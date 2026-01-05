package com.denizetkar.walkietalkieapp.network

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.HandlerThread
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BleDriver(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    private val adapter = btManager.adapter

    private val serverHandler = GattServerHandler(context, scope)
    private val advertiserModule = BleAdvertiserModule(adapter, serverHandler)
    private val discoveryModule = BleDiscoveryModule(adapter, scope)

    private val clientWorkerThread = HandlerThread("BleClientWorker").apply { start() }

    // The master registry. Immutable Map wrapped in a StateFlow for atomic updates.
    private val _peers = MutableStateFlow<Map<Int, PeerConnection>>(emptyMap())

    // Public API: Automatically derived from _peers.
    // No manual sync needed.
    val connectedPeers: StateFlow<Set<Int>> = _peers
        .map { it.keys }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private var myAccessCode: String = ""
    private var myNodeId: Int = 0

    private val _events = MutableSharedFlow<BleDriverEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    init {
        // 1. Listen to Server Events (Incoming Connections)
        scope.launch {
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
                        Log.i("BleDriver", "Server: Client Disconnected ${event.device.address}")
                        handleTransportDisconnect(event.device.address)
                    }
                    else -> {}
                }
            }
        }

        // 2. Listen to Discovery Events
        scope.launch {
            discoveryModule.events.collect { node ->
                _events.emit(BleDriverEvent.PeerDiscovered(node))
            }
        }
    }

    // --- Strategy Implementations ---

    private class ClientStrategy(
        private val handler: GattClientHandler,
        private val eventJob: Job // Holds the coroutine collecting events
    ) : TransportStrategy {
        override val type = TransportType.OUTGOING
        override val address: String = handler.targetDevice.address.uppercase()

        override suspend fun send(data: ByteArray, type: TransportDataType) {
            handler.sendMessage(type, data)
        }

        override fun disconnect() {
            Log.d("BleDriver", "ClientStrategy: Disconnecting and cancelling job for $address")
            eventJob.cancel() // Stop the listener
            handler.disconnect()
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

    // --- Registry Management ---

    private fun resolveNode(address: String): PeerConnection? {
        val normalized = address.uppercase()
        return _peers.value.values.find { it.address == normalized }
    }

    /**
     * Handles a new connection attempt (Incoming or Outgoing).
     * Enforces the "One Link" rule using Node ID as a tie-breaker.
     */
    private fun handleNewTransport(nodeId: Int, newStrategy: TransportStrategy) {
        // Atomic Get-Or-Put logic for StateFlow
        var peer = _peers.value[nodeId]
        if (peer == null) {
            val newPeer = PeerConnection(nodeId)
            _peers.update { current ->
                if (current.containsKey(nodeId)) current else current + (nodeId to newPeer)
            }
            // Re-fetch to ensure we have the canonical instance (in case of race)
            peer = _peers.value[nodeId]!!
            Log.i("BleDriver", "New Peer Created: $nodeId")
        }

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

                // 3. Tie-Breaker Logic (Simultaneous Connection)
                // We have two links on the same MAC (one Incoming, one Outgoing).
                // We must drop one to save resources.
                // Rule: The connection where ClientID > ServerID wins.
                val keepNew = shouldKeepNewConnection(
                    myId = myNodeId,
                    remoteId = nodeId,
                    newIsOutgoing = (newStrategy.type == TransportType.OUTGOING)
                )

                if (keepNew) {
                    Log.i("BleDriver", "Collision Resolution: Replacing existing ${current.type} with new ${newStrategy.type} (Node $nodeId)")
                    peer.setStrategy(newStrategy)
                } else {
                    Log.i("BleDriver", "Collision Resolution: Rejecting new ${newStrategy.type}, keeping existing ${current.type} (Node $nodeId)")
                    newStrategy.disconnect()
                    return // Don't emit connected event
                }
            } else {
                // No collision, just set it
                Log.d("BleDriver", "Setting ${newStrategy.type} Transport for Node $nodeId")
                peer.setStrategy(newStrategy)
            }
        }

        scope.launch { _events.emit(BleDriverEvent.PeerConnected(nodeId)) }
    }

    /**
     * Returns TRUE if the new connection is "Better" according to the rule:
     * "The connection where ClientID > ServerID is preferred."
     */
    private fun shouldKeepNewConnection(myId: Int, remoteId: Int, newIsOutgoing: Boolean): Boolean {
        if (newIsOutgoing) return myId > remoteId
        return remoteId > myId
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

                // Remove from Registry (Atomic update)
                _peers.update { it - peer.nodeId }

                scope.launch { _events.emit(BleDriverEvent.PeerDisconnected(peer.nodeId)) }
            }
        }
    }

    // --- Public API ---

    fun validateCapabilities(): Result<Unit> {
        if (adapter == null) return Result.failure(IllegalStateException("Bluetooth is not supported."))
        if (!adapter.isEnabled) return Result.failure(IllegalStateException("Bluetooth is turned off."))
        if (adapter.bluetoothLeAdvertiser == null) return Result.failure(IllegalStateException("BLE Advertising not supported."))
        return Result.success(Unit)
    }

    fun setCredentials(accessCode: String, ownNodeId: Int) {
        this.myAccessCode = accessCode
        this.myNodeId = ownNodeId
        serverHandler.currentAccessCode = accessCode
    }

    fun startScanning() = discoveryModule.start()
    fun stopScanning() = discoveryModule.stop()
    fun startAdvertising(config: AdvertisingConfig) = advertiserModule.start(config)
    fun stopAdvertising() = advertiserModule.stop()

    suspend fun connectTo(address: String, nodeId: Int) {
        val normalizedAddress = address.uppercase()

        // 1. Check if already connected
        val existingPeer = _peers.value[nodeId]
        if (existingPeer != null && existingPeer.isActive()) {
            Log.d("BleDriver", "Ignored Connect: Already connected to Node $nodeId")
            return
        }

        Log.d("BleDriver", "CMD: Connect to $normalizedAddress (Node $nodeId)")
        val device = adapter.getRemoteDevice(normalizedAddress)

        if (!clientWorkerThread.isAlive) {
            Log.e("BleDriver", "CRITICAL: Client thread is dead. Cannot connect.")
            return
        }

        val client = GattClientHandler(
            context,
            scope,
            device,
            myNodeId,
            myAccessCode,
            clientWorkerThread.looper
        )

        val connectionResult = CompletableDeferred<Boolean>()

        // Launch the event collector. We capture the Job.
        val job = scope.launch {
            client.clientEvents.collect { event ->
                when (event) {
                    is ClientEvent.Authenticated -> {
                        Log.i("BleDriver", "Client: Authenticated with $nodeId")

                        // Capture the current job so the strategy can cancel it later
                        val myJob = currentCoroutineContext().job
                        val strategy = ClientStrategy(client, myJob)

                        handleNewTransport(nodeId, strategy)
                        if (connectionResult.isActive) connectionResult.complete(true)
                    }
                    is ClientEvent.MessageReceived -> {
                        _events.emit(BleDriverEvent.DataReceived(nodeId, event.data, event.type))
                    }
                    is ClientEvent.Disconnected, is ClientEvent.Error -> {
                        Log.w("BleDriver", "Client: Disconnected/Error with $nodeId: $event")
                        if (_peers.value[nodeId]?.address == normalizedAddress) {
                            handleTransportDisconnect(normalizedAddress)
                        }
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
                    Log.w("BleDriver", "Connection failed (Handshake logic)")
                    client.disconnect()
                    job.cancel() // Ensure job is killed if we fail early
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("BleDriver", "Connection Timed Out for $normalizedAddress", e)
            client.disconnect()
            job.cancel()
        }
    }

    fun disconnectNode(nodeId: Int) {
        val peer = _peers.value[nodeId] ?: return
        Log.i("BleDriver", "Force Disconnecting Node $nodeId")
        _peers.update { it - nodeId }
        peer.disconnect()
        scope.launch { _events.emit(BleDriverEvent.PeerDisconnected(nodeId)) }
    }

    fun disconnectAll() {
        serverHandler.disconnectAll()
        val currentPeers = _peers.value.values.toList()
        _peers.value = emptyMap()
        currentPeers.forEach { it.disconnect() }
    }

    suspend fun broadcast(data: ByteArray, type: TransportDataType) {
        _peers.value.values.forEach { peer ->
            peer.send(data, type)
        }
    }

    fun stop() {
        Log.d("BleDriver", "CMD: Stop (Soft)")
        stopScanning()
        stopAdvertising()
        scope.launch { disconnectAll() }
    }

    fun destroy() {
        Log.d("BleDriver", "CMD: Destroy (Hard)")
        stop()
        serverHandler.stopServer()
        clientWorkerThread.quitSafely()
    }
}