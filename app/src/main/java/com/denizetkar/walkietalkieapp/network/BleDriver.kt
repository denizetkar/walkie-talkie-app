package com.denizetkar.walkietalkieapp.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.bluetooth.*
import com.denizetkar.walkietalkieapp.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The "Muscle" of the Network Layer.
 *
 * Responsibilities:
 * 1. Managing Bluetooth hardware (Scan, Advertise, GATT Server).
 * 2. Maintaining the list of active peers (Connections).
 * 3. Executing "Effects" from the Core.
 * 4. Emitting "Actions" to the Core via the standardized dispatch lane.
 */
class BleDriver(
    private val context: Context,
    // The scope where long-running connection jobs live
    private val scope: CoroutineScope,
    // INJECTION POINT: Allows tests to swap IO for TestDispatcher
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // FACTORY INJECTION: Allows tests to supply a mock Handler
    private val clientHandlerFactory: (Context, CoroutineScope, BluetoothDevice, PeerId, String, CoroutineDispatcher) -> GattClientHandler = ::GattClientHandler,
    // STANDARD DIRECT LANE: Fast path for high-frequency events (Audio/Packets)
    private val dispatch: (Action) -> Unit
) {
    // --- State ---
    // Atomic wrappers for thread-safe visibility to inner classes/modules
    private val currentAccessCode = AtomicReference<String?>(null)

    // IDENTITY: We maintain an eventually-consistent ID for Incoming connections.
    // Outgoing connections will use the Explicit ID passed in the Effect.
    private val currentNodeId = AtomicInteger(0)

    // --- Sub-Modules ---
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    private val adapter = btManager.adapter
    private val serverHandler = GattServerHandler(context, scope, ioDispatcher) { currentAccessCode.get() }
    private val advertiserModule = BleAdvertiserModule(adapter, serverHandler)
    private val discoveryModule = BleDiscoveryModule(adapter, scope)

    // --- Registry ---
    // SSOT for active connections. Updates are atomic via Mutex + StateFlow.
    private val peerMutex = Mutex()
    private val _peers = MutableStateFlow(PeerRegistry())

    // NEW: Bluetooth State Monitor
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.w("BleDriver", "Bluetooth Disabled by System")
                        dispatch(Action.BluetoothStateChanged(false))
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.i("BleDriver", "Bluetooth Enabled by System")
                        dispatch(Action.BluetoothStateChanged(true))
                    }
                }
            }
        }
    }

    init {
        dispatch(Action.BluetoothStateChanged(adapter?.isEnabled == true))

        context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        scope.launch {
            discoveryModule.events.collect { event ->
                when (event) {
                    is DiscoveryEvent.NodeFound -> {
                        dispatch(Action.AdvertisementSeen(
                            DiscoveredGroup(
                                id = event.node.id,
                                name = event.node.name,
                                rssi = event.node.rssi,
                                netId = event.node.networkId,
                                nodeId = event.node.nodeId
                            )
                        ))
                    }
                    is DiscoveryEvent.ScanFailed -> {
                        dispatch(Action.ScanFailed("Async scan failure: ${event.errorCode}"))
                    }
                }
            }
        }

        scope.launch {
            serverHandler.serverEvents.collect { event ->
                when (event) {
                    is ServerEvent.ClientConnected -> {
                        val address = TransportAddress.from(event.device.address)
                        // If the address is already in our registry, it's an Outgoing connection
                        // (or a collision handoff). The OS is just mirroring the link state to the Server.
                        if (_peers.value.addressIndex.containsKey(address)) {
                            Log.d("BleDriver", "Server: Ignored ClientConnected for $address (Already managed)")
                            return@collect
                        }

                        Log.d("BleDriver", "Server: Unmanaged connection from $address. Starting security fuse.")
                        scope.launch {
                            delay(Config.BLE_CONNECT_TIMEOUT)
                            if (!_peers.value.addressIndex.containsKey(address)) {
                                Log.w("BleDriver", "Fuse Blown: Disconnecting unauthenticated zombie $address")
                                serverHandler.disconnect(event.device)
                            }
                        }
                    }
                    is ServerEvent.ClientAuthenticated -> {
                        val address = TransportAddress.from(event.device.address)
                        Log.i("BleDriver", "Server: Peer Authenticated ${event.nodeId} ($address)")
                        // INCOMING: Use our current eventually-consistent ID
                        val myNodeId = currentNodeId.get().toUInt()
                        // Launch in scope to avoid stalling the SharedFlow event collector
                        // if we need to call the suspending `serverHandler.disconnect()`
                        scope.launch {
                            val accepted = launchPeerJob(event.nodeId, myNodeId, TransportType.INCOMING, address) { channel, isCollisionHandoff ->
                                runServerConnection(event, channel, isCollisionHandoff)
                            }
                            if (!accepted) {
                                Log.w("BleDriver", "Server: Rejected incoming connection from $address due to collision. Disconnecting.")
                                serverHandler.disconnect(event.device)
                            }
                        }
                    }
                    is ServerEvent.MessageReceived -> {
                        val address = TransportAddress.from(event.device.address)
                        val nodeId = resolvePeerId(address)
                        if (nodeId != null) {
                            val isControl = (event.type == TransportDataType.CONTROL)
                            dispatch(Action.PacketReceived(event.data, nodeId, isControl))
                        } else {
                            Log.w("BleDriver", "Server: Received data from unknown device $address")
                        }
                    }
                    is ServerEvent.ClientDisconnected -> {
                        val address = TransportAddress.from(event.device.address)
                        Log.i("BleDriver", "Server: Disconnected $address")
                        // REFACTOR: Instead of removing from map directly, we find the job and cancel it.
                        // The Job's 'finally' block will handle the map removal and dispatching PeerDisconnected.
                        val nodeId = resolvePeerId(address)
                        if (nodeId != null) {
                            Log.i("BleDriver", "Server Disconnect for Node $nodeId")
                            peerMutex.withLock {
                                _peers.value.sessions[nodeId]?.job?.cancel()
                            }
                        }
                    }
                    is ServerEvent.Error -> {
                        val address = TransportAddress.from(event.device.address)
                        Log.e("BleDriver", "Server Error from $address: ${event.reason}")
                    }
                }
            }
        }
    }

    /**
     * REACTIVE BINDING
     * Connects the Core logic to this Driver.
     */
    fun bind(state: StateFlow<AppState>, effects: Flow<Effect>) {
        scope.launch {
            var lastConfig: DriverConfig? = null
            var wasSessionNull = true

            state.collect { appState ->
                // Synchronous updates
                currentNodeId.set(appState.myself.toInt())
                currentAccessCode.set(appState.session?.accessCode)

                val isSessionNull = appState.session == null
                if (isSessionNull && !wasSessionNull) {
                    closeAllConnections()
                }
                wasSessionNull = isSessionNull

                val newConfig = deriveDriverConfig(appState)
                if (newConfig != lastConfig) {
                    applyDriverConfig(newConfig)
                    lastConfig = newConfig
                }
            }
        }

        // 5. Effect Consumer
        scope.launch {
            effects.collect { effect ->
                when (effect) {
                    is Effect.ConnectTo -> handleConnectRequest(effect.targetId, effect.targetNodeId, effect.originNodeId, effect.accessCode)
                    is Effect.Disconnect -> handleDisconnectRequest(effect.peerId)
                    is Effect.Transmit -> handleTransmit(effect.data, effect.isControl, effect.excludedSource)
                    else -> {} // Other effects (like Toast) are handled by UI layer
                }
            }
        }
    }

    /**
     * Hard cleanup. Must be called when Service is destroyed.
     */
    fun close() {
        Log.d("BleDriver", "CMD: Destroy (Hard)")
        try {
            context.unregisterReceiver(btReceiver)
        } catch (_: Exception) {}
        advertiserModule.stop()
        discoveryModule.stop()
        serverHandler.stopServer()
        closeAllConnections()
    }

    // --- Configuration Logic ---

    private fun deriveDriverConfig(state: AppState): DriverConfig {
        val session = state.session

        // LOGIC FIX: Premature Advertising Prevention
        // 1. If we created the group (!isJoinAttempt), we advertise immediately.
        // 2. If we are joining, we MUST NOT advertise until we have at least 1 peer (Relay Node).
        val shouldAdvertise = if (session != null) {
            if (!session.isJoinAttempt) true
            else state.connectedPeers.isNotEmpty()
        } else {
            false
        }

        // If we are in a session, we ALWAYS scan to find new peers,
        // merge islands, and recover lost connections (Continuous/Lazy Scanning).
        val isScanning = state.isBrowsing || session != null

        return DriverConfig(
            isBluetoothEnabled = state.isBluetoothEnabled,
            isAdvertising = shouldAdvertise,
            isScanning = isScanning,
            groupName = session?.groupName ?: "",
            ownNodeId = state.myself,
            netId = state.network.rootId,
            hops = state.network.hops,
            isFull = state.connectedPeers.size >= Config.MAX_PEERS
        )
    }

    private fun applyDriverConfig(config: DriverConfig) {
        // If Bluetooth is disabled, halt hardware operations but don't wipe internal state
        if (!config.isBluetoothEnabled) {
            advertiserModule.stop()
            discoveryModule.stop()
            serverHandler.stopServer() // Ensure GATT server is reset on hardware toggle
            closeAllConnections()
            return
        }

        if (config.isAdvertising) {
            val advertisingConfig = AdvertisingConfig(
                groupName = config.groupName,
                ownNodeId = config.ownNodeId,
                networkId = config.netId,
                hopsToRoot = config.hops,
                isAvailable = !config.isFull
            )
            val success = advertiserModule.start(advertisingConfig)
            if (!success) {
                Log.e("BleDriver", "CRITICAL: Advertising Requested but Failed.")
                dispatch(Action.JoinGroupFailed("Bluetooth Radio Unavailable or Error"))
            }
        } else {
            advertiserModule.stop()
        }

        if (config.isScanning) {
            val success = discoveryModule.start()
            if (!success) {
                Log.e("BleDriver", "CRITICAL: Scanning Requested but Failed.")
                dispatch(Action.ScanFailed("Bluetooth Scanner Unavailable"))
            }
        } else {
            discoveryModule.stop()
        }
    }

    // --- Connection Lifecycle (The "Job is the Peer" Logic) ---

    private suspend fun handleConnectRequest(rawAddress: String, targetNodeId: PeerId, originNodeId: PeerId, accessCode: String) {
        if (adapter?.isEnabled != true) {
            Log.w("BleDriver", "Skipping ConnectTo: Bluetooth is disabled")
            return
        }

        val address = TransportAddress.from(rawAddress)
        Log.d("BleDriver", "CMD: Connect to $address (Node $targetNodeId) as $originNodeId")
        launchPeerJob(targetNodeId, originNodeId, TransportType.OUTGOING, address) { channel, isCollisionHandoff ->
            runClientConnection(rawAddress, targetNodeId, originNodeId, accessCode, channel, isCollisionHandoff)
        }
    }

    private suspend fun launchPeerJob(
        targetNodeId: PeerId,
        myNodeId: PeerId,
        type: TransportType,
        address: TransportAddress,
        block: suspend (ReceiveChannel<OutgoingPacket>, AtomicBoolean) -> Unit,
    ): Boolean {
        peerMutex.withLock {
            val existing = _peers.value.sessions[targetNodeId]
            if (existing != null) {
                // If we are already connecting/connected to this node as a CLIENT (Outgoing),
                // and the new request is also OUTGOING, just ignore it.
                // The current job is likely still performing the handshake.
                if (existing.type == TransportType.OUTGOING && type == TransportType.OUTGOING) {
                    Log.d("BleDriver", "Ignored duplicate connection request for Node $targetNodeId")
                    return false
                }

                // COLLISION: Two connections to same Node ID.
                // Tie-breaker: Higher ID wins the right to keep THEIR initiated connection.
                val keepNew = if (type == TransportType.OUTGOING) myNodeId > targetNodeId else targetNodeId > myNodeId

                if (keepNew) {
                    Log.i("BleDriver", "Collision $targetNodeId: Replacing OLD ${existing.type} with NEW $type")
                    existing.isCollisionHandoff.set(true)
                    existing.job.cancel() // Cancel old job. Its finally block will run asynchronously.
                } else {
                    Log.i("BleDriver", "Collision $targetNodeId: Rejecting NEW $type, keeping OLD ${existing.type}")
                    return false // Abort new connection
                }
            }

            // We use a unique ID for this connection attempt to prevent "Innocent Kill" bugs
            val connectionId = UUID.randomUUID()
            val channel = Channel<OutgoingPacket>(Channel.UNLIMITED)
            val isCollisionHandoff = AtomicBoolean(false)
            // To prevent "Dead-on-Arrival" races
            val readySignal = CompletableDeferred<Unit>()
            val job = scope.launch(ioDispatcher) {
                readySignal.await() // Wait for registry insertion to finish safely
                try {
                    block(channel, isCollisionHandoff)
                } catch (_: CancellationException) {
                    Log.d("BleDriver", "Peer Job $targetNodeId cancelled")
                } catch (e: Exception) {
                    Log.e("BleDriver", "Peer Job $targetNodeId failed: ${e.message}", e)
                } finally {
                    // AUTOMATIC CLEANUP
                    // We must lock to ensure we don't remove a NEW connection that replaced us
                    cleanupPeer(targetNodeId, connectionId)
                }
            }
            // ATOMIC UPDATE: Session and Address Index update together
            val session = PeerSession(job, channel, type, address, connectionId, isCollisionHandoff)
            _peers.update { it.put(targetNodeId, session) }
            readySignal.complete(Unit) // Unleash the job
            return true
        }
    }

    private suspend fun cleanupPeer(nodeId: PeerId, connectionId: UUID) = withContext(NonCancellable) {
        peerMutex.withLock {
            // Identity Check: Only remove if the registry still points to THIS exact connection attempt
            val currentSession = _peers.value.sessions[nodeId]
            if (currentSession?.connectionId == connectionId) {
                Log.i("BleDriver", "Transport disconnected for Node $nodeId")
                _peers.update { it.remove(nodeId) }
                dispatch(Action.PeerDisconnected(nodeId))
            }
        }
    }

    // --- Client Logic ---

    private suspend fun runClientConnection(
        address: String,
        targetNodeId: PeerId,
        myNodeId: PeerId,
        accessCode: String,
        outgoing: ReceiveChannel<OutgoingPacket>,
        isCollisionHandoff: AtomicBoolean,
    ) {
        val device = adapter.getRemoteDevice(address)

        // 1. DETACHED SCOPE SETUP
        // We create a scope that is a child of the Driver (scope), but NOT a child of this PeerJob.
        // This ensures that when PeerJob is cancelled (User leaves), this scope stays alive
        // long enough to process the Disconnected event for the "Polite Disconnect".
        // SupervisorJob ensures a crash in the client doesn't crash the Driver.
        val clientScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val client = clientHandlerFactory(context, clientScope, device, myNodeId, accessCode, ioDispatcher)

        // 2. DISCONNECT SIGNAL
        // A latch that opens when the stack confirms disconnection.
        // Using CompletableDeferred handles the race condition where the event arrives
        // before we start waiting for it.
        val disconnectSignal = CompletableDeferred<Unit>()

        // Capture current job to allow the event collector to kill the loop
        val peerJob = currentCoroutineContext().job

        try {
            val handshakeComplete = CompletableDeferred<Unit>()

            // 3. EVENT COLLECTOR (The "Ear")
            clientScope.launch {
                client.clientEvents.collect { event ->
                    when (event) {
                        is ClientEvent.Authenticated -> {
                            Log.i("BleDriver", "Client: Authenticated with $targetNodeId")
                            handshakeComplete.complete(Unit)
                        }
                        is ClientEvent.MessageReceived -> {
                            val isControl = (event.type == TransportDataType.CONTROL)
                            dispatch(Action.PacketReceived(event.data, targetNodeId, isControl))
                        }
                        is ClientEvent.Disconnected -> {
                            val address = TransportAddress.from(event.device.address)
                            Log.w("BleDriver", "Client: Disconnect $address")
                            // Mark the signal so finally block knows
                            disconnectSignal.complete(Unit)
                            // Kill the main loop immediately
                            peerJob.cancel(CancellationException("Remote Disconnect"))
                        }
                        is ClientEvent.Error -> {
                            val address = TransportAddress.from(event.device.address)
                            Log.e("BleDriver", "Client: Error $address: ${event.reason}")
                            // Treat error as disconnect to avoid hanging
                            disconnectSignal.complete(Unit)
                            // Kill the main loop immediately
                            peerJob.cancel(CancellationException("Client Error: ${event.reason}"))
                            if (event.reason is ConnectionFailure.AuthRejected) {
                                dispatch(Action.JoinGroupFailed("Access Code Rejected"))
                            }
                        }
                        else -> {}
                    }
                }
            }

            // 4. CONNECT & HANDSHAKE
            // Handshake is now wrapped in a timeout inside GattClientHandler,
            // but we also keep a high-level timeout for the whole connection process here.
            client.connect()
            withTimeout(Config.BLE_CONNECT_TIMEOUT) { handshakeComplete.await() }
            dispatch(Action.PeerConnected(targetNodeId))

            // 5. MAIN LOOP (The "Mouth")
            // Pump data from the channel to the client.
            for (packet in outgoing) {
                val type = if (packet.isControl) TransportDataType.CONTROL else TransportDataType.AUDIO
                client.sendMessage(type, packet.data)
            }

        } finally {
            // 6. TEARDOWN LOGIC (The "Brain")
            // We use NonCancellable to ensure this runs even if the job was cancelled.
            withContext(NonCancellable) {
                // DECISION: Do we wait for a polite disconnect?
                // YES if:
                // 1. The Driver is still alive (System didn't kill us and adapter is enabled).
                // 2. The Peer didn't disconnect from us (Signal is NOT completed).
                // 3. This is not a collision handoff
                val shouldWait = scope.isActive && adapter?.isEnabled == true && !disconnectSignal.isCompleted && !isCollisionHandoff.get()

                if (shouldWait) {
                    try {
                        Log.d("BleDriver", "Initiating Polite Disconnect for $address")
                        client.disconnect()
                        // We must not wait forever for the stack to callback.
                        withTimeout(Config.PEER_DISCONNECT_TIMEOUT) {
                            disconnectSignal.await()
                        }
                    } catch (e: Exception) {
                        Log.w("BleDriver", "Polite disconnect failed or timed out: ${e.message}")
                    }
                } else if (isCollisionHandoff.get()) {
                    Log.d("BleDriver", "Skipping Polite Disconnect for $address due to Collision Handoff")
                }

                // Always Hard Close to release resources.
                client.close()
            }
        }
    }

    // --- Server Logic ---

    private suspend fun runServerConnection(
        event: ServerEvent.ClientAuthenticated,
        outgoing: ReceiveChannel<OutgoingPacket>,
        isCollisionHandoff: AtomicBoolean,
    ) {
        val device = event.device
        try {
            dispatch(Action.PeerConnected(event.nodeId))

            for (packet in outgoing) {
                val type = if (packet.isControl) TransportDataType.CONTROL else TransportDataType.AUDIO
                serverHandler.sendTo(device, packet.data, type)
            }
        } finally {
            // Ensure disconnect command can be sent even if Job is cancelled
            withContext(NonCancellable) {
                if (!isCollisionHandoff.get()) {
                    val address = TransportAddress.from(device.address)
                    Log.d("BleDriver", "ServerStrategy: Disconnecting $address")
                    serverHandler.disconnect(device)
                } else {
                    Log.d("BleDriver", "ServerStrategy: Skipping disconnect for ${device.address} due to Collision Handoff")
                }
            }
        }
    }

    // --- Helpers ---

    private fun handleDisconnectRequest(peerId: PeerId) {
        Log.i("BleDriver", "Force Disconnecting Node $peerId")
        _peers.value.sessions[peerId]?.job?.cancel()
    }

    private fun closeAllConnections() {
        val peerSessions = _peers.value.sessions
        if (peerSessions.isNotEmpty()) {
            Log.d("BleDriver", "Disconnecting ${peerSessions.size} peers...")
        }
        peerSessions.values.forEach { session -> session.job.cancel() }
    }

    private fun handleTransmit(data: ByteArray, isControl: Boolean, excludedSource: PeerId?) {
        val packet = OutgoingPacket(data, isControl)
        // Broadcast to all active peers
        _peers.value.sessions.forEach { (peerId, session) ->
            // FIX: Split Horizon Optimization.
            // If excludedSource is set, skip the peer that sent us this packet.
            if (excludedSource == null || peerId != excludedSource) {
                session.channel.trySend(packet)
            }
        }
    }

    private fun resolvePeerId(address: TransportAddress): PeerId? {
        return _peers.value.addressIndex[address]
    }
}