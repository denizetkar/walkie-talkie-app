package com.denizetkar.walkietalkieapp.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.denizetkar.walkietalkieapp.network.NetworkTransport
import com.denizetkar.walkietalkieapp.network.TransportDataType
import com.denizetkar.walkietalkieapp.network.TransportEvent
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

    private val context = context.applicationContext
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = btManager.adapter

    private val peerMap = MutableStateFlow<Map<Int, BlePeer>>(emptyMap())
    private val rawClientHandlers = ConcurrentHashMap<String, GattClientHandler>()
    private val clientJobs = ConcurrentHashMap<String, Job>()

    override val connectedPeers: StateFlow<Set<Int>> = peerMap
        .map { it.keys }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _events = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events = _events.asSharedFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning = _isScanning.asStateFlow()

    private val serverHandler = GattServerHandler(context, scope)
    private val discoveryModule = BleDiscoveryModule(adapter, scope, _events, _isScanning)
    private val advertiserModule = BleAdvertiserModule(adapter, serverHandler)

    private var myAccessCode: String = ""
    private var myNodeId: Int = 0

    init {
        scope.launch {
            serverHandler.serverEvents.collect { event ->
                when (event) {
                    is ServerEvent.ClientAuthenticated -> {
                        val peer = BlePeer(
                            nodeId = event.nodeId,
                            address = event.device.address,
                            sendImpl = { data, type -> serverHandler.sendTo(event.device.address, data, type) },
                            disconnectImpl = { serverHandler.disconnect(event.device.address) }
                        )
                        registerPeer(peer)
                    }
                    is ServerEvent.ClientDisconnected -> unregisterPeerByAddress(event.device.address)
                    is ServerEvent.MessageReceived -> {
                        val nodeId = peerMap.value.entries.find { it.value.address == event.device.address }?.key
                        if (nodeId != null) {
                            _events.emit(TransportEvent.DataReceived(nodeId, event.data, event.type))
                        }
                    }
                    else -> {}
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
    // PEER MANAGEMENT (Reactive Updates)
    // ===========================================================================

    private suspend fun registerPeer(peer: BlePeer) {
        val currentMap = peerMap.value
        if (currentMap.containsKey(peer.nodeId)) {
            Log.w("BleTransport", "Duplicate connection to Node ${peer.nodeId}. Dropping new one.")
            peer.disconnect()
            return
        }
        peerMap.update { it + (peer.nodeId to peer) }
        _events.emit(TransportEvent.ConnectionEstablished(peer.nodeId))
    }

    private suspend fun unregisterPeerByAddress(address: String) {
        val nodeId = peerMap.value.entries.find { it.value.address == address }?.key ?: return
        peerMap.update { it - nodeId }
        _events.emit(TransportEvent.ConnectionLost(nodeId))
    }

    // ===========================================================================
    // CONNECTION LOGIC
    // ===========================================================================

    private val connectMutex = Mutex()

    override suspend fun connect(address: String, nodeId: Int) = connectMutex.withLock {
        if (peerMap.value.containsKey(nodeId)) return
        if (rawClientHandlers.containsKey(address)) return

        val device = adapter.getRemoteDevice(address)
        val client = GattClientHandler(context, scope, device, myNodeId, myAccessCode)
        rawClientHandlers[address] = client

        val job = scope.launch {
            client.clientEvents.collect { event ->
                when (event) {
                    is ClientEvent.Authenticated -> {
                        val peer = BlePeer(
                            nodeId = nodeId,
                            address = address,
                            sendImpl = { data, type -> client.sendMessage(type, data) },
                            disconnectImpl = { client.disconnect() }
                        )
                        registerPeer(peer)
                    }
                    is ClientEvent.Disconnected -> {
                        rawClientHandlers.remove(address)
                        clientJobs.remove(address)
                        unregisterPeerByAddress(address)
                        this.cancel()
                    }
                    is ClientEvent.MessageReceived -> {
                        _events.emit(TransportEvent.DataReceived(nodeId, event.data, event.type))
                    }
                    is ClientEvent.Error -> {
                        Log.e("BleTransport", "Client Error: ${event.message}")
                        disconnect(nodeId)
                    }
                    else -> {}
                }
            }
        }
        clientJobs[address] = job

        client.connect()
    }

    override suspend fun disconnect(nodeId: Int) {
        val peer = peerMap.value[nodeId] ?: return

        clientJobs[peer.address]?.cancel()
        clientJobs.remove(peer.address)
        rawClientHandlers[peer.address]?.disconnect()
        rawClientHandlers.remove(peer.address)

        serverHandler.disconnect(peer.address)

        peerMap.update { it - nodeId }
        _events.emit(TransportEvent.ConnectionLost(nodeId))
    }

    override suspend fun disconnectAll() {
        rawClientHandlers.forEach { (address, client) ->
            clientJobs[address]?.cancel()
            client.disconnect()
        }
        rawClientHandlers.clear()
        clientJobs.clear()

        val currentPeers = peerMap.value.values
        currentPeers.forEach { peer ->
            serverHandler.disconnect(peer.address)
        }
        peerMap.value = emptyMap()
    }

    override suspend fun shutdown() {
        discoveryModule.stop()
        advertiserModule.stop()
        disconnectAll()
        serverHandler.stopServer()
    }

    // ===========================================================================
    // DATA TRANSFER
    // ===========================================================================

    override suspend fun send(toNodeId: Int, data: ByteArray, type: TransportDataType) {
        peerMap.value[toNodeId]?.send(data, type)
    }

    override suspend fun sendToAll(data: ByteArray, type: TransportDataType, excludeNodeId: Int?) {
        peerMap.value.forEach { (nodeId, peer) ->
            if (nodeId != excludeNodeId) {
                peer.send(data, type)
            }
        }
    }

    // ===========================================================================
    // DELEGATES
    // ===========================================================================

    override suspend fun startDiscovery() = discoveryModule.start()
    override suspend fun stopDiscovery() = discoveryModule.stop()
    override suspend fun startAdvertising(groupName: String, networkId: Int, hops: Int, isAvailable: Boolean) = advertiserModule.start(groupName, myNodeId, networkId, hops, isAvailable)
    override suspend fun stopAdvertising() = advertiserModule.stop()
}