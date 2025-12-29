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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val peers = ConcurrentHashMap<Int, BlePeer>()
    private val rawClientHandlers = ConcurrentHashMap<String, GattClientHandler>()
    private val clientJobs = ConcurrentHashMap<String, Job>()

    private val _events = MutableSharedFlow<TransportEvent>()
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
                        val nodeId = peers.entries.find { it.value.address == event.device.address }?.key
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
    // PEER MANAGEMENT
    // ===========================================================================

    private suspend fun registerPeer(peer: BlePeer) {
        if (peers.containsKey(peer.nodeId)) {
            Log.w("BleTransport", "Duplicate connection to Node ${peer.nodeId}. Dropping new one.")
            peer.disconnect()
            return
        }
        peers[peer.nodeId] = peer
        _events.emit(TransportEvent.ConnectionEstablished(peer.nodeId))
    }

    private suspend fun unregisterPeerByAddress(address: String) {
        val nodeId = peers.entries.find { it.value.address == address }?.key ?: return
        peers.remove(nodeId)
        _events.emit(TransportEvent.ConnectionLost(nodeId))
    }

    // ===========================================================================
    // CONNECTION LOGIC
    // ===========================================================================

    private val connectMutex = Mutex()

    override suspend fun connect(address: String, nodeId: Int) = connectMutex.withLock {
        if (peers.containsKey(nodeId)) return
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
        val peer = peers[nodeId] ?: return

        clientJobs[peer.address]?.cancel()
        clientJobs.remove(peer.address)
        rawClientHandlers[peer.address]?.disconnect()
        rawClientHandlers.remove(peer.address)

        serverHandler.disconnect(peer.address)

        peers.remove(nodeId)
        _events.emit(TransportEvent.ConnectionLost(nodeId))
    }

    // ===========================================================================
    // DATA TRANSFER
    // ===========================================================================

    override suspend fun send(toNodeId: Int, data: ByteArray, type: TransportDataType) {
        peers[toNodeId]?.send(data, type)
    }

    override suspend fun sendToAll(data: ByteArray, type: TransportDataType, excludeNodeId: Int?) {
        peers.forEach { (nodeId, peer) ->
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
    override suspend fun startAdvertising(groupName: String) = advertiserModule.start(groupName, myNodeId)
    override suspend fun stopAdvertising() = advertiserModule.stop()
}