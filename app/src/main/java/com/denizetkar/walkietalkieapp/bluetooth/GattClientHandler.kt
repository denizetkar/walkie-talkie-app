package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.domain.PeerId
import com.denizetkar.walkietalkieapp.protocol.HandshakeLogic
import com.denizetkar.walkietalkieapp.network.ClientEvent
import com.denizetkar.walkietalkieapp.network.ConnectionFailure
import com.denizetkar.walkietalkieapp.network.TransportAddress
import com.denizetkar.walkietalkieapp.network.TransportDataType
import com.denizetkar.walkietalkieapp.protocol.Packet
import com.denizetkar.walkietalkieapp.protocol.Protocol
import com.denizetkar.walkietalkieapp.utils.retryWithBackoff
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GattClientHandler(
    private val context: Context,
    val scope: CoroutineScope, // Exposed for Driver to launch event collector
    val targetDevice: BluetoothDevice,
    private val ownNodeId: PeerId,
    private val accessCode: String
) {
    private var bluetoothGatt: BluetoothGatt? = null
    // If 'scope' dies, the queue operations die automatically.
    private val operationQueue = BleOperationQueue(scope)
    private var currentMtu = Config.BLE_DEFAULT_MTU

    // --- Callback Bridge ---
    // Single atomic reference for ANY pending async GATT operation.
    // Safe because BleOperationQueue ensures we only do one thing at a time.
    private val pendingAction = AtomicReference<CancellableContinuation<Unit>?>(null)

    private val _clientEvents = MutableSharedFlow<ClientEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val clientEvents: SharedFlow<ClientEvent> = _clientEvents.asSharedFlow()

    @SuppressLint("MissingPermission")
    fun connect() {
        val address = TransportAddress.from(targetDevice.address)
        Log.d("GattClient", "Connecting to $address...")
        try {
            bluetoothGatt = targetDevice.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            Log.e("GattClient", "Connect Failed Exception", e)
            fail(ConnectionFailure.Io("Connect Exception: ${e.message}"))
        }
    }

    /**
     * Polite disconnect with a Safety Net.
     * Tries to disconnect cleanly, but guarantees resource release after a timeout.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        operationQueue.shutdown()
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) { }

        // SAFETY NET: If the stack doesn't fire the callback,
        // we assume the connection is dead and force a cleanup to prevent leaks.
        scope.launch {
            delay(Config.PEER_DISCONNECT_TIMEOUT)
            Log.w("GattClient", "Disconnect callback timed out. Forcing close.")
            close()
        }
    }

    /**
     * Immediate Resource Release.
     * Unlike disconnect(), this does not wait for callbacks.
     * Used when the service is being destroyed to prevent leaks.
     */
    @SuppressLint("MissingPermission")
    fun close() {
        operationQueue.shutdown()
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        // This cancels the scope, which also cancels the Safety Net job above.
        scope.cancel()
    }

    fun sendMessage(type: TransportDataType, data: ByteArray) {
        // MTU SAFETY CHECK
        // We subtract 3 bytes for the ATT header (OpCode + Handle)
        if (data.size > currentMtu - 3) {
            Log.w("GattClient", "Packet size ${data.size} exceeds MTU ${currentMtu - 3}. Dropping.")
            return
        }

        // FIRE AND FORGET (Actor Model)
        // We enqueue the operation. If it fails inside the Actor, we must handle it there.
        operationQueue.enqueue(type) {
            try {
                val maxAttempts = if (type == TransportDataType.CONTROL) Config.GATT_RETRY_ATTEMPTS else 1
                retryWithBackoff(maxAttempts, Config.GATT_RETRY_COOLDOWN) {
                    writeCharacteristic(data, type)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    fail(ConnectionFailure.Timeout("Characteristic Write Timeout: ${t.message}"))
                    throw t // Re-throw to satisfy Coroutine flow
                }

                if (type == TransportDataType.CONTROL) {
                    fail(ConnectionFailure.Io("Characteristic Write Failed: ${t.message}"))
                } else {
                    Log.w("GattClient", "Dropped Audio Packet (Stack Busy/Error)")
                }
            }
        }
    }

    /**
     * Encapsulates the "Suspend -> Resume" logic.
     */
    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristic(data: ByteArray, type: TransportDataType) {
        val (uuid, writeType) = when (type) {
            TransportDataType.AUDIO -> Config.CHAR_DATA_UUID to BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            TransportDataType.CONTROL -> Config.CHAR_CONTROL_UUID to BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val gatt = bluetoothGatt ?: throw Exception("Gatt is null")
        val service = gatt.getService(Config.APP_SERVICE_UUID) ?: throw Exception("Service not found when writing to $uuid")
        val char = service.getCharacteristic(uuid) ?: throw Exception("Characteristic not found: $uuid")

        suspendCancellableCoroutine { cont ->
            pendingAction.set(cont)

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }

            if (!success) {
                pendingAction.set(null)
                if (cont.isActive) cont.resumeWithException(Exception("Stack Busy"))
            }
        }
    }

    /**
     * The Linear Handshake Sequence.
     * This runs inside the OperationQueue, ensuring no other packets interfere.
     */
    @SuppressLint("MissingPermission")
    private suspend fun performHandshakeSequence(gatt: BluetoothGatt) {
        try {
            // 1. Start Discovery
            if (!gatt.discoverServices()) throw Exception("Service Discovery Start Failed")

            // 2. Wait for Callback (Reactive Bridge)
            suspendCancellableCoroutine { cont ->
                pendingAction.set(cont)
                // If discovery takes too long, the OperationQueue timeout will kill this
            }

            // 3. Subscribe
            val service = gatt.getService(Config.APP_SERVICE_UUID) ?: throw Exception("Target does not have the WalkieTalkie Service!")
            delay(Config.GATT_SUBSCRIPTION_DELAY)
            subscribeToCharacteristic(gatt, service, Config.CHAR_CONTROL_UUID)
            subscribeToCharacteristic(gatt, service, Config.CHAR_DATA_UUID)

            // 4. Send Hello
            Log.d("GattClient", "Subscription Confirmed. Sending HELLO.")
            val packet = Packet.Control.Raw(Protocol.OP_HELLO, ByteArray(0))

            // Use retry logic for the Hello packet too
            retryWithBackoff(Config.GATT_RETRY_ATTEMPTS, Config.GATT_RETRY_COOLDOWN) {
                writeCharacteristic(packet.toBytes(), TransportDataType.CONTROL)
            }

        } catch (t: Throwable) {
            fail(ConnectionFailure.Io("Handshake Failed: ${t.message}"))
            // FIX: Don't swallow cancellation!
            if (t is CancellationException) throw t
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun subscribeToCharacteristic(gatt: BluetoothGatt, service: BluetoothGattService, charUUID: UUID) {
        val char = service.getCharacteristic(charUUID) ?: throw Exception("Characteristic not found for $charUUID")
        if (!gatt.setCharacteristicNotification(char, true)) throw Exception("setCharacteristicNotification failed for $charUUID")
        val descriptor = char.getDescriptor(GattServerHandler.CCCD_UUID) ?: throw Exception("CCCD not found for $charUUID")

        Log.d("GattClient", "Subscribing to $charUUID")
        suspendCancellableCoroutine { cont ->
            pendingAction.set(cont)
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            if (!success) {
                pendingAction.set(null)
                if (cont.isActive) cont.resumeWithException(Exception("writeDescriptor failed for $charUUID"))
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("GattClient", "Connection Error: $status")
                fail(ConnectionFailure.Io("Status $status"))
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("GattClient", "Connected. Requesting High Priority & Starting Handshake...")
                scope.launch { _clientEvents.emit(ClientEvent.Connected(targetDevice)) }
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

                // Start the linear handshake sequence in the queue
                operationQueue.enqueue(TransportDataType.CONTROL) {
                    performHandshakeSequence(gatt)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val address = TransportAddress.from(targetDevice.address)
                Log.d("GattClient", "Disconnected from $address")
                scope.launch { _clientEvents.emit(ClientEvent.Disconnected(targetDevice)) }
                close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("GattClient", "Service Discovery:  $status")
            resumePending(status == BluetoothGatt.GATT_SUCCESS, "Service Discovery")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d("GattClient", "Descriptor Write: ${descriptor.characteristic.uuid}, $status")
            resumePending(status == BluetoothGatt.GATT_SUCCESS, "Descriptor Write")
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            resumePending(status == BluetoothGatt.GATT_SUCCESS, "Characteristic Write")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingData(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleIncomingData(characteristic.uuid, characteristic.value)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < Config.BLE_MTU_MIN) {
                fail(ConnectionFailure.Io("MTU Negotiation Failed ($status) or too low ($mtu < ${Config.BLE_MTU_MIN})"))
                return
            }

            Log.d("GattClient", "MTU Negotiated: $mtu")
            currentMtu = mtu
            scope.launch { _clientEvents.emit(ClientEvent.Authenticated(targetDevice)) }
        }
    }

    private fun resumePending(success: Boolean, operationName: String) {
        val cont = pendingAction.getAndSet(null)
        if (cont?.isActive == true) {
            if (success) {
                cont.resume(Unit)
            } else {
                cont.resumeWithException(Exception("$operationName Failed"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleIncomingData(uuid: UUID, data: ByteArray) {
        if (uuid == Config.CHAR_DATA_UUID) {
            scope.launch { _clientEvents.emit(ClientEvent.MessageReceived(targetDevice, data, TransportDataType.AUDIO)) }
            return
        }

        // Control Packet Handling (Handshake)
        val packet = Packet.fromBytes(data, isControlChar = true)
        if (packet is Packet.Control) {
            when (packet.opCode) {
                Protocol.OP_AUTH_CHALLENGE -> {
                    if (packet is Packet.Control.Raw) {
                        val nonce = String(packet.data, Charsets.UTF_8)
                        solveChallenge(nonce)
                    }
                }
                Protocol.OP_AUTH_RESULT -> {
                    // CHECK FOR NACK
                    if (packet is Packet.Control.Raw && packet.data.isNotEmpty() && packet.data[0] == 1.toByte()) {
                        Log.d("GattClient", "Auth Success. Requesting MTU...")
                        operationQueue.enqueue(TransportDataType.CONTROL) {
                            try {
                                if (bluetoothGatt?.requestMtu(Config.BLE_MTU_TARGET) != true) {
                                    throw Exception("MTU Request Rejected")
                                }
                            } catch (t: Throwable) {
                                fail(ConnectionFailure.Io("MTU Request Failed: ${t.message}"))
                                // FIX: Don't swallow cancellation!
                                if (t is CancellationException) throw t
                            }
                        }
                    } else {
                        // EXPLICIT FAILURE
                        Log.e("GattClient", "Received AUTH_FAILED (NACK)")
                        fail(ConnectionFailure.AuthRejected("Wrong Access Code"))
                    }
                }
                else -> {
                    // Authenticated Traffic
                    scope.launch { _clientEvents.emit(ClientEvent.MessageReceived(targetDevice, data, TransportDataType.CONTROL)) }
                }
            }
        }
    }

    private fun solveChallenge(nonce: String) {
        val response = HandshakeLogic.generateResponse(accessCode, nonce, ownNodeId)
        val packet = Packet.Control.Raw(Protocol.OP_AUTH_RESPONSE, response)
        Log.d("GattClient", "Sending Challenge Response...")
        sendMessage(TransportDataType.CONTROL, packet.toBytes())
    }

    /**
     * Centralized error handler.
     * Emits the error and triggers a polite disconnect.
     */
    private fun fail(reason: ConnectionFailure) {
        Log.e("GattClient", "Error: $reason")
        scope.launch { _clientEvents.emit(ClientEvent.Error(targetDevice, reason)) }
        disconnect()
    }
}