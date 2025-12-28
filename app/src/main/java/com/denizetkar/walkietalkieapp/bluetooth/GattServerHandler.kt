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
    // Map Address -> NodeId
    private val authenticatedSessions = ConcurrentHashMap<String, Int>()
    // Map Address -> Device Object (needed for notify)
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    private val _serverEvents = MutableSharedFlow<ServerEvent>()
    val serverEvents: SharedFlow<ServerEvent> = _serverEvents

    companion object {
        val SERVICE_UUID: UUID = Config.APP_SERVICE_UUID
        val CHAR_AUDIO_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805f9b34fb")
        val CHAR_CONTROL_UUID: UUID = UUID.fromString("00002222-0000-1000-8000-00805f9b34fb")
        val CHAR_CHALLENGE_UUID: UUID = UUID.fromString("00003333-0000-1000-8000-00805f9b34fb")
        val CHAR_RESPONSE_UUID: UUID = UUID.fromString("00004444-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (gattServer != null) return
        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("GattServer", "New Connection: ${device.address}")
                    connectedDevices[device.address] = device
                    scope.launch { _serverEvents.emit(ServerEvent.ClientConnected(device)) }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("GattServer", "Disconnected: ${device.address}")
                    connectedDevices.remove(device.address)
                    pendingChallenges.remove(device.address)
                    authenticatedSessions.remove(device.address)
                    scope.launch { _serverEvents.emit(ServerEvent.ClientDisconnected(device)) }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                if (descriptor.characteristic.uuid == CHAR_CHALLENGE_UUID && descriptor.uuid == CCCD_UUID) {
                    sendChallenge(device)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)

                when (characteristic.uuid) {
                    CHAR_RESPONSE_UUID -> handleHandshakeResponse(device, value)
                    CHAR_AUDIO_UUID -> {
                        if (authenticatedSessions.containsKey(device.address)) {
                            scope.launch { _serverEvents.emit(ServerEvent.MessageReceived(device, value, TransportDataType.AUDIO)) }
                        }
                    }
                    CHAR_CONTROL_UUID -> {
                        if (authenticatedSessions.containsKey(device.address)) {
                            scope.launch { _serverEvents.emit(ServerEvent.MessageReceived(device, value, TransportDataType.CONTROL)) }
                        }
                    }
                }
            }
        }
        gattServer = bluetoothManager.openGattServer(context, callback)
        setupService()
    }

    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        val device = connectedDevices[address]
        if (device != null) {
            gattServer?.cancelConnection(device)
        }
    }

    // NEW: Unicast Send from Server to Client
    @SuppressLint("MissingPermission")
    fun sendTo(address: String, data: ByteArray, type: TransportDataType) {
        val device = connectedDevices[address] ?: return
        if (!authenticatedSessions.containsKey(address)) return
        notifyDevice(device, data, type)
    }

    // UPDATED: Multicast Send from Server to Clients
    @SuppressLint("MissingPermission")
    fun broadcastToClients(data: ByteArray, excludeAddress: String?, type: TransportDataType) {
        authenticatedSessions.keys.forEach { address ->
            if (address != excludeAddress) {
                val device = connectedDevices[address] ?: return@forEach
                notifyDevice(device, data, type)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyDevice(device: BluetoothDevice, data: ByteArray, type: TransportDataType) {
        val uuid = if (type == TransportDataType.AUDIO) CHAR_AUDIO_UUID else CHAR_CONTROL_UUID
        val service = gattServer?.getService(SERVICE_UUID) ?: return
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
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Audio: Write No Response (Client->Server), Notify (Server->Client)
        val audio = BluetoothGattCharacteristic(CHAR_AUDIO_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        audio.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
        service.addCharacteristic(audio)

        // Control: Write Default (Client->Server), Notify (Server->Client)
        val control = BluetoothGattCharacteristic(CHAR_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        control.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
        service.addCharacteristic(control)

        val challenge = BluetoothGattCharacteristic(CHAR_CHALLENGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        challenge.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
        service.addCharacteristic(challenge)

        service.addCharacteristic(BluetoothGattCharacteristic(CHAR_RESPONSE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))

        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    private fun sendChallenge(device: BluetoothDevice) {
        val nonce = UUID.randomUUID().toString().substring(0, 8)
        pendingChallenges[device.address] = nonce
        val char = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_CHALLENGE_UUID) ?: return
        val data = nonce.toByteArray(Charsets.UTF_8)
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
    private fun handleHandshakeResponse(device: BluetoothDevice, value: ByteArray) {
        val nonce = pendingChallenges[device.address]
        val code = currentAccessCode
        if (nonce == null || code == null) return

        val clientNodeId = ProtocolUtils.verifyHandshake(value, code, nonce)

        if (clientNodeId != null) {
            Log.i("GattServer", "Authenticated Node: $clientNodeId (MAC: ${device.address})")
            pendingChallenges.remove(device.address)
            authenticatedSessions[device.address] = clientNodeId
            scope.launch { _serverEvents.emit(ServerEvent.ClientAuthenticated(device, clientNodeId)) }
        } else {
            Log.w("GattServer", "Auth Failed for ${device.address}")
            gattServer?.cancelConnection(device)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
        authenticatedSessions.clear()
        pendingChallenges.clear()
        connectedDevices.clear()
    }
}