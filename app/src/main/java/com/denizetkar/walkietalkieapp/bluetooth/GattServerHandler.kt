package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.protocol.HandshakeLogic
import com.denizetkar.walkietalkieapp.network.ServerEvent
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
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GattServerHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val accessCodeProvider: () -> String?
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null

    // Key: Device Address
    // Value: The Actor responsible for serializing writes to that specific device.
    private val clientQueues = ConcurrentHashMap<TransportAddress, BleOperationQueue>()

    // Sync Latch for Disconnect
    private val disconnectSignals = ConcurrentHashMap<TransportAddress, CompletableDeferred<Unit>>()
    // We only track these for internal logic (Auth/Queue), not for connection management.
    private val pendingChallenges = ConcurrentHashMap<TransportAddress, String>()
    // Bridge: Maps Device Address -> The Continuation waiting for 'onNotificationSent'
    private val pendingNotifications = ConcurrentHashMap<TransportAddress, CancellableContinuation<Unit>>()

    private val _serverEvents = MutableSharedFlow<ServerEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val serverEvents: SharedFlow<ServerEvent> = _serverEvents.asSharedFlow()

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = TransportAddress.from(device.address)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("GattServer", "New Connection: $address")

                // LIFECYCLE: Create Queue
                // We STRICTLY overwrite any existing queue. If a zombie queue existed, it is now
                // unreachable and will be GC'd (or timed out). We want the fresh state.
                clientQueues[address] = BleOperationQueue(scope, ioDispatcher)

                scope.launch { _serverEvents.emit(ServerEvent.ClientConnected(device)) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // RACE CONDITION FIX:
                // If the device is technically still CONNECTED in the Manager, this DISCONNECTED event
                // belongs to an old session (Ghost/Zombie Disconnect). We must ignore it.
                val actualState = bluetoothManager.getConnectionState(device, BluetoothProfile.GATT)
                if (actualState == BluetoothProfile.STATE_CONNECTED) {
                    Log.w("GattServer", "Ignored GHOST Disconnect for $address (Manager says Connected)")
                    return
                }

                Log.d("GattServer", "Disconnected: $address")

                // 1. SIGNAL THE WAITER
                disconnectSignals[address]?.complete(Unit)

                // 2. CLEANUP
                cleanupDeviceData(address)
                scope.launch { _serverEvents.emit(ServerEvent.ClientDisconnected(device)) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val address = TransportAddress.from(device.address)
            Log.d("GattServer", "MTU Changed for $address: $mtu")

            if (mtu < Config.BLE_MTU_MIN) {
                fail(device, ConnectionFailure.Io("MTU too low ($mtu < ${Config.BLE_MTU_MIN}) for $address"))
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            val address = TransportAddress.from(device.address)
            Log.d("GattServer", "Descriptor Write Request: ${descriptor.uuid} from $address")
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
                    // Audio Packet (Fast path)
                    scope.launch { _serverEvents.emit(ServerEvent.MessageReceived(device, value, TransportDataType.AUDIO)) }
                }
                Config.CHAR_CONTROL_UUID -> handleControlMessage(device, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val address = TransportAddress.from(device.address)
            val cont = pendingNotifications.remove(address)
            // CRITICAL: Check isActive. If the OperationQueue timed out, the continuation is cancelled.
            if (cont?.isActive == true) {
                if (status == BluetoothGatt.GATT_SUCCESS) cont.resume(Unit)
                else cont.resumeWithException(Exception("Status: $status"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (gattServer != null) return
        try {
            gattServer = bluetoothManager.openGattServer(context, gattCallback)
            if (gattServer == null) {
                Log.e("GattServer", "openGattServer returned null (Bluetooth off?)")
                return
            }
            setupService()
            Log.d("GattServer", "GATT Server Started")
        } catch (e: Exception) {
            Log.e("GattServer", "Failed to start GATT Server", e)
        }
    }

    private fun handleControlMessage(device: BluetoothDevice, data: ByteArray) {
        val packet = Packet.fromBytes(data, isControlChar = true)
        if (packet !is Packet.Control) return

        when (packet.opCode) {
            Protocol.OP_HELLO -> {
                val address = TransportAddress.from(device.address)
                Log.d("GattServer", "Received HELLO from $address. Sending Challenge.")
                sendChallenge(device)
            }
            Protocol.OP_AUTH_RESPONSE -> {
                if (packet is Packet.Control.Raw) handleAuthResponse(device, packet.data)
            }
            else -> {
                // Pass the FULL PACKET (data) up, not just the payload.
                scope.launch { _serverEvents.emit(ServerEvent.MessageReceived(device, data, TransportDataType.CONTROL)) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendChallenge(device: BluetoothDevice) {
        val nonce = UUID.randomUUID().toString().substring(0, 8)
        val address = TransportAddress.from(device.address)
        pendingChallenges[address] = nonce
        val packet = Packet.Control.Raw(Protocol.OP_AUTH_CHALLENGE, nonce.toByteArray(Charsets.UTF_8))
        sendTo(device, packet.toBytes(), TransportDataType.CONTROL)
    }

    @SuppressLint("MissingPermission")
    private fun handleAuthResponse(device: BluetoothDevice, payload: ByteArray) {
        val address = TransportAddress.from(device.address)
        val nonce = pendingChallenges.remove(address) ?: run {
            Log.w("GattServer", "Duplicate or invalid Auth Response from $address")
            return
        }
        val code = accessCodeProvider() ?: return
        val clientNodeId = HandshakeLogic.verifyResponse(payload, code, nonce)
        if (clientNodeId != null) {
            Log.i("GattServer", "Authenticated Node: $clientNodeId")
            val successPacket = Packet.Control.Raw(Protocol.OP_AUTH_RESULT, byteArrayOf(0x01))
            sendTo(device, successPacket.toBytes(), TransportDataType.CONTROL)
            scope.launch { _serverEvents.emit(ServerEvent.ClientAuthenticated(device, clientNodeId)) }
        } else {
            Log.w("GattServer", "Auth Failed. Sending NACK.")
            val failPacket = Packet.Control.Raw(Protocol.OP_AUTH_RESULT, byteArrayOf(0x00))
            sendTo(device, failPacket.toBytes(), TransportDataType.CONTROL)
            fail(device, ConnectionFailure.AuthRejected("Access code does not match $code"))
        }
    }

    @SuppressLint("MissingPermission")
    fun sendTo(device: BluetoothDevice, data: ByteArray, type: TransportDataType) {
        val server = gattServer ?: return
        val service = server.getService(Config.APP_SERVICE_UUID) ?: return
        val uuid = if (type == TransportDataType.AUDIO) Config.CHAR_DATA_UUID else Config.CHAR_CONTROL_UUID
        val char = service.getCharacteristic(uuid) ?: return

        // LOOKUP: Get the specific queue for this device
        val address = TransportAddress.from(device.address)
        val queue = clientQueues[address] ?: run {
            Log.w("GattServer", "Attempted to send to $address but no queue exists (Disconnected?)")
            return
        }

        queue.enqueue(type) {
            try {
                val maxAttempts = if (type == TransportDataType.CONTROL) Config.GATT_RETRY_ATTEMPTS else 1
                retryWithBackoff(maxAttempts, Config.GATT_RETRY_COOLDOWN) {
                    notify(server, device, char, data, type)
                }
            } catch (t: Throwable) {
                // Safe to ignore because the queue no longer throws TimeoutCancellationException
                if (t is CancellationException) throw t

                if (type == TransportDataType.CONTROL) {
                    fail(device, ConnectionFailure.Io("Notify Failed: ${t.message}"))
                } else {
                    Log.w("GattServer", "Dropped Audio Packet (Stack Busy)")
                }
            }
        }
    }

    /**
     * Encapsulates the "Suspend -> Resume" logic.
     */
    @SuppressLint("MissingPermission")
    private suspend fun notify(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        data: ByteArray,
        type: TransportDataType
    ) {
        // Determine Reliability:
        // CONTROL = Indication (ACK required) -> confirm = true
        // AUDIO = Notification (No ACK) -> confirm = false
        val confirm = (type == TransportDataType.CONTROL)
        val address = TransportAddress.from(device.address)

        if (confirm) {
            val result = withTimeoutOrNull(Config.BLE_OPERATION_TIMEOUT) {
                // SUSPENDING PATH (Control/Indication)
                // We must wait for onNotificationSent to ensure the stack is ready for the next one.
                suspendCancellableCoroutine { cont ->
                    pendingNotifications[address] = cont
                    val success = notifyCompat(server, device, char, data, confirm)
                    if (!success) {
                        pendingNotifications.remove(address)
                        if (cont.isActive) cont.resumeWithException(Exception("Stack Busy"))
                    }
                }
            }
            if (result == null) {
                pendingNotifications.remove(address)
                throw Exception("Notification Timeout") // To be caught by retryWithBackoff
            }
        } else {
            // FIRE-AND-FORGET PATH (Audio/Notification)
            // We do not wait for callbacks for audio to prevent blocking the queue on slow receivers.
            val success = notifyCompat(server, device, char, data, confirm)
            if (!success) throw Exception("Stack Busy")
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        data: ByteArray,
        confirm: Boolean
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, char, confirm, data) == BluetoothStatusCodes.SUCCESS
        } else @Suppress("DEPRECATION") {
            char.value = data
            server.notifyCharacteristicChanged(device, char, confirm)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupService() {
        val service = BluetoothGattService(Config.APP_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val dataChar = BluetoothGattCharacteristic(
            Config.CHAR_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        dataChar.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
        service.addCharacteristic(dataChar)

        val controlChar = BluetoothGattCharacteristic(
            Config.CHAR_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        controlChar.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
        service.addCharacteristic(controlChar)

        gattServer?.addService(service)
    }

    /**
     * Suspending Disconnect.
     * Used by the Driver to gracefully tear down the session.
     */
    @SuppressLint("MissingPermission")
    suspend fun disconnect(device: BluetoothDevice) {
        val address = TransportAddress.from(device.address)
        try {
            // Do not wait for a callback if they are already physically disconnected!
            val state = bluetoothManager.getConnectionState(device, BluetoothProfile.GATT)
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("GattServer", "Skipping disconnect wait for $address (Already Disconnected)")
                return
            }

            Log.d("GattServer", "Requesting disconnect for $address")
            val signal = CompletableDeferred<Unit>()
            disconnectSignals[address] = signal

            gattServer?.cancelConnection(device)
            if (bluetoothManager.adapter?.isEnabled != true) {
                Log.d("GattServer", "Skipping polite disconnect wait: Bluetooth is off")
                return
            }

            withTimeout(Config.PEER_DISCONNECT_TIMEOUT) {
                signal.await()
            }
        } catch (e: Exception) {
            Log.w("GattServer", "Disconnect Wait Timed Out/Failed for $address", e)
        } finally {
            cleanupDeviceData(address)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        Log.d("GattServer", "CMD: Destroy (Hard)")
        try {
            gattServer?.close()
        } catch (_: Exception) {}
        gattServer = null
        clientQueues.values.forEach { it.shutdown() }
        cleanupDeviceData(null)
    }

    private fun cleanupDeviceData(address: TransportAddress?) {
        if (address != null) {
            clientQueues.remove(address)?.shutdown()
            pendingChallenges.remove(address)
            pendingNotifications.remove(address)?.cancel()
            disconnectSignals.remove(address)
        } else {
            clientQueues.clear()
            pendingChallenges.clear()
            pendingNotifications.clear()
            disconnectSignals.clear()
        }
    }

    private fun fail(device: BluetoothDevice, reason: ConnectionFailure) {
        Log.e("GattServer", "Error: $reason")
        scope.launch {
            _serverEvents.emit(ServerEvent.Error(device, reason))
            disconnect(device)
        }
    }
}