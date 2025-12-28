package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

// Events specific to a Client connection
sealed class ClientEvent {
    data class Connected(val device: BluetoothDevice) : ClientEvent()
    data class Authenticated(val device: BluetoothDevice) : ClientEvent()
    data class Disconnected(val device: BluetoothDevice) : ClientEvent()
    data class MessageReceived(val device: BluetoothDevice, val data: ByteArray) : ClientEvent()
    data class Error(val device: BluetoothDevice, val message: String) : ClientEvent()
}

class GattClientHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val targetDevice: BluetoothDevice,
    private val ownNodeId: Int,
    private val accessCode: String
) {
    private var bluetoothGatt: BluetoothGatt? = null

    private val _clientEvents = MutableSharedFlow<ClientEvent>()
    val clientEvents: SharedFlow<ClientEvent> = _clientEvents

    @SuppressLint("MissingPermission")
    fun connect() {
        Log.d("GattClient", "Connecting to ${targetDevice.address}...")
        // AutoConnect = false for faster connections (Direct Connection)
        bluetoothGatt = targetDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    @SuppressLint("MissingPermission")
    fun sendAudio(data: ByteArray) {
        writeCharacteristic(GattServerHandler.CHAR_AUDIO_UUID, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    @SuppressLint("MissingPermission")
    fun sendControlMessage(data: ByteArray) {
        writeCharacteristic(GattServerHandler.CHAR_CONTROL_UUID, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("GattClient", "Connection Error: $status")
                scope.launch { _clientEvents.emit(ClientEvent.Error(targetDevice, "Connection Error: $status")) }
                disconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("GattClient", "Connected to ${targetDevice.address}. Discovering Services...")
                scope.launch { _clientEvents.emit(ClientEvent.Connected(targetDevice)) }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("GattClient", "Disconnected from ${targetDevice.address}")
                scope.launch { _clientEvents.emit(ClientEvent.Disconnected(targetDevice)) }
                disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(Config.APP_SERVICE_UUID)
                if (service != null) {
                    Log.d("GattClient", "Services Discovered. Subscribing to Challenge...")
                    // 1. Subscribe to Challenge (to receive the Nonce)
                    enableNotification(gatt, service, GattServerHandler.CHAR_CHALLENGE_UUID)

                    // 2. Subscribe to Control (to receive Heartbeats)
                    // Note: We usually need to wait for the first descriptor write to finish before writing the next.
                    // For MVP simplicity, we might rely on the OS queue, but robust apps use a command queue.
                    // We will trigger Control subscription after Handshake is done to be safe.
                } else {
                    Log.e("GattClient", "Target does not have the WalkieTalkie Service!")
                    disconnect()
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // API 33+ Callback
            handleIncomingData(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Legacy Callback
            @Suppress("DEPRECATION")
            handleIncomingData(characteristic.uuid, characteristic.value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleIncomingData(uuid: UUID, data: ByteArray) {
        when (uuid) {
            GattServerHandler.CHAR_CHALLENGE_UUID -> {
                val nonce = String(data, Charsets.UTF_8)
                Log.d("GattClient", "Received Challenge Nonce: $nonce")
                solveChallenge(nonce)
            }
            GattServerHandler.CHAR_CONTROL_UUID -> {
                scope.launch { _clientEvents.emit(ClientEvent.MessageReceived(targetDevice, data)) }
            }
            // Audio usually doesn't come via Notify on Client side (Client Writes to Server),
            // unless we implement bi-directional Audio via Notify.
            // For now, let's assume Client Pushes Audio to Server, and Server Pushes Audio to Client via Notify?
            // Actually, standard GATT is Client Writes to Server.
            // To receive Audio from Server, we MUST subscribe to Audio Characteristic Notifications too.
        }
    }

    @SuppressLint("MissingPermission")
    private fun solveChallenge(nonce: String) {
        // 1. Calculate Hash: SHA256(AccessCode + Nonce)
        val fullHash = MessageDigest.getInstance("SHA-256").digest((accessCode + nonce).toByteArray(Charsets.UTF_8))
        val hashBytes = fullHash.copyOfRange(0, 16) // First 16 bytes

        // 2. Prepare Node ID (4 bytes)
        val nodeIdBytes = ByteBuffer.allocate(4).putInt(ownNodeId).array()

        // 3. Combine: [Hash (16)] + [NodeID (4)]
        val responsePayload = hashBytes + nodeIdBytes

        Log.d("GattClient", "Sending Challenge Response...")
        writeCharacteristic(GattServerHandler.CHAR_RESPONSE_UUID, responsePayload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

        // Assume success if not disconnected immediately.
        // Realistically, we should wait for a "Welcome" control message, but for now:
        scope.launch { _clientEvents.emit(ClientEvent.Authenticated(targetDevice)) }

        // Now safe to subscribe to other notifications (Control/Audio)
        bluetoothGatt?.getService(Config.APP_SERVICE_UUID)?.let { service ->
            enableNotification(bluetoothGatt!!, service, GattServerHandler.CHAR_CONTROL_UUID)
            // If we want to hear audio from this peer, we must subscribe to Audio too
            // enableNotification(bluetoothGatt!!, service, GattServerHandler.CHAR_AUDIO_UUID)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, service: BluetoothGattService, charUUID: UUID) {
        val characteristic = service.getCharacteristic(charUUID) ?: return
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(GattServerHandler.CCCD_UUID)
        if (descriptor != null) {
            // API 33+ vs Legacy
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(uuid: UUID, data: ByteArray, writeType: Int) {
        val service = bluetoothGatt?.getService(Config.APP_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(uuid) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(char, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            bluetoothGatt?.writeCharacteristic(char)
        }
    }
}