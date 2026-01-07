package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.logic.PacketUtils
import com.denizetkar.walkietalkieapp.logic.ProtocolUtils
import com.denizetkar.walkietalkieapp.network.TransportDataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class ServerEvent {
    data class ClientConnected(val device: BluetoothDevice) : ServerEvent()
    data class ClientAuthenticated(val device: BluetoothDevice, val nodeId: UInt) : ServerEvent() // CHANGED
    data class ClientDisconnected(val device: BluetoothDevice) : ServerEvent()
    data class MessageReceived(val device: BluetoothDevice, val data: ByteArray, val type: TransportDataType) : ServerEvent()
}

class GattServerHandler(
    private val context: Context,
    private val scope: CoroutineScope
) {
    var currentAccessCode: String? = null
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null

    private val pendingChallenges = ConcurrentHashMap<String, String>()
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val pendingDisconnects = ConcurrentHashMap.newKeySet<String>()

    private val _serverEvents = MutableSharedFlow<ServerEvent>(
        extraBufferCapacity = Config.EVENT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val serverEvents: SharedFlow<ServerEvent> = _serverEvents

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address.uppercase()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("GattServer", "New Connection: $address")
                connectedDevices[address] = device
                scope.launch { _serverEvents.emit(ServerEvent.ClientConnected(device)) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("GattServer", "Disconnected: $address")
                cleanupDevice(device)
                scope.launch { _serverEvents.emit(ServerEvent.ClientDisconnected(device)) }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            Log.d("GattServer", "Descriptor Write Request: ${descriptor.uuid} from ${device.address}")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)

            when (characteristic.uuid) {
                Config.CHAR_DATA_UUID -> {
                    scope.launch { _serverEvents.emit(ServerEvent.MessageReceived(device, value, TransportDataType.AUDIO)) }
                }
                Config.CHAR_CONTROL_UUID -> handleControlMessage(device, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val normalizedAddress = device.address.uppercase()
            if (pendingDisconnects.contains(normalizedAddress)) {
                Log.i("GattServer", "Packet flushed. Closing connection for $normalizedAddress")
                gattServer?.cancelConnection(device)
                pendingDisconnects.remove(normalizedAddress)
            }
        }
    }

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (gattServer != null) return

        gattServer = bluetoothManager.openGattServer(context, gattCallback)
        setupService()
        Log.d("GattServer", "GATT Server Started")
    }

    private fun handleControlMessage(device: BluetoothDevice, data: ByteArray) {
        val (type, payload) = PacketUtils.parseControlPacket(data) ?: return

        when (type) {
            PacketUtils.TYPE_PING -> {
                Log.d("GattServer", "Received PING from ${device.address}")
            }
            PacketUtils.TYPE_CLIENT_HELLO -> {
                Log.d("GattServer", "Received HELLO from ${device.address}. Sending Challenge.")
                scope.launch { sendChallenge(device) }
            }
            PacketUtils.TYPE_AUTH_RESPONSE -> handleAuthResponse(device, payload)
            PacketUtils.TYPE_HEARTBEAT -> {
                scope.launch { _serverEvents.emit(ServerEvent.MessageReceived(device, payload, TransportDataType.CONTROL)) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendChallenge(device: BluetoothDevice) {
        val nonce = UUID.randomUUID().toString().substring(0, 8)
        pendingChallenges[device.address.uppercase()] = nonce
        val packet = PacketUtils.createControlPacket(PacketUtils.TYPE_AUTH_CHALLENGE, nonce.toByteArray(Charsets.UTF_8))
        notifyDevice(device, packet, TransportDataType.CONTROL)
    }

    @SuppressLint("MissingPermission")
    private fun handleAuthResponse(device: BluetoothDevice, payload: ByteArray) {
        val address = device.address.uppercase()
        val nonce = pendingChallenges.remove(address) ?: run {
            Log.w("GattServer", "Duplicate or invalid Auth Response from $address")
            return
        }
        val code = currentAccessCode ?: return

        val clientNodeId = ProtocolUtils.verifyHandshake(payload, code, nonce)
        scope.launch {
            if (clientNodeId != null) {
                Log.i("GattServer", "Authenticated Node: $clientNodeId")
                pendingChallenges.remove(address)
                val successPacket = PacketUtils.createControlPacket(PacketUtils.TYPE_AUTH_RESULT, byteArrayOf(0x01))
                notifyDevice(device, successPacket, TransportDataType.CONTROL)
                _serverEvents.emit(ServerEvent.ClientAuthenticated(device, clientNodeId))
            } else {
                Log.w("GattServer", "Auth Failed. Sending NACK.")
                val failPacket = PacketUtils.createControlPacket(PacketUtils.TYPE_AUTH_RESULT, byteArrayOf(0x00))
                pendingDisconnects.add(address)
                notifyDevice(device, failPacket, TransportDataType.CONTROL)
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun sendTo(device: BluetoothDevice, data: ByteArray, type: TransportDataType) {
        val packet = if (type == TransportDataType.CONTROL) {
            PacketUtils.createControlPacket(PacketUtils.TYPE_HEARTBEAT, data)
        } else {
            data
        }
        notifyDevice(device, packet, type)
    }

    @SuppressLint("MissingPermission")
    private suspend fun notifyDevice(device: BluetoothDevice, data: ByteArray, type: TransportDataType): Boolean {
        val server = gattServer ?: return false
        val service = server.getService(Config.APP_SERVICE_UUID) ?: return false
        val uuid = if (type == TransportDataType.AUDIO) Config.CHAR_DATA_UUID else Config.CHAR_CONTROL_UUID
        val char = service.getCharacteristic(uuid) ?: return false

        if (type == TransportDataType.AUDIO) {
            return server.notifyCompat(device, char, data)
        }

        // Simple retry for control packets
        repeat(Config.GATT_RETRY_ATTEMPTS) {
            if (server.notifyCompat(device, char, data)) return true
            yield()
        }

        Log.e("GattServer", "CRITICAL: Failed to queue Control Packet for ${device.address}")
        return false
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun BluetoothGattServer.notifyCompat(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifyCharacteristicChanged(device, characteristic, false, data) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = data
            notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupService() {
        val service = BluetoothGattService(Config.APP_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val dataChar = BluetoothGattCharacteristic(Config.CHAR_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        dataChar.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
        service.addCharacteristic(dataChar)

        val controlChar = BluetoothGattCharacteristic(Config.CHAR_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        controlChar.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
        service.addCharacteristic(controlChar)

        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    fun disconnect(device: BluetoothDevice) {
        gattServer?.cancelConnection(device)
        cleanupDevice(device)
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        connectedDevices.values.forEach { gattServer?.cancelConnection(it) }
        cleanupDevice(null)
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        disconnectAll()
        gattServer?.close()
        gattServer = null
        cleanupDevice(null)
    }

    private fun cleanupDevice(device: BluetoothDevice?) {
        if (device != null) {
            val address = device.address.uppercase()
            connectedDevices.remove(address)
            pendingChallenges.remove(address)
            pendingDisconnects.remove(address)
        } else {
            connectedDevices.clear()
            pendingChallenges.clear()
            pendingDisconnects.clear()
        }
    }
}