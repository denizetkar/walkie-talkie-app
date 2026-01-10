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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class BleDriver(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    private val adapter = btManager.adapter

    private val serverHandler = GattServerHandler(context, scope)
    private val advertiserModule = BleAdvertiserModule(adapter, serverHandler)
    private val discoveryModule = BleDiscoveryModule(adapter, scope)

    // SSOT: Peers
    private val peerMutex = Mutex()
    private val _peers = MutableStateFlow<Map<UInt, TransportStrategy>>(emptyMap())
    val connectedPeers: StateFlow<Set<UInt>> = _peers
        .map { it.keys }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Tracks in-progress connection attempts to enforce idempotency.
    private val connectionJobs = mutableMapOf<UInt, Job>()

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

    // --- Public API ---

    fun startScanning() {
        ensureEventLoopActive()
        discoveryModule.start()
    }

    fun stopScanning() = discoveryModule.stop()

    fun startAdvertising(config: AdvertisingConfig) {
        ensureEventLoopActive()
        advertiserModule.start(config)
    }

    fun stopAdvertising() = advertiserModule.stop()

    suspend fun connectTo(address: String, nodeId: UInt) {
        ensureEventLoopActive() // Must be active to handle the resulting connection

        val job = peerMutex.withLock {
            // Deduplication: Already connected?
            if (_peers.value.containsKey(nodeId)) {
                Log.d("BleDriver", "Ignored Connect: Already connected to Node $nodeId")
                return@withLock null
            }

            // Idempotency: Join existing attempt or start new
            connectionJobs.getOrPut(nodeId) {
                scope.launch {
                    try {
                        attemptConnection(address, nodeId)
                    } finally {
                        // Cleanup job reference when done
                        peerMutex.withLock { connectionJobs.remove(nodeId) }
                    }
                }
            }
        }

        job?.join()
    }

    suspend fun broadcast(data: ByteArray, type: TransportDataType) {
        // Snapshot the current peers to avoid locking during I/O
        val currentPeers = _peers.value.values.toList()
        currentPeers.forEach { strategy -> strategy.send(data, type) }
    }

    fun disconnectNode(nodeId: UInt) {
        scope.launch {
            peerMutex.withLock {
                val strategy = _peers.value[nodeId] ?: return@withLock
                Log.i("BleDriver", "Force Disconnecting Node $nodeId")
                // REACTIVE: Just trigger the action. The event loop handles the state update.
                strategy.disconnect()
            }
        }
    }

    // --- Lifecycle ---

    suspend fun stop() {
        Log.d("BleDriver", "CMD: Stop (Soft)")

        // 1. Stop accepting NEW things
        stopScanning()
        stopAdvertising()

        // 2. Gracefully disconnect existing peers
        // This suspends until everyone is gone or timeout
        disconnectAll()

        // 3. Close the Server Socket (Hard Kill)
        // Safe to do now because disconnectAll() ensured the stack is clean
        serverHandler.stopServer()

        // 4. Stop processing events
        stopEventLoop()
    }

    fun destroy() {
        Log.d("BleDriver", "CMD: Destroy (Hard)")
        // 1. Stop Hardware
        stopScanning()
        stopAdvertising()
        serverHandler.stopServer()

        // 2. Cancel new connections
        scope.launch {
            peerMutex.withLock {
                connectionJobs.values.forEach { it.cancel() }
                connectionJobs.clear()

                // 3. HARD CLOSE all peers
                val currentPeers = _peers.value.values.toList()
                _peers.value = emptyMap()
                currentPeers.forEach { it.close() }
            }
        }
    }

    private suspend fun disconnectAll() {
        // 1. Trigger Disconnects
        peerMutex.withLock {
            connectionJobs.values.forEach { it.cancel() }
            connectionJobs.clear()

            val currentPeers = _peers.value.values.toList()
            if (currentPeers.isEmpty()) return

            Log.d("BleDriver", "Disconnecting ${currentPeers.size} peers...")
            currentPeers.forEach { it.disconnect() }
        }

        // 2. Reactive Wait
        // We wait for the stack to fire 'Disconnected' events, which updates _peers.
        withTimeoutOrNull(Config.DISCONNECT_ALL_TIMEOUT) {
            _peers.first { it.isEmpty() }
        }

        // 3. Force Clear if stuck
        peerMutex.withLock {
            if (_peers.value.isNotEmpty()) {
                Log.w("BleDriver", "Disconnect timeout. Force clearing ${_peers.value.size} peers.")
                _peers.value = emptyMap()
            } else {
                Log.d("BleDriver", "All peers disconnected gracefully.")
            }
        }
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
                        val nodeId = resolveNodeId(event.device.address)
                        if (nodeId != null) {
                            _events.emit(BleDriverEvent.DataReceived(nodeId, event.data, event.type))
                        } else {
                            Log.w("BleDriver", "Server: Received data from unknown device ${event.device.address}")
                        }
                    }
                    is ServerEvent.ClientDisconnected -> {
                        Log.i("BleDriver", "Server: Disconnected ${event.device.address}")
                        handleTransportDisconnect(event.device.address, null, TransportType.INCOMING)
                    }
                    is ServerEvent.Error -> {
                        Log.i("BleDriver", "Server: Error ${event.device.address}: ${event.message}")
                        handleTransportDisconnect(event.device.address, null, TransportType.INCOMING)
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

    // --- Internal Logic ---

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
        val device = adapter.getRemoteDevice(address)

        // FIX: Properly linked child scope.
        // If 'scope' (Driver) is cancelled, 'clientScope' is cancelled automatically.
        val clientScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)

        val client = GattClientHandler(context, clientScope, device, myNodeId, myAccessCode)

        // The bridge job that pipes Client events to Driver events.
        var collectionJob: Job? = null
        val connectionResult = CompletableDeferred<Boolean>()

        try {
            collectionJob = scope.launch {
                // Capture the strategy instance so we can identify it during disconnect
                var activeStrategy: ClientStrategy? = null

                client.clientEvents.collect { event ->
                    when (event) {
                        is ClientEvent.Authenticated -> {
                            Log.i("BleDriver", "Client: Authenticated with $nodeId")
                            // Pass the collectionJob so Strategy can cancel it on HARD close.
                            val strategy = ClientStrategy(client, this.coroutineContext[Job]!!)
                            activeStrategy = strategy
                            handleNewTransport(nodeId, strategy)
                            connectionResult.complete(true)
                        }
                        is ClientEvent.MessageReceived -> {
                            _events.emit(BleDriverEvent.DataReceived(nodeId, event.data, event.type))
                        }
                        is ClientEvent.Disconnected -> {
                            Log.w("BleDriver", "Client: Disconnected ${event.device.address}")
                            handleTransportDisconnect(event.device.address, activeStrategy, TransportType.OUTGOING)
                            connectionResult.complete(false)
                            this.coroutineContext.cancel()
                        }
                        is ClientEvent.Error -> {
                            Log.w("BleDriver", "Client: Error ${event.device.address}: ${event.message}")
                            handleTransportDisconnect(event.device.address, activeStrategy, TransportType.OUTGOING)
                            connectionResult.complete(false)
                            this.coroutineContext.cancel()
                        }
                        else -> {}
                    }
                }
            }

            client.connect()

            withTimeout(Config.PEER_CONNECT_TIMEOUT) {
                val success = connectionResult.await()
                if (!success) {
                    throw java.io.IOException("Connection failed logical check")
                }
            }
        } catch (e: Exception) {
            Log.w("BleDriver", "Connection failed: ${e.message}", e)
            client.close()
            collectionJob?.cancel()
        }
    }

    private fun resolveNodeId(address: String): UInt? {
        val normalized = address.uppercase()
        return _peers.value.entries.find { it.value.address == normalized }?.key
    }

    private suspend fun handleNewTransport(nodeId: UInt, newStrategy: TransportStrategy) {
        peerMutex.withLock {
            val existing = _peers.value[nodeId] ?: run {
                // New Peer
                _peers.update { it + (nodeId to newStrategy) }
                return@withLock
            }

            // Collision Resolution
            if (existing.address != newStrategy.address) {
                Log.w("BleDriver", "Node ID Collision ($nodeId). Old: ${existing.address}, New: ${newStrategy.address}. Replacing.")
                existing.disconnect()
                _peers.update { it + (nodeId to newStrategy) }
                return@withLock
            }

            // Same MAC / Reconnection case cannot happen on the server-side!
            if (existing.type == newStrategy.type) {
                Log.w("BleDriver", "Transport Retry ($nodeId). Replacing old ${existing.type} with new.")
                existing.disconnect()
                _peers.update { it + (nodeId to newStrategy) }
                return@withLock
            }

            // Tie-breaker: ClientID > ServerID wins
            val keepNew = if (newStrategy.type == TransportType.OUTGOING) {
                myNodeId > nodeId
            } else {
                nodeId > myNodeId
            }

            if (keepNew) {
                Log.i("BleDriver", "Collision: Keeping NEW ${newStrategy.type} (Node $nodeId)")
                existing.disconnect()
                _peers.update { it + (nodeId to newStrategy) }
            } else {
                Log.i("BleDriver", "Collision: Rejecting NEW ${newStrategy.type} (Node $nodeId)")
                newStrategy.disconnect()
            }
        }

        _events.emit(BleDriverEvent.PeerConnected(nodeId))
    }

    private suspend fun handleTransportDisconnect(
        address: String,
        sourceStrategy: TransportStrategy?,
        sourceType: TransportType
    ) {
        peerMutex.withLock {
            val normalized = address.uppercase()
            val (nodeId, currentStrategy) = _peers.value.entries.find { it.value.address == normalized } ?: return

            if (sourceType == TransportType.OUTGOING) {
                // CLIENT LOGIC: Strict Instance Check
                // If the disconnect comes from a Client, it MUST match the active strategy instance.
                // If it doesn't match, it's a stale disconnect from a previous attempt.
                if (sourceStrategy != null && currentStrategy !== sourceStrategy) {
                    Log.d("BleDriver", "Ignoring stale Client disconnect for $address")
                    return@withLock
                }
            } else {
                // SERVER LOGIC: Type Check
                // If the disconnect comes from the Server, we must ensure the active strategy
                // is actually a Server strategy. If it's a Client strategy, it means we
                // replaced the Server connection with a Client one, and we shouldn't kill it.
                if (currentStrategy.type == TransportType.OUTGOING) {
                    Log.d("BleDriver", "Ignoring Server disconnect for Client connection $address")
                    return@withLock
                }
            }

            // Only remove if the disconnected strategy is the ACTIVE one
            Log.i("BleDriver", "Transport disconnected for Node $nodeId")
            _peers.update { it - nodeId }
            scope.launch { _events.emit(BleDriverEvent.PeerDisconnected(nodeId)) }
        }
    }

    // --- Strategy Classes ---

    private class ClientStrategy(
        private val handler: GattClientHandler,
        private val collectionJob: Job
    ) : TransportStrategy {
        override val type = TransportType.OUTGOING
        override val address: String = handler.targetDevice.address.uppercase()

        override suspend fun send(data: ByteArray, type: TransportDataType) {
            handler.sendMessage(type, data)
        }

        override fun disconnect() {
            // Soft Disconnect: Trigger hardware, let events flow.
            // Do NOT cancel collectionJob here; let it die when Disconnected event arrives.
            Log.d("BleDriver", "ClientStrategy: Disconnecting $address")
            handler.disconnect()
        }

        override fun close() {
            // Hard Close: Kill everything immediately.
            handler.close()
            collectionJob.cancel()
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

        override fun close() {
            handler.disconnect(device)
        }
    }
}