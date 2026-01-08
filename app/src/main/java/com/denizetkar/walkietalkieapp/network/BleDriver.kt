package com.denizetkar.walkietalkieapp.network

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.bluetooth.BleAdvertiserModule
import com.denizetkar.walkietalkieapp.bluetooth.BleDiscoveryModule
import com.denizetkar.walkietalkieapp.bluetooth.ClientEvent
import com.denizetkar.walkietalkieapp.bluetooth.GattClientHandler
import com.denizetkar.walkietalkieapp.bluetooth.GattServerHandler
import com.denizetkar.walkietalkieapp.bluetooth.ServerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class BleDriver(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    private val adapter = btManager.adapter

    private val serverHandler = GattServerHandler(context, scope)
    private val advertiserModule = BleAdvertiserModule(adapter, serverHandler)
    private val discoveryModule = BleDiscoveryModule(adapter, scope)

    private val bleClientDispatcher = Dispatchers.IO.limitedParallelism(1)

    // The master registry. Immutable Map wrapped in a StateFlow for atomic updates.
    private val _peers = MutableStateFlow<Map<UInt, PeerConnection>>(emptyMap())
    val connectedPeers: StateFlow<Set<UInt>> = _peers
        .map { it.keys }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Tracks in-progress connection attempts to enforce idempotency.
    private val connectionJobs = ConcurrentHashMap<UInt, Job>()
    private val connectionMutex = Mutex()

    private var myAccessCode: String = ""
    private var myNodeId: UInt = 0u

    private val _events = MutableSharedFlow<BleDriverEvent>(
        extraBufferCapacity = Config.EVENT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    // --- Lifecycle Jobs ---
    private var serverEventJob: Job? = null
    private var discoveryEventJob: Job? = null

    // --- 1. Public Entry Points (Capabilities) ---

    fun startScanning() {
        ensureEventLoopActive()
        discoveryModule.start()
    }

    fun stopScanning() {
        discoveryModule.stop()
    }

    fun startAdvertising(config: AdvertisingConfig) {
        ensureEventLoopActive()
        advertiserModule.start(config)
    }

    fun stopAdvertising() {
        advertiserModule.stop()
    }

    suspend fun connectTo(address: String, nodeId: UInt) {
        ensureEventLoopActive() // Must be active to handle the resulting connection

        val normalizedAddress = address.uppercase()

        // Deduplication: Already connected?
        val existingPeer = _peers.value[nodeId]
        if (existingPeer != null && existingPeer.isActive()) {
            Log.d("BleDriver", "Ignored Connect: Already connected to Node $nodeId")
            return
        }

        // Idempotency: Join existing attempt or start new
        val job = connectionMutex.withLock {
            val existingJob = connectionJobs[nodeId]
            if (existingJob != null && existingJob.isActive) {
                Log.v("BleDriver", "Join existing connection attempt for $nodeId")
                return@withLock existingJob
            }

            val newJob = scope.launch { attemptConnection(normalizedAddress, nodeId) }
            connectionJobs[nodeId] = newJob
            newJob
        }
        job.join()
    }

    suspend fun broadcast(data: ByteArray, type: TransportDataType) {
        _peers.value.values.forEach { peer -> peer.send(data, type) }
    }

    fun disconnectNode(nodeId: UInt) {
        val peer = _peers.value[nodeId] ?: return
        Log.i("BleDriver", "Force Disconnecting Node $nodeId")
        _peers.update { it - nodeId }
        peer.disconnect()
        scope.launch { _events.emit(BleDriverEvent.PeerDisconnected(nodeId)) }
    }

    // --- 2. Lifecycle Management ---

    suspend fun stop() {
        Log.d("BleDriver", "CMD: Stop (Soft)")

        // A. Cut the nerves (Stop processing inputs)
        stopEventLoop()

        // B. Stop the hardware
        stopScanning()
        stopAdvertising()

        // C. Clear the state (Safe now because events are cut)
        disconnectAll()
    }

    fun destroy() {
        Log.d("BleDriver", "CMD: Destroy (Hard)")
        runBlocking { withTimeout(Config.DESTROY_TIMEOUT) { stop() } }
        serverHandler.stopServer()
    }

    /**
     * Resurrects the event listeners if they were killed by stop().
     * Called automatically by any method that requires the driver to be functional.
     */
    private fun ensureEventLoopActive() {
        if (serverEventJob?.isActive == true && discoveryEventJob?.isActive == true) return

        // Cancel any half-dead jobs to be safe
        stopEventLoop()

        // 1. Server Events (Incoming connections/data)
        serverEventJob = scope.launch {
            serverHandler.serverEvents.collect { event ->
                when (event) {
                    is ServerEvent.ClientAuthenticated -> {
                        Log.i("BleDriver", "Server: Peer Authenticated ${event.nodeId} (${event.device.address})")
                        val strategy = ServerStrategy(event.device, serverHandler)
                        handleNewTransport(event.nodeId, strategy)
                    }
                    is ServerEvent.MessageReceived -> {
                        val nodeId = resolveNode(event.device.address)?.nodeId
                        if (nodeId != null) {
                            _events.emit(BleDriverEvent.DataReceived(nodeId, event.data, event.type))
                        } else {
                            Log.w("BleDriver", "Server: Received data from unknown device ${event.device.address}")
                        }
                    }
                    is ServerEvent.ClientDisconnected -> {
                        Log.i("BleDriver", "Server: Disconnected ${event.device.address}")
                        handleTransportDisconnect(event.device.address)
                    }
                    is ServerEvent.Error -> {
                        Log.i("BleDriver", "Server: Error ${event.device.address}: ${event.message}")
                        handleTransportDisconnect(event.device.address)
                    }
                    else -> {}
                }
            }
        }

        // 2. Discovery Events
        discoveryEventJob = scope.launch {
            discoveryModule.events.collect { node ->
                _events.emit(BleDriverEvent.PeerDiscovered(node))
            }
        }
    }

    private fun stopEventLoop() {
        serverEventJob?.cancel()
        discoveryEventJob?.cancel()
        serverEventJob = null
        discoveryEventJob = null
    }

    // --- 3. Internal Logic ---

    fun validateCapabilities(): Result<Unit> {
        if (adapter == null) return Result.failure(IllegalStateException("Bluetooth is not supported."))
        if (!adapter.isEnabled) return Result.failure(IllegalStateException("Bluetooth is turned off."))
        if (adapter.bluetoothLeAdvertiser == null) return Result.failure(IllegalStateException("BLE Advertising not supported."))
        return Result.success(Unit)
    }

    fun setCredentials(accessCode: String, ownNodeId: UInt) {
        this.myAccessCode = accessCode
        this.myNodeId = ownNodeId
        serverHandler.currentAccessCode = accessCode
    }

    private suspend fun attemptConnection(address: String, nodeId: UInt) {
        Log.d("BleDriver", "CMD: Connect to $address (Node $nodeId)")

        // Create a Child Scope specifically for this Client's lifecycle
        // SupervisorJob ensures that if a child coroutine fails, the scope doesn't crash the parent
        val clientScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        val device = adapter.getRemoteDevice(address)

        val client = GattClientHandler(
            context,
            clientScope,
            device,
            myNodeId,
            myAccessCode,
            bleClientDispatcher
        )

        val connectionResult = CompletableDeferred<Boolean>()

        // We launch the event collector in the CLIENT scope.
        // This ensures it dies automatically when clientScope.cancel() is called.
        clientScope.launch {
            client.clientEvents.collect { event ->
                when (event) {
                    is ClientEvent.Authenticated -> {
                        Log.i("BleDriver", "Client: Authenticated with $nodeId")
                        val strategy = ClientStrategy(client, clientScope)
                        handleNewTransport(nodeId, strategy)
                        if (connectionResult.isActive) connectionResult.complete(true)
                    }
                    is ClientEvent.MessageReceived -> {
                        _events.emit(BleDriverEvent.DataReceived(nodeId, event.data, event.type))
                    }
                    is ClientEvent.Disconnected -> {
                        Log.w("BleDriver", "Client: Disconnected ${event.device.address}")
                        handleTransportDisconnect(event.device.address)
                        if (connectionResult.isActive) connectionResult.complete(false)
                    }
                    is ClientEvent.Error -> {
                        Log.w("BleDriver", "Client: Error ${event.device.address}: ${event.message}")
                        handleTransportDisconnect(event.device.address)
                        if (connectionResult.isActive) connectionResult.complete(false)
                    }
                    else -> {}
                }
            }
        }

        client.connect()

        try {
            withTimeout(Config.PEER_CONNECT_TIMEOUT) {
                val success = connectionResult.await()
                if (!success) {
                    client.disconnect()
                    clientScope.cancel()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("BleDriver", "Connection Timed Out for $address", e)
            client.disconnect()
            clientScope.cancel()
        } finally {
            connectionMutex.withLock { connectionJobs.remove(nodeId) }
        }
    }

    private fun resolveNode(address: String): PeerConnection? {
        val normalized = address.uppercase()
        return _peers.value.values.find { it.address == normalized }
    }

    private suspend fun handleNewTransport(nodeId: UInt, newStrategy: TransportStrategy) {
        // Cancel any pending outgoing attempts for this node, as we now have a candidate
        cancelPendingConnection(nodeId)

        val newPeer = PeerConnection(nodeId)
        // Optimistic update or get existing
        _peers.update { current ->
            if (current.containsKey(nodeId)) current else current + (nodeId to newPeer)
        }
        val peer = _peers.value[nodeId]!!

        synchronized(peer) {
            val current = peer.transport

            if (current != null) {
                // COLLISION DETECTED: We already have a link.

                // 1. Check for Stale State (MAC Rotation)
                // If the Node ID is the same, but MAC is different, the old one is likely a "Zombie".
                // We overwrite it with the fresh, authenticated connection.
                if (current.address != newStrategy.address) {
                    Log.w("BleDriver", "Node ID Collision ($nodeId). Old: ${current.address}, New: ${newStrategy.address}. Replacing.")
                    peer.setStrategy(newStrategy)
                    return
                }

                // 2. Check for Retry (Same Type, Same MAC)
                // If we are replacing a Client with a Client on the same MAC, it's a retry. Always accept.
                if (current.type == newStrategy.type) {
                    Log.w("BleDriver", "Transport Retry ($nodeId). Replacing old ${current.type} with new.")
                    peer.setStrategy(newStrategy)
                    return
                }

                // Tie-breaker: ClientID > ServerID wins
                val keepNew = if (newStrategy.type == TransportType.OUTGOING) {
                    myNodeId > nodeId
                } else {
                    nodeId > myNodeId
                }

                if (keepNew) {
                    Log.i("BleDriver", "Collision: Keeping NEW ${newStrategy.type} (Node $nodeId)")
                    peer.setStrategy(newStrategy)
                } else {
                    Log.i("BleDriver", "Collision: Rejecting NEW ${newStrategy.type} (Node $nodeId)")
                    newStrategy.disconnect()
                    return
                }
            } else {
                Log.d("BleDriver", "Setting ${newStrategy.type} Transport for Node $nodeId")
                peer.setStrategy(newStrategy)
            }
        }

        _events.emit(BleDriverEvent.PeerConnected(nodeId))
    }

    private fun handleTransportDisconnect(address: String) {
        val normalized = address.uppercase()
        val peer = resolveNode(normalized) ?: return

        // Only remove if the disconnected address matches the ACTIVE transport
        // (Prevents race conditions where we replaced the transport but the old one sends a disconnect event)
        synchronized(peer) {
            if (peer.address == normalized) {
                Log.i("BleDriver", "Transport disconnected for Node ${peer.nodeId}")
                peer.clearStrategy()
                _peers.update { it - peer.nodeId }
                scope.launch { _events.emit(BleDriverEvent.PeerDisconnected(peer.nodeId)) }
            }
        }
    }

    private suspend fun cancelPendingConnection(nodeId: UInt) {
        connectionMutex.withLock {
            connectionJobs[nodeId]?.cancel()
            connectionJobs.remove(nodeId)
        }
    }

    suspend fun disconnectAll() {
        serverHandler.disconnectAll()
        val currentPeers = _peers.value.values.toList()
        _peers.value = emptyMap()
        currentPeers.forEach { it.disconnect() }
        connectionMutex.withLock {
            connectionJobs.values.forEach { it.cancel() }
            connectionJobs.clear()
        }
    }

    // --- Strategy Classes ---

    private class ClientStrategy(
        private val handler: GattClientHandler,
        private val clientScope: CoroutineScope
    ) : TransportStrategy {
        override val type = TransportType.OUTGOING
        override val address: String = handler.targetDevice.address.uppercase()

        override suspend fun send(data: ByteArray, type: TransportDataType) {
            handler.sendMessage(type, data)
        }

        override fun disconnect() {
            Log.d("BleDriver", "ClientStrategy: Disconnecting $address")
            // 1. Cleanup GATT
            handler.disconnect()
            // 2. Kill all background jobs (timeouts, queues) for this client
            clientScope.cancel()
        }
    }

    private class ServerStrategy(
        private val device: BluetoothDevice,
        private val handler: GattServerHandler
    ) : TransportStrategy {
        override val type = TransportType.INCOMING
        override val address: String = device.address.uppercase()

        override suspend fun send(data: ByteArray, type: TransportDataType) {
            handler.sendTo(device, data, type)
        }

        override fun disconnect() {
            Log.d("BleDriver", "ServerStrategy: Disconnecting $address")
            handler.disconnect(device)
        }
    }
}