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
import android.content.Context
import android.os.Build
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.logic.PacketUtils
import com.denizetkar.walkietalkieapp.logic.ProtocolUtils
import com.denizetkar.walkietalkieapp.network.TransportDataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class ServerEvent {
    data class ClientConnected(val device: BluetoothDevice) : ServerEvent()
    data class ClientAuthenticated(val device: BluetoothDevice, val nodeId: Int) : ServerEvent()
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
    private val authenticatedSessions = ConcurrentHashMap<String, Int>()
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    private val _serverEvents = MutableSharedFlow<ServerEvent>()
    val serverEvents: SharedFlow<ServerEvent> = _serverEvents

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (gattServer != null) return

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("GattServer", "New Connection: ${device.address}")
                    connectedDevices[device.address.uppercase()] = device
                    scope.launch { _serverEvents.emit(ServerEvent.ClientConnected(device)) }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("GattServer", "Disconnected: ${device.address}")
                    cleanupDevice(device)
                    scope.launch { _serverEvents.emit(ServerEvent.ClientDisconnected(device)) }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)

                if (descriptor.characteristic.uuid == Config.CHAR_CONTROL_UUID && descriptor.uuid == CCCD_UUID) {
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        Log.d("GattServer", "Client subscribed to Control. Sending Challenge to ${device.address}")
                        scope.launch {
                            sendChallenge(device)
                        }
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)

                when (characteristic.uuid) {
                    Config.CHAR_DATA_UUID -> {
                        if (authenticatedSessions.containsKey(device.address.uppercase())) {
                            // Strip header if you added one for Data, or just pass raw
                            // For now assuming raw audio or simple type header
                            scope.launch { _serverEvents.emit(ServerEvent.MessageReceived(device, value, TransportDataType.AUDIO)) }
                        }
                    }
                    Config.CHAR_CONTROL_UUID -> {
                        handleControlMessage(device, value)
                    }
                }
            }
        }
        gattServer = bluetoothManager.openGattServer(context, callback)
        setupService()
        Log.d("GattServer", "GATT Server Started")
    }

    private fun handleControlMessage(device: BluetoothDevice, data: ByteArray) {
        val (type, payload) = PacketUtils.parseControlPacket(data) ?: return

        when (type) {
            PacketUtils.TYPE_AUTH_RESPONSE -> handleAuthResponse(device, payload)
            PacketUtils.TYPE_HEARTBEAT -> {
                if (authenticatedSessions.containsKey(device.address.uppercase())) {
                    scope.launch { _serverEvents.emit(ServerEvent.MessageReceived(device, payload, TransportDataType.CONTROL)) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendChallenge(device: BluetoothDevice) {
        val nonce = UUID.randomUUID().toString().substring(0, 8)
        pendingChallenges[device.address.uppercase()] = nonce

        val packet = PacketUtils.createControlPacket(PacketUtils.TYPE_AUTH_CHALLENGE, nonce.toByteArray(Charsets.UTF_8))
        notifyDevice(device, packet, TransportDataType.CONTROL)
    }

    @SuppressLint("MissingPermission")
    private fun handleAuthResponse(device: BluetoothDevice, payload: ByteArray) {
        val nonce = pendingChallenges[device.address.uppercase()]
        val code = currentAccessCode
        if (nonce == null || code == null) return

        val clientNodeId = ProtocolUtils.verifyHandshake(payload, code, nonce)

        if (clientNodeId != null) {
            Log.i("GattServer", "Authenticated Node: $clientNodeId")
            pendingChallenges.remove(device.address.uppercase())
            authenticatedSessions[device.address.uppercase()] = clientNodeId

            // Send Success
            val successPacket = PacketUtils.createControlPacket(PacketUtils.TYPE_AUTH_RESULT, byteArrayOf(0x01))
            notifyDevice(device, successPacket, TransportDataType.CONTROL)

            scope.launch { _serverEvents.emit(ServerEvent.ClientAuthenticated(device, clientNodeId)) }
        } else {
            Log.w("GattServer", "Auth Failed")
            // Send Fail
            val failPacket = PacketUtils.createControlPacket(PacketUtils.TYPE_AUTH_RESULT, byteArrayOf(0x00))
            notifyDevice(device, failPacket, TransportDataType.CONTROL)
            gattServer?.cancelConnection(device)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendTo(address: String, data: ByteArray, type: TransportDataType) {
        val device = connectedDevices[address.uppercase()] ?: return
        if (!authenticatedSessions.containsKey(address.uppercase())) return

        val packet = if (type == TransportDataType.CONTROL) {
            PacketUtils.createControlPacket(PacketUtils.TYPE_HEARTBEAT, data)
        } else {
            // For Audio, we might send raw or wrap. Let's send raw for speed as per architecture
            data
        }

        notifyDevice(device, packet, type)
    }

    @SuppressLint("MissingPermission")
    private fun notifyDevice(device: BluetoothDevice, data: ByteArray, type: TransportDataType) {
        val uuid = if (type == TransportDataType.AUDIO) Config.CHAR_DATA_UUID else Config.CHAR_CONTROL_UUID
        val service = gattServer?.getService(Config.APP_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(uuid) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer?.notifyCharacteristicChanged(device, char, false, data)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gattServer?.notifyCharacteristicChanged(device, char, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupService() {
        val service = BluetoothGattService(Config.APP_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Data (Audio): WriteNoResp | Notify
        val dataChar = BluetoothGattCharacteristic(Config.CHAR_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        dataChar.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
        service.addCharacteristic(dataChar)

        // Control: Write | Notify
        val controlChar = BluetoothGattCharacteristic(Config.CHAR_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        controlChar.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
        service.addCharacteristic(controlChar)

        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        val device = connectedDevices[address.uppercase()]
        if (device != null) gattServer?.cancelConnection(device)
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        connectedDevices.forEach { (_, device) -> gattServer!!.cancelConnection(device) }
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
        cleanupDevice(null)
    }

    private fun cleanupDevice(device: BluetoothDevice?) {
        if (device != null) {
            connectedDevices.remove(device.address.uppercase())
            pendingChallenges.remove(device.address.uppercase())
            authenticatedSessions.remove(device.address.uppercase())
        } else {
            connectedDevices.clear()
            pendingChallenges.clear()
            authenticatedSessions.clear()
        }
    }
}