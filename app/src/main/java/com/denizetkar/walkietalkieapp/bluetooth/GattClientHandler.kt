package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ClientEvent {
    data class Connected(val device: BluetoothDevice) : ClientEvent()
    data class Authenticated(val device: BluetoothDevice) : ClientEvent()
    data class Disconnected(val device: BluetoothDevice) : ClientEvent()
    data class MessageReceived(val device: BluetoothDevice, val data: ByteArray, val type: TransportDataType) : ClientEvent()
    data class Error(val device: BluetoothDevice, val message: String) : ClientEvent()
}

class GattClientHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    val targetDevice: BluetoothDevice,
    private val ownNodeId: UInt,
    private val accessCode: String
) {
    private var bluetoothGatt: BluetoothGatt? = null

    // If 'scope' dies, the queue operations die automatically.
    private val operationQueue = BleOperationQueue(scope) { disconnect() }

    private var currentMtu = Config.BLE_DEFAULT_MTU

    private val _clientEvents = MutableSharedFlow<ClientEvent>(
        extraBufferCapacity = Config.EVENT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val clientEvents: SharedFlow<ClientEvent> = _clientEvents

    private var handshakeTimeoutJob: Job? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        Log.d("GattClient", "Connecting to ${targetDevice.address}...")
        startHandshakeTimeout()
        try {
            bluetoothGatt = targetDevice.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                null
            )
        } catch (_: SecurityException) {
            scope.launch { _clientEvents.emit(ClientEvent.Error(targetDevice, "Permission Missing")) }
        }
    }

    private fun startHandshakeTimeout() {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            delay(Config.GROUP_JOIN_TIMEOUT)
            Log.w("GattClient", "Handshake Timed Out")
            _clientEvents.emit(ClientEvent.Error(targetDevice, "Handshake Timeout"))
            disconnect()
        }
    }

    /**
     * Polite disconnect. Tries to disconnect cleanly and waits for callback.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        handshakeTimeoutJob?.cancel()
        operationQueue.shutdown()
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) { }
    }

    /**
     * Immediate Resource Release.
     * Unlike disconnect(), this does not wait for callbacks.
     * Used when the service is being destroyed to prevent leaks.
     */
    @SuppressLint("MissingPermission")
    fun close() {
        handshakeTimeoutJob?.cancel()
        operationQueue.shutdown()
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        scope.cancel()
    }

    fun sendMessage(type: TransportDataType, data: ByteArray) {
        val (uuid, writeType) = when (type) {
            TransportDataType.AUDIO -> Config.CHAR_DATA_UUID to BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            TransportDataType.CONTROL -> Config.CHAR_CONTROL_UUID to BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        // DUMB PIPE: We no longer wrap CONTROL packets here.
        // The MeshController is responsible for the wire format.
        operationQueue.enqueue(type) {
            writeCharacteristicInternal(uuid, data, writeType, type)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("GattClient", "Connection Error: $status")
                scope.launch { _clientEvents.emit(ClientEvent.Error(targetDevice, "Connection Error: $status")) }
                close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("GattClient", "Connected. Requesting High Priority & Starting Service Discovery...")
                scope.launch { _clientEvents.emit(ClientEvent.Connected(targetDevice)) }
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                operationQueue.enqueue(TransportDataType.CONTROL) {
                    if (!gatt.discoverServices()) {
                        Log.e("GattClient", "Service Discovery Failed to Start")
                        disconnect()
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("GattClient", "Disconnected from ${targetDevice.address}")
                scope.launch { _clientEvents.emit(ClientEvent.Disconnected(targetDevice)) }
                close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            operationQueue.operationCompleted()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("GattClient", "Service Discovery Failed: $status")
                disconnect()
                return
            }

            val service = gatt.getService(Config.APP_SERVICE_UUID) ?: run {
                Log.e("GattClient", "Target does not have the WalkieTalkie Service!")
                disconnect()
                return
            }

            Log.d("GattClient", "Services Discovered. Queueing Subscriptions (MTU 23)...")
            operationQueue.enqueue(TransportDataType.CONTROL) {
                delay(Config.GATT_SUBSCRIPTION_DELAY)
                subscribeToCharacteristic(gatt, service, Config.CHAR_CONTROL_UUID)
            }
            operationQueue.enqueue(TransportDataType.CONTROL) {
                subscribeToCharacteristic(gatt, service, Config.CHAR_DATA_UUID)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d("GattClient", "Descriptor Write: ${descriptor.characteristic.uuid}, $status")
            operationQueue.operationCompleted()
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.characteristic.uuid == Config.CHAR_CONTROL_UUID) {
                sendHello()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            operationQueue.operationCompleted()
            Log.d("GattClient", "MTU Negotiated: $mtu")
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= Config.BLE_MTU_MIN) {
                currentMtu = mtu
                scope.launch { _clientEvents.emit(ClientEvent.Authenticated(targetDevice)) }
            } else {
                Log.e("GattClient", "MTU too low ($mtu) or failed ($status). Disconnecting.")
                scope.launch {
                    _clientEvents.emit(ClientEvent.Error(targetDevice, "Device not supported: Low MTU"))
                }
                disconnect()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // This callback fires even for WRITE_TYPE_NO_RESPONSE (on most devices)
            // to indicate the buffer is free. We must always unblock the queue here.
            operationQueue.operationCompleted()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingData(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleIncomingData(characteristic.uuid, characteristic.value)
        }
    }

    private fun sendHello() {
        Log.d("GattClient", "Subscription Confirmed. Sending HELLO.")
        val packet = PacketUtils.createControlPacket(PacketUtils.TYPE_CLIENT_HELLO, ByteArray(0))
        operationQueue.enqueue(TransportDataType.CONTROL) {
            writeCharacteristicInternal(Config.CHAR_CONTROL_UUID, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, TransportDataType.CONTROL)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToCharacteristic(gatt: BluetoothGatt, service: BluetoothGattService, charUUID: UUID) {
        val characteristic = service.getCharacteristic(charUUID) ?: run {
            Log.e("GattClient", "Characteristic not found for $charUUID")
            operationQueue.operationCompleted()
            return
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e("GattClient", "setCharacteristicNotification failed for $charUUID")
            operationQueue.operationCompleted()
            return
        }

        Log.d("GattClient", "Getting CCCD for $charUUID")
        val descriptor = characteristic.getDescriptor(GattServerHandler.CCCD_UUID) ?: run {
            Log.e("GattClient", "CCCD not found for $charUUID")
            operationQueue.operationCompleted()
            return
        }

        Log.d("GattClient", "Subscribing to $charUUID")
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }

        if (!success) {
            Log.e("GattClient", "writeDescriptor failed for $charUUID")
            operationQueue.operationCompleted()
        } else {
            Log.d("GattClient", "writeDescriptor succeeded for $charUUID")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleIncomingData(uuid: UUID, data: ByteArray) {
        when (uuid) {
            Config.CHAR_CONTROL_UUID -> {
                val (type, payload) = PacketUtils.parseControlPacket(data) ?: return
                when (type) {
                    PacketUtils.TYPE_AUTH_CHALLENGE -> {
                        val nonce = String(payload, Charsets.UTF_8)
                        solveChallenge(nonce)
                    }
                    PacketUtils.TYPE_AUTH_RESULT -> {
                        if (payload.isNotEmpty() && payload[0] == 1.toByte()) {
                            handshakeTimeoutJob?.cancel()
                            Log.d("GattClient", "Auth Success. Requesting MTU...")
                            operationQueue.enqueue(TransportDataType.CONTROL) {
                                if (!bluetoothGatt?.requestMtu(Config.BLE_MTU_TARGET)!!) {
                                    Log.e("GattClient", "MTU Request Failed")
                                    scope.launch { _clientEvents.emit(ClientEvent.Authenticated(targetDevice)) }
                                }
                            }
                        } else {
                            disconnect()
                        }
                    }
                    else -> {
                        // Pass the FULL PACKET (data) up, not just the payload.
                        // This allows the Controller to hash the exact bytes received.
                        scope.launch { _clientEvents.emit(ClientEvent.MessageReceived(targetDevice, data, TransportDataType.CONTROL)) }
                    }
                }
            }
            Config.CHAR_DATA_UUID -> {
                scope.launch { _clientEvents.emit(ClientEvent.MessageReceived(targetDevice, data, TransportDataType.AUDIO)) }
            }
        }
    }

    private fun solveChallenge(nonce: String) {
        val responsePayload = ProtocolUtils.generateHandshakeResponse(accessCode, nonce, ownNodeId)
        val packet = PacketUtils.createControlPacket(PacketUtils.TYPE_AUTH_RESPONSE, responsePayload)
        Log.d("GattClient", "Sending Challenge Response...")

        operationQueue.enqueue(TransportDataType.CONTROL) {
            writeCharacteristicInternal(Config.CHAR_CONTROL_UUID, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, TransportDataType.CONTROL)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristicInternal(uuid: UUID, data: ByteArray, writeType: Int, type: TransportDataType) {
        val service = bluetoothGatt?.getService(Config.APP_SERVICE_UUID) ?: run {
            Log.e("GattClient", "Service not found when writing to $uuid")
            operationQueue.operationCompleted()
            return
        }
        val char = service.getCharacteristic(uuid) ?: run {
            Log.e("GattClient", "Characteristic not found: $uuid")
            operationQueue.operationCompleted()
            return
        }

        val maxAttempts = if (type == TransportDataType.CONTROL) Config.GATT_RETRY_ATTEMPTS else 1
        repeat(maxAttempts) { attempt ->
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(char, data, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                bluetoothGatt?.writeCharacteristic(char) == true
            }

            if (success) {
                // Stack accepted the request.
                // We return immediately and wait for onCharacteristicWrite to unblock queue.
                if (attempt > 0) Log.w("GattClient", "Write to $uuid succeeded after $attempt retries")
                return
            }

            // Failure (Stack busy). If Control, wait briefly and retry.
            if (type == TransportDataType.CONTROL) delay(Config.GATT_RETRY_COOLDOWN)
        }

        // If we reach here, we exhausted retries (or failed the single Audio attempt).
        // Since success was never true, onCharacteristicWrite will NEVER fire.
        // We MUST manually unblock the queue.
        if (type == TransportDataType.CONTROL) {
            Log.e("GattClient", "CRITICAL: FAILED to write CONTROL to $uuid after $maxAttempts attempts.")
        } else {
            // For audio, this is expected occasionally. Use warn/debug.
            Log.w("GattClient", "Dropped Audio Packet (Stack Busy)")
        }
        operationQueue.operationCompleted()
    }
}