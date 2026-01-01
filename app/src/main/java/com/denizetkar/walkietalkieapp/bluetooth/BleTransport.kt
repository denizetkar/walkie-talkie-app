package com.denizetkar.walkietalkieapp.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.HandlerThread
import android.util.Log
import com.denizetkar.walkietalkieapp.network.NetworkTransport
import com.denizetkar.walkietalkieapp.network.TransportDataType
import com.denizetkar.walkietalkieapp.network.TransportEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class BleTransport(
    context: Context,
    private val scope: CoroutineScope
) : NetworkTransport {

    // --- Dependencies ---
    private val btManager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = btManager.adapter
    private val context = context.applicationContext

    // --- State ---
    private val _peerMap = MutableStateFlow<Map<Int, BlePeer>>(emptyMap())
    private val activeClientHandlers = ConcurrentHashMap<String, GattClientHandler>()
    private val activeClientJobs = ConcurrentHashMap<String, Job>()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning = _isScanning.asStateFlow()

    private val _scanIntent = MutableStateFlow(false)
    private val _activeConnectionAttempts = MutableStateFlow(0)

    // --- Public API ---
    override val connectedPeers: StateFlow<Set<Int>> = _peerMap.map { it.keys }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _events = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events = _events.asSharedFlow()

    // --- Config ---
    private var myAccessCode: String = ""
    private var myNodeId: Int = 0
    private val clientWorkerThread = HandlerThread("BleClientWorker").apply { start() }

    // --- Modules ---
    private val serverHandler = GattServerHandler(this.context, scope)
    private val discoveryModule = BleDiscoveryModule(adapter, scope, _events, _isScanning)
    private val advertiserModule = BleAdvertiserModule(adapter, serverHandler)

    init {
        scope.launch {
            serverHandler.serverEvents.collect { event -> handleServerEvent(event) }
        }

        scope.launch {
            combine(_scanIntent, _activeConnectionAttempts) { wantToScan, attempts ->
                Pair(wantToScan, attempts > 0)
            }.collectLatest { (wantToScan, isBusy) ->
                if (wantToScan && !isBusy) {
                    Log.d("BleTransport", "Radio Free: Starting Discovery")
                    discoveryModule.start()
                } else {
                    if (isBusy) {
                        Log.d("BleTransport", "Radio Busy (Connecting): Pausing Discovery")
                    } else {
                        Log.d("BleTransport", "Scan Intent Stopped")
                    }
                    discoveryModule.stop()
                }
            }
        }
    }

    override fun setCredentials(accessCode: String, ownNodeId: Int) {
        this.myAccessCode = accessCode
        this.myNodeId = ownNodeId
        serverHandler.currentAccessCode = accessCode
    }

    // ===========================================================================
    // 1. CONNECTION LOGIC (The Client Side)
    // ===========================================================================

    private val connectMutex = Mutex()

    override suspend fun connect(address: String, nodeId: Int) {
        val connectionResult = CompletableDeferred<Boolean>()
        _activeConnectionAttempts.update { it + 1 }
        try {
            connectMutex.withLock {
                if (_peerMap.value.containsKey(nodeId)) return
                if (activeClientHandlers.containsKey(address)) return

                val device = adapter.getRemoteDevice(address)
                val client = GattClientHandler(context, scope, device, myNodeId, myAccessCode, clientWorkerThread.looper)

                val job = scope.launch {
                    client.clientEvents.collect { event ->
                        when (event) {
                            is ClientEvent.Authenticated -> {
                                registerPeer(
                                    nodeId = nodeId,
                                    address = address,
                                    sender = { data, type -> client.sendMessage(type, data) },
                                    disconnector = { client.disconnect() }
                                )
                                if (connectionResult.isActive) connectionResult.complete(true)
                            }
                            is ClientEvent.MessageReceived -> {
                                handleIncomingData(nodeId, event.data, event.type)
                            }
                            is ClientEvent.Error, is ClientEvent.Disconnected -> {
                                if (connectionResult.isActive) connectionResult.complete(false)
                                cleanupClient(address)
                                this.cancel()
                            }
                            else -> {}
                        }
                    }
                }

                activeClientHandlers[address] = client
                activeClientJobs[address] = job

                client.connect()
            }

            val success = connectionResult.await()
            if (!success) cleanupClient(address)
        } catch (e: Exception) {
            cleanupClient(address)
            throw e
        } finally {
            _activeConnectionAttempts.update { it - 1 }
        }
    }

    private fun cleanupClient(address: String) {
        activeClientJobs[address]?.cancel()
        activeClientJobs.remove(address)
        activeClientHandlers[address]?.disconnect()
        activeClientHandlers.remove(address)
    }

    // ===========================================================================
    // 2. SERVER LOGIC (The Server Side)
    // ===========================================================================

    private suspend fun handleServerEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.ClientAuthenticated -> {
                registerPeer(
                    nodeId = event.nodeId,
                    address = event.device.address,
                    sender = { data, type -> serverHandler.sendTo(event.device.address, data, type) },
                    disconnector = { serverHandler.disconnect(event.device.address) }
                )
            }
            is ServerEvent.MessageReceived -> {
                val nodeId = _peerMap.value.entries.find { it.value.address == event.device.address }?.key
                if (nodeId != null) {
                    handleIncomingData(nodeId, event.data, event.type)
                }
            }
            is ServerEvent.ClientDisconnected -> {
                unregisterPeerByAddress(event.device.address)
            }
            else -> {}
        }
    }

    // ===========================================================================
    // 3. UNIFIED PEER REGISTRY
    // ===========================================================================

    private suspend fun registerPeer(
        nodeId: Int,
        address: String,
        sender: suspend (ByteArray, TransportDataType) -> Unit,
        disconnector: suspend () -> Unit
    ) {
        if (_peerMap.value.containsKey(nodeId)) {
            Log.w("BleTransport", "Duplicate Node $nodeId. Ignoring.")
            disconnector()
            return
        }

        val peer = BlePeer(nodeId, address, sender, disconnector)
        _peerMap.update { it + (nodeId to peer) }
        _events.emit(TransportEvent.ConnectionEstablished(nodeId))
    }

    private suspend fun unregisterPeerByAddress(address: String) {
        val nodeId = _peerMap.value.entries.find { it.value.address == address }?.key ?: return
        _peerMap.update { it - nodeId }
        _events.emit(TransportEvent.ConnectionLost(nodeId))
    }

    private suspend fun handleIncomingData(nodeId: Int, data: ByteArray, type: TransportDataType) {
        _events.emit(TransportEvent.DataReceived(nodeId, data, type))
    }

    // ===========================================================================
    // 4. STANDARD OVERRIDES
    // ===========================================================================

    override suspend fun disconnect(nodeId: Int) {
        val peer = _peerMap.value[nodeId] ?: return
        if (activeClientHandlers.containsKey(peer.address)) {
            cleanupClient(peer.address)
        }
        peer.disconnect()

        _peerMap.update { it - nodeId }
        _events.emit(TransportEvent.ConnectionLost(nodeId))
    }

    override suspend fun disconnectAll() {
        activeClientHandlers.keys.forEach { cleanupClient(it) }
        serverHandler.disconnectAll()

        _peerMap.value = emptyMap()
    }

    override suspend fun send(toNodeId: Int, data: ByteArray, type: TransportDataType) {
        _peerMap.value[toNodeId]?.send(data, type)
    }

    override suspend fun sendToAll(data: ByteArray, type: TransportDataType, excludeNodeId: Int?) {
        _peerMap.value.forEach { (nodeId, peer) ->
            if (nodeId != excludeNodeId) peer.send(data, type)
        }
    }

    override suspend fun shutdown() {
        discoveryModule.stop()
        advertiserModule.stop()
        disconnectAll()
        serverHandler.stopServer()
        clientWorkerThread.quitSafely()
    }

    override suspend fun startDiscovery() {
        _scanIntent.value = true
    }
    override suspend fun stopDiscovery() {
        _scanIntent.value = false
    }
    override suspend fun startAdvertising(groupName: String, networkId: Int, hops: Int, isAvailable: Boolean) =
        advertiserModule.start(groupName, myNodeId, networkId, hops, isAvailable)
    override suspend fun stopAdvertising() = advertiserModule.stop()
}