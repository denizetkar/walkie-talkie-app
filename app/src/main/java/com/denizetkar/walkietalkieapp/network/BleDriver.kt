package com.denizetkar.walkietalkieapp.network

import android.content.Context
import android.os.HandlerThread
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.bluetooth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    // Thread stays alive for the life of the Service
    private val clientWorkerThread = HandlerThread("BleClientWorker").apply { start() }

    private val activeClientHandlers = ConcurrentHashMap<String, GattClientHandler>()
    private val activeClientJobs = ConcurrentHashMap<String, Job>()
    private val serverConnections = ConcurrentHashMap<String, Int>()

    private var myAccessCode: String = ""
    private var myNodeId: Int = 0

    private val _connectedPeers = MutableStateFlow<Set<Int>>(emptySet())
    val connectedPeers = _connectedPeers.asStateFlow()

    private val _events = MutableSharedFlow<BleDriverEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    init {
        scope.launch {
            serverHandler.serverEvents.collect { event ->
                when (event) {
                    is ServerEvent.ClientAuthenticated -> {
                        Log.i("BleDriver", "Server: Peer Authenticated ${event.nodeId}")
                        ensureSingleConnection(event.nodeId, event.device.address)
                        serverConnections[event.device.address.uppercase()] = event.nodeId
                        _connectedPeers.update { it + event.nodeId }
                        _events.emit(BleDriverEvent.PeerConnected(event.nodeId))
                    }
                    is ServerEvent.MessageReceived -> {
                        val nodeId = serverConnections[event.device.address.uppercase()]
                        if (nodeId != null) {
                            _events.emit(BleDriverEvent.DataReceived(nodeId, event.data, event.type))
                        } else {
                            Log.w("BleDriver", "Server: Received data from unknown device ${event.device.address}")
                        }
                    }
                    is ServerEvent.ClientDisconnected -> {
                        Log.i("BleDriver", "Server: Client Disconnected")
                        val nodeId = serverConnections.remove(event.device.address.uppercase())
                        if (nodeId != null) {
                            _connectedPeers.update { it - nodeId }
                            _events.emit(BleDriverEvent.PeerDisconnected(nodeId))
                        }
                    }
                    else -> {}
                }
            }
        }

        scope.launch {
            discoveryModule.events.collect { node ->
                _events.emit(BleDriverEvent.PeerDiscovered(node))
            }
        }
    }

    /**
     * Checks if the given NodeID is already connected via a DIFFERENT MAC address.
     * If so, it disconnects the old (stale) connection to prevent zombie states.
     */
    private fun ensureSingleConnection(nodeId: Int, currentAddress: String) {
        val normalizedAddress = currentAddress.uppercase()

        // 1. Check Server connections
        val oldServerEntry = serverConnections.entries.find { it.value == nodeId && it.key != normalizedAddress }
        if (oldServerEntry != null) {
            Log.w("BleDriver", "Duplicate Node ID $nodeId detected (Old: ${oldServerEntry.key}, New: $normalizedAddress). Killing old Server connection.")
            serverHandler.disconnect(oldServerEntry.key)
            serverConnections.remove(oldServerEntry.key)
        }

        // 2. Check Client connections
        val oldClientEntry = activeClientHandlers.entries.find { it.value.targetNodeId == nodeId && it.key != normalizedAddress }
        if (oldClientEntry != null) {
            Log.w("BleDriver", "Duplicate Node ID $nodeId detected (Old: ${oldClientEntry.key}, New: $normalizedAddress). Killing old Client connection.")
            cleanupClient(oldClientEntry.key)
        }
    }

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
        if (activeClientHandlers.containsKey(normalizedAddress)) return
        ensureSingleConnection(nodeId, normalizedAddress)

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
            clientWorkerThread.looper,
            nodeId
        )

        val connectionResult = CompletableDeferred<Boolean>()

        val job = scope.launch {
            client.clientEvents.collect { event ->
                when (event) {
                    is ClientEvent.Authenticated -> {
                        Log.i("BleDriver", "Client: Authenticated with $nodeId")
                        ensureSingleConnection(nodeId, normalizedAddress)

                        _connectedPeers.update { it + nodeId }
                        _events.emit(BleDriverEvent.PeerConnected(nodeId))
                        if (connectionResult.isActive) connectionResult.complete(true)
                    }
                    is ClientEvent.MessageReceived -> {
                        _events.emit(BleDriverEvent.DataReceived(nodeId, event.data, event.type))
                    }
                    is ClientEvent.Disconnected, is ClientEvent.Error -> {
                        Log.w("BleDriver", "Client: Disconnected/Error with $nodeId")
                        _connectedPeers.update { it - nodeId }
                        _events.emit(BleDriverEvent.PeerDisconnected(nodeId))
                        if (connectionResult.isActive) connectionResult.complete(false)
                        cleanupClient(normalizedAddress)
                    }
                    else -> {}
                }
            }
        }

        activeClientHandlers[normalizedAddress] = client
        activeClientJobs[normalizedAddress] = job

        client.connect()

        try {
            withTimeout(Config.PEER_CONNECT_TIMEOUT) {
                val success = connectionResult.await()
                if (!success) cleanupClient(normalizedAddress)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("BleDriver", "Connection Timed Out for $normalizedAddress", e)
            cleanupClient(normalizedAddress)
        }
    }

    private fun cleanupClient(address: String) {
        val normalizedAddress = address.uppercase()
        activeClientJobs[normalizedAddress]?.cancel()
        activeClientJobs.remove(normalizedAddress)
        activeClientHandlers[normalizedAddress]?.disconnect()
        activeClientHandlers.remove(normalizedAddress)
    }

    /**
     * Forcefully disconnects a specific Node ID (both client and server side).
     * Used by MeshController when Liveness Check fails.
     */
    fun disconnectNode(nodeId: Int) {
        Log.w("BleDriver", "Force Disconnecting Node $nodeId")

        // 1. Disconnect Server Link
        val serverEntry = serverConnections.entries.find { it.value == nodeId }
        if (serverEntry != null) {
            serverHandler.disconnect(serverEntry.key)
            serverConnections.remove(serverEntry.key)
        }

        // 2. Disconnect Client Link
        val clientEntry = activeClientHandlers.entries.find { it.value.targetNodeId == nodeId }
        if (clientEntry != null) {
            cleanupClient(clientEntry.key)
        }

        // 3. Force State Update
        _connectedPeers.update { it - nodeId }
        scope.launch { _events.emit(BleDriverEvent.PeerDisconnected(nodeId)) }
    }

    fun disconnectAll() {
        serverHandler.disconnectAll()
        activeClientHandlers.values.forEach { it.disconnect() }
        activeClientHandlers.clear()
        serverConnections.clear()
        _connectedPeers.value = emptySet()
    }

    suspend fun broadcast(data: ByteArray, type: TransportDataType) {
        serverHandler.broadcast(data, type)
        activeClientHandlers.values.forEach { client ->
            client.sendMessage(type, data)
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