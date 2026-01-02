package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.bluetooth.BleTransport
import com.denizetkar.walkietalkieapp.network.NetworkTransport
import com.denizetkar.walkietalkieapp.network.TransportDataType
import com.denizetkar.walkietalkieapp.network.TransportEvent
import com.denizetkar.walkietalkieapp.network.TransportNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uniffi.walkie_talkie_engine.AudioEngine
import uniffi.walkie_talkie_engine.PacketTransport
import uniffi.walkie_talkie_engine.initLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.random.Random

data class DiscoveredGroup(
    val name: String,
    val highestRssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

class MeshNetworkManager(
    context: Context,
    private val scope: CoroutineScope
) {
    private val transport: NetworkTransport = BleTransport(context, scope)
    val ownNodeId = Random.nextInt(1, Int.MAX_VALUE)

    // ===========================================================================
    // STATE: TOPOLOGY
    // ===========================================================================
    private var currentNetworkId: Int = ownNodeId
    private var hopsToRoot: Int = 0
    private var rootSequence: Int = 0
    private var lastHeartbeatTime: Long = System.currentTimeMillis()

    // ===========================================================================
    // STATE: APP & MESH
    // ===========================================================================
    private var isMeshMode = false
    private var currentGroupName: String? = null
    private var isAdvertisingAllowed = false
    private var lastAdvertisedAvailability = true

    private val pendingConnections = ConcurrentHashMap.newKeySet<Int>()
    val peerCount = transport.connectedPeers
        .map { it.size }
        .stateIn(scope, SharingStarted.Lazily, 0)

    private val _discoveredGroups = MutableStateFlow<List<DiscoveredGroup>>(emptyList())
    val discoveredGroups = _discoveredGroups.asStateFlow()
    private val foundGroupsMap = ConcurrentHashMap<String, DiscoveredGroup>()

    val isScanning = transport.isScanning
    private val _isTalking = MutableStateFlow(false)

    // Deduplication Cache
    private val seenPackets = ConcurrentHashMap<Int, Long>()

    // Audio variables
    private val audioTransport = object : PacketTransport {
        override fun sendPacket(data: ByteArray) {
            scope.launch(Dispatchers.IO) {
                transport.sendToAll(data, TransportDataType.AUDIO)
            }
        }
    }
    private val audioEngine = AudioEngine(audioTransport)
    private val audioSession = AudioSessionManager(context)

    // Jobs
    private var startAdvertisementJob: Job? = null
    private var heartbeatJob: Job? = null
    private var scanJob: Job? = null
    private var groupCleanupJob: Job? = null

    init {
        try { initLogger() } catch (_: Exception) {}

        scope.launch {
            transport.events.collect { event -> handleTransportEvent(event) }
        }

        scope.launch {
            combine(
                transport.connectedPeers.map { it.size },
                _isTalking
            ) { count, isTalking ->
                Pair(count, isTalking)
            }.collectLatest { (count, isTalking) ->
                manageScanningState(count, isTalking)
            }
        }

        startPacketCleanupLoop()
    }

    // ===========================================================================
    // Audio functions
    // ===========================================================================

    fun startTalking() {
        if (!isMeshMode) return
        _isTalking.value = true
        try {
            audioEngine.startRecording()
            Log.d("MeshManager", "PTT Pressed: Recording Started")
        } catch (e: Exception) {
            Log.e("MeshManager", "Failed to start recording", e)
        }
    }

    fun stopTalking() {
        _isTalking.value = false
        try {
            audioEngine.stopRecording()
            Log.d("MeshManager", "PTT Released: Recording Stopped")
        } catch (e: Exception) {
            Log.e("MeshManager", "Failed to stop recording", e)
        }
    }

    // ===========================================================================
    // MODE 1: GROUP SCANNING (Join Screen)
    // ===========================================================================

    fun startGroupScan() {
        isMeshMode = false
        currentGroupName = null
        scanJob?.cancel()
        foundGroupsMap.clear()
        _discoveredGroups.value = emptyList()
        startGroupCleanup()
        scope.launch { transport.startDiscovery() }
    }

    fun stopGroupScan() {
        groupCleanupJob?.cancel()
        scope.launch { transport.stopDiscovery() }
    }

    private fun startGroupCleanup() {
        groupCleanupJob?.cancel()
        groupCleanupJob = scope.launch {
            while (isActive) {
                delay(Config.CLEANUP_PERIOD)
                val now = System.currentTimeMillis()
                val listChanged = foundGroupsMap.values.removeIf { now - it.lastSeen > Config.GROUP_ADVERTISEMENT_TIMEOUT }
                if (listChanged) {
                    _discoveredGroups.value = foundGroupsMap.values.sortedByDescending { it.highestRssi }
                }
            }
        }
    }

    // ===========================================================================
    // MODE 2: MESH NETWORKING (Radio Screen)
    // ===========================================================================

    fun startMesh(groupName: String, accessCode: String, asHost: Boolean) {
        isMeshMode = true
        currentGroupName = groupName
        isAdvertisingAllowed = false

        // Reset Topology
        currentNetworkId = ownNodeId
        hopsToRoot = 0
        rootSequence = 0
        lastHeartbeatTime = System.currentTimeMillis()

        stopGroupScan()
        audioSession.startSession()
        transport.setCredentials(accessCode, ownNodeId)

        val peerThreshold = if (asHost) 0 else 1
        startAdvertisementJob = scope.launch {
            peerCount.first { it >= peerThreshold }

            Log.i("MeshManager", "Advertising Threshold Met ($peerThreshold). Enabling Visibility.")
            isAdvertisingAllowed = true
            refreshAdvertising()
        }

        // Start Heartbeat Loop (Sends only if Root)
        startHeartbeatLoop()

        updateScanStrategy(peerCount.value)
    }

    fun stopMesh() {
        isMeshMode = false
        isAdvertisingAllowed = false
        startAdvertisementJob?.cancel()
        heartbeatJob?.cancel()
        scanJob?.cancel()
        scope.launch { transport.stop() }
        audioSession.stopSession()
        try { audioEngine.shutdown() } catch (_: Exception) {}
        try { audioEngine.stopRecording() } catch (_: Exception) {}
    }

    /**
     * Pushes the current Topology State to the Advertiser.
     * Only runs if the "Latch" (isAdvertisingAllowed) is open.
     */
    private fun refreshAdvertising() {
        if (!isMeshMode || currentGroupName == null || !isAdvertisingAllowed) return

        val isAvailable = transport.connectedPeers.value.size < Config.MAX_PEERS
        lastAdvertisedAvailability = isAvailable
        scope.launch {
            transport.startAdvertising(currentGroupName!!, currentNetworkId, hopsToRoot, isAvailable)
        }
    }

    // ===========================================================================
    // TOPOLOGY CONTROL (The Heartbeat)
    // ===========================================================================

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()

                // CASE A: I am the Root
                if (currentNetworkId == ownNodeId) {
                    rootSequence++
                    lastHeartbeatTime = now

                    // Only broadcast if we are allowed (Host or Connected Joiner)
                    if (isAdvertisingAllowed) {
                        val payload = PacketUtils.createHeartbeatPayload(ownNodeId, rootSequence, 0)
                        transport.sendToAll(payload, TransportDataType.CONTROL)
                        if (rootSequence % 6 == 0) Log.d("MeshTopology", "Root Heartbeat #$rootSequence")
                    }
                }
                // CASE B: I am a Follower
                else {
                    if (now - lastHeartbeatTime > Config.HEARTBEAT_TIMEOUT) {
                        Log.w("MeshTopology", "Root Timeout! Downgrading to Self.")
                        downgradeToRoot()
                    }
                }

                delay(Config.HEARTBEAT_INTERVAL)
            }
        }
    }

    private fun downgradeToRoot() {
        currentNetworkId = ownNodeId
        hopsToRoot = 0
        rootSequence = 0
        lastHeartbeatTime = System.currentTimeMillis()
        refreshAdvertising()
    }

    private fun handleHeartbeat(fromNodeId: Int, payload: ByteArray) {
        val (netId, seq, hops) = PacketUtils.parseHeartbeatPayload(payload) ?: return

        var topologyChanged = false
        var shouldRelay = false

        // 1. MERGE / INITIAL JOIN
        if (netId > currentNetworkId) {
            Log.i("MeshTopology", "Adopting Better Network: $netId (Old: $currentNetworkId)")
            currentNetworkId = netId
            rootSequence = seq
            hopsToRoot = hops + 1
            lastHeartbeatTime = System.currentTimeMillis()
            topologyChanged = true
            shouldRelay = true
        }
        // 2. UPDATE
        else if (netId == currentNetworkId) {
            if (seq > rootSequence) {
                rootSequence = seq
                hopsToRoot = hops + 1
                lastHeartbeatTime = System.currentTimeMillis()
                topologyChanged = true
                shouldRelay = true
            }
        }

        if (topologyChanged) {
            refreshAdvertising()
        }

        if (shouldRelay) {
            relayHeartbeat(fromNodeId, netId, seq, hops + 1)
        }
    }

    private fun relayHeartbeat(fromNodeId: Int, netId: Int, seq: Int, myHops: Int) {
        val newPayload = PacketUtils.createHeartbeatPayload(netId, seq, myHops)
        scope.launch {
            transport.sendToAll(newPayload, TransportDataType.CONTROL, excludeNodeId = fromNodeId)
        }
    }

    // ===========================================================================
    // EVENT HANDLING
    // ===========================================================================

    private fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.NodeDiscovered -> handleNodeDiscovered(event.node)
            is TransportEvent.ConnectionEstablished -> Log.i("MeshManager", "Peer Connected: ${event.nodeId}")
            is TransportEvent.ConnectionLost -> Log.i("MeshManager", "Peer Lost: ${event.nodeId}")
            is TransportEvent.DataReceived -> handleDataReceived(event.fromNodeId, event.data, event.type)
            is TransportEvent.Error -> Log.e("MeshManager", "Error: ${event.message}")
        }
    }

    private fun handleNodeDiscovered(node: TransportNode) {
        // Mode 1: Just collecting groups for UI
        if (!isMeshMode) {
            if (node.name == null) return
            val existing = foundGroupsMap[node.name]
            val rssi = max(node.rssi, existing?.highestRssi ?: Config.WORST_RSSI)
            foundGroupsMap[node.name] = DiscoveredGroup(node.name, rssi)
            _discoveredGroups.value = foundGroupsMap.values.toList().sortedByDescending { it.highestRssi }
            return
        }

        // Mode 2: Mesh Logic
        if (node.name != currentGroupName) return
        if (node.nodeId == ownNodeId) return
        val currentPeers = transport.connectedPeers.value
        if (currentPeers.contains(node.nodeId)) return
        if (pendingConnections.contains(node.nodeId)) return

        // --- PEER SELECTION STRATEGY ---
        var shouldConnect = false

        // Priority 1: ISLAND MERGE (They have a higher Network ID)
        if (node.networkId > currentNetworkId) {
            Log.i("MeshManager", "Found Better Network (${node.networkId}). Connecting Priority!")
            shouldConnect = true
        }
        // Priority 2: FILL SLOTS (If I am lonely)
        else if (currentPeers.size < Config.TARGET_PEERS) {
            // If they are available OR if they are an inferior island (we want to absorb them)
            if (node.isAvailable || node.networkId < currentNetworkId) {
                Log.d("MeshManager", "Connecting to fill slots: ${node.nodeId}")
                shouldConnect = true
            }
        }
        // Priority 3: MAX CAPACITY (Only for merging inferior islands)
        else if (currentPeers.size < Config.MAX_PEERS) {
            if (node.networkId < currentNetworkId) {
                Log.i("MeshManager", "Absorbing Inferior Island: ${node.nodeId}")
                shouldConnect = true
            }
        }

        if (shouldConnect) {
            pendingConnections.add(node.nodeId)
            scope.launch {
                try {
                    transport.connect(node.id, node.nodeId)
                } finally {
                    pendingConnections.remove(node.nodeId)
                }
            }
        }
    }

    private fun handleDataReceived(fromNodeId: Int, data: ByteArray, type: TransportDataType) {
        // 1. Handle Control Messages
        if (type == TransportDataType.CONTROL) {
            val (msgType, payload) = PacketUtils.parseControlPacket(data) ?: return
            if (msgType == PacketUtils.TYPE_HEARTBEAT) {
                handleHeartbeat(fromNodeId, payload)
            }
            return
        }

        // 2. Handle Data (Voice/Chat)
        val packetHash = data.contentHashCode()
        if (seenPackets.containsKey(packetHash)) return
        seenPackets[packetHash] = System.currentTimeMillis()

        if (type == TransportDataType.AUDIO) {
            try {
                audioEngine.pushIncomingPacket(data)
            } catch (e: Exception) {
                Log.e("MeshManager", "Error pushing audio to engine", e)
            }
        }

        // Flood
        scope.launch { transport.sendToAll(data, type, excludeNodeId = fromNodeId) }
    }

    // ===========================================================================
    // SCANNING LOOP
    // ===========================================================================

    private suspend fun manageScanningState(count: Int, isTalking: Boolean) {
        if (isTalking) {
            Log.d("MeshManager", "State: TALKING. Pausing Scan.")
            transport.stopDiscovery()
            return
        }

        if (!isMeshMode) return
        updateScanStrategy(count)
        if (!isAdvertisingAllowed) return

        val isAvailable = count < Config.MAX_PEERS
        if (isAvailable != lastAdvertisedAvailability) {
            refreshAdvertising()
        }
    }

    private fun updateScanStrategy(count: Int) {
        scanJob?.cancel()
        scanJob = scope.launch {
            when {
                count < Config.TARGET_PEERS -> {
                    Log.d("MeshManager", "State: LONELY ($count). Aggressive Scan.")
                    runScanLoop(Config.SCAN_PERIOD_AGGRESSIVE, Config.SCAN_PAUSE_AGGRESSIVE)
                }
                count < Config.MAX_PEERS -> {
                    Log.d("MeshManager", "State: STABLE ($count). Lazy Scan.")
                    delay(Config.SCAN_INTERVAL_LAZY)
                    runScanLoop(Config.SCAN_PERIOD_LAZY, Config.SCAN_INTERVAL_LAZY)
                }
                else -> {
                    Log.d("MeshManager", "State: FULL ($count). Stop Scan.")
                    transport.stopDiscovery()
                }
            }
        }
    }

    private suspend fun runScanLoop(scanDuration: Long, pauseDuration: Long) {
        while (currentCoroutineContext().isActive) {
            transport.startDiscovery()
            delay(scanDuration)
            transport.stopDiscovery()
            delay(pauseDuration)
        }
    }

    private fun startPacketCleanupLoop() {
        scope.launch {
            while (isActive) {
                delay(Config.CLEANUP_PERIOD)
                val now = System.currentTimeMillis()
                seenPackets.entries.removeIf { (now - it.value) > Config.PACKET_CACHE_TIMEOUT }
            }
        }
    }

    // ===========================================================================
    // LIFECYCLE FUNCTIONS
    // ===========================================================================

    fun destroy() {
        transport.cleanup()
    }
}