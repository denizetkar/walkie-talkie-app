package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uniffi.walkie_talkie_engine.AudioConfig
import uniffi.walkie_talkie_engine.AudioEngine
import uniffi.walkie_talkie_engine.PacketTransport
import uniffi.walkie_talkie_engine.initLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

sealed class EngineState {
    data object Idle : EngineState()
    data object Discovering : EngineState()
    data class Joining(val groupName: String) : EngineState()
    data class RadioActive(val groupName: String, val peerCount: Int) : EngineState()
}

class MeshController(
    context: Context,
    private val driver: BleDriver,
    private val scope: CoroutineScope, // Already Dispatchers.IO from Service
    private val ownNodeId: Int
) {
    // --- State ---
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state = _state.asStateFlow()

    private val _discoveredGroups = MutableStateFlow<List<DiscoveredGroup>>(emptyList())
    val discoveredGroups = _discoveredGroups.asStateFlow()
    private val foundGroupsMap = ConcurrentHashMap<String, DiscoveredGroup>()

    // --- Logic & Subsystems ---
    private val topology = TopologyEngine(ownNodeId)
    private val audioSession = AudioSessionManager(context)

    private val seenPackets = ConcurrentHashMap<Int, Long>()
    private val pendingConnections = ConcurrentHashMap.newKeySet<Int>()

    private val lastHeardFrom = ConcurrentHashMap<Int, Long>()

    private val packetTransport = object : PacketTransport {
        override fun sendPacket(data: ByteArray) {
            broadcastGeneratedPacket(data, TransportDataType.AUDIO)
        }
    }
    private val audioConfig = AudioConfig(
        sampleRate = Config.AUDIO_SAMPLE_RATE,
        frameSizeMs = Config.AUDIO_FRAME_SIZE_MS,
        jitterBufferMs = Config.AUDIO_JITTER_BUFFER_MS
    )
    private val audioEngine = AudioEngine(audioConfig, packetTransport)

    // --- Jobs ---
    private var heartbeatJob: Job? = null
    private var scanLoopJob: Job? = null
    private var cleanupJob: Job? = null
    private var packetCleanupJob: Job? = null
    private var livenessJob: Job? = null

    init {
        try { initLogger() } catch (_: Exception) {}

        scope.launch {
            driver.events.collect { event -> handleDriverEvent(event) }
        }

        startPacketCleanup()
    }

    fun checkSystemRequirements(): Result<Unit> = driver.validateCapabilities()

    // ===========================================================================
    // Public API (Called from Main Thread)
    // ===========================================================================

    fun startGroupScan() {
        Log.d("MeshController", "Action: Start Group Scan")
        transitionTo(EngineState.Discovering)
    }

    fun stopGroupScan() {
        if (_state.value is EngineState.Discovering) {
            transitionTo(EngineState.Idle)
        }
    }

    fun createGroup(name: String, code: String) {
        Log.d("MeshController", "Action: Create Group $name")
        driver.setCredentials(code, ownNodeId)
        transitionTo(EngineState.RadioActive(name, 0))
    }

    fun joinGroup(name: String, code: String) {
        Log.d("MeshController", "Action: Join Group $name")
        driver.setCredentials(code, ownNodeId)
        transitionTo(EngineState.Joining(name))
    }

    fun leave() {
        Log.d("MeshController", "Action: Leave")
        transitionTo(EngineState.Idle)
    }

    fun startTalking() {
        if (_state.value is EngineState.RadioActive) {
            scope.launch {
                try { audioEngine.startRecording() } catch (e: Exception) { Log.e("MeshController", "Mic Error", e) }
            }
        }
    }

    fun stopTalking() {
        scope.launch {
            try { audioEngine.stopRecording() } catch (e: Exception) { Log.e("MeshController", "Mic Error", e) }
        }
    }

    // ===========================================================================
    // State Machine (Async & Flattened)
    // ===========================================================================

    private fun transitionTo(newState: EngineState) {
        val oldState = _state.value
        if (oldState == newState) return

        // 1. Update UI State Immediately
        _state.value = newState
        Log.i("MeshController", "State Change: $oldState -> $newState")

        // 2. Offload Hardware Operations to IO Scope
        // This prevents blocking the Main Thread with Binder calls.
        scope.launch {
            // A. Teardown Old State
            if (oldState is EngineState.RadioActive) stopRadioLogic()
            if (oldState is EngineState.Discovering || oldState is EngineState.Joining) {
                driver.stopScanning()
                stopGroupCleanup()
            }

            // B. Setup New State
            when (newState) {
                is EngineState.Idle -> {
                    driver.stop()
                    foundGroupsMap.clear()
                    _discoveredGroups.value = emptyList()
                }
                is EngineState.Discovering -> {
                    foundGroupsMap.clear()
                    _discoveredGroups.value = emptyList()
                    startGroupCleanup()
                    driver.startScanning()
                }
                is EngineState.Joining -> {
                    driver.startScanning()
                }
                is EngineState.RadioActive -> {
                    startRadioLogic(newState.groupName)
                }
            }
        }
    }

    fun destroy() {
        driver.destroy()
    }

    // ===========================================================================
    // Radio Logic (Suspend Functions)
    // ===========================================================================

    private fun startRadioLogic(groupName: String) {
        // 1. Audio Setup (Blocking IO)
        audioSession.startSession()

        // 2. Start Concurrent Background Jobs
        // These must run in parallel to the rest of the logic, so we launch new jobs.
        livenessJob = scope.launch { runLivenessCheck() }
        heartbeatJob = scope.launch { runHeartbeatLoop(groupName) }
        scanLoopJob = scope.launch { runScanLoop() }

        // 3. Initial Advertising (Blocking IO)
        refreshAdvertising(groupName)
    }

    private suspend fun stopRadioLogic() {
        // 1. Cancel Background Jobs
        heartbeatJob?.cancel()
        scanLoopJob?.cancel()
        livenessJob?.cancel()

        // 2. Wait for jobs to actually finish (optional, but cleaner)
        joinAll(heartbeatJob, scanLoopJob, livenessJob)

        // 3. Teardown Hardware (Blocking IO)
        audioEngine.stopRecording()
        audioSession.stopSession()
        driver.disconnectAll()
        driver.stopScanning()
        driver.stopAdvertising()
    }

    private suspend fun CoroutineScope.runLivenessCheck() {
        while (isActive) {
            delay(Config.CLEANUP_PERIOD)
            val now = System.currentTimeMillis()
            val peers = driver.connectedPeers.value

            for (peerId in peers) {
                val lastTime = lastHeardFrom[peerId] ?: now
                if (now - lastTime > Config.PEER_CONNECT_TIMEOUT) {
                    Log.w("MeshController", "Peer $peerId timed out. Disconnecting.")
                    driver.disconnectNode(peerId)
                    lastHeardFrom.remove(peerId)
                }
            }
        }
    }

    private suspend fun CoroutineScope.runHeartbeatLoop(groupName: String) {
        while (isActive) {
            if (topology.checkTimeout()) {
                refreshAdvertising(groupName)
            }
            val packet = topology.generateHeartbeat()
            if (packet != null) {
                broadcastGeneratedPacket(packet, TransportDataType.CONTROL)
            }
            delay(Config.HEARTBEAT_INTERVAL)
        }
    }

    private suspend fun CoroutineScope.runScanLoop() {
        while (isActive) {
            val peers = driver.connectedPeers.value.size
            val scanDuration = if (peers < Config.TARGET_PEERS) Config.SCAN_PERIOD_AGGRESSIVE else Config.SCAN_PERIOD_LAZY
            val pauseDuration = if (peers < Config.TARGET_PEERS) Config.SCAN_PAUSE_AGGRESSIVE else Config.SCAN_INTERVAL_LAZY

            Log.d("MeshController", "Scan Loop: Starting Scan")
            driver.startScanning()
            delay(scanDuration)
            Log.d("MeshController", "Scan Loop: Stopping Scan")
            driver.stopScanning()
            delay(pauseDuration)
        }
    }

    private fun refreshAdvertising(groupName: String) {
        val topo = topology.getCurrentState()
        val isAvailable = driver.connectedPeers.value.size < Config.MAX_PEERS

        val config = AdvertisingConfig(
            groupName = groupName,
            ownNodeId = ownNodeId,
            networkId = topo.currentNetworkId,
            hopsToRoot = topo.hopsToRoot,
            isAvailable = isAvailable
        )
        driver.startAdvertising(config)
    }

    private fun broadcastGeneratedPacket(data: ByteArray, type: TransportDataType) {
        markPacketAsSeen(data)
        scope.launch {
            driver.broadcast(data, type)
        }
    }

    // ===========================================================================
    // Cleanup Helpers
    // ===========================================================================

    private fun startGroupCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(Config.CLEANUP_PERIOD)
                val now = System.currentTimeMillis()
                val removed = foundGroupsMap.values.removeIf { now - it.lastSeen > Config.GROUP_ADVERTISEMENT_TIMEOUT }
                if (removed) {
                    _discoveredGroups.value = foundGroupsMap.values.sortedByDescending { it.highestRssi }
                }
            }
        }
    }

    private fun stopGroupCleanup() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    private fun startPacketCleanup() {
        packetCleanupJob?.cancel()
        packetCleanupJob = scope.launch {
            while (isActive) {
                delay(Config.CLEANUP_PERIOD)
                val now = System.currentTimeMillis()
                seenPackets.entries.removeIf { (now - it.value) > Config.PACKET_CACHE_TIMEOUT }
            }
        }
    }

    private fun markPacketAsSeen(data: ByteArray) {
        val hash = data.contentHashCode()
        seenPackets[hash] = System.currentTimeMillis()
    }

    // ===========================================================================
    // Event Handling
    // ===========================================================================

    private fun handleDriverEvent(event: BleDriverEvent) {
        when (event) {
            is BleDriverEvent.PeerDiscovered -> handleDiscovery(event.node)
            is BleDriverEvent.PeerConnected -> {
                lastHeardFrom[event.nodeId] = System.currentTimeMillis()
                updatePeerCount()
            }
            is BleDriverEvent.PeerDisconnected -> {
                lastHeardFrom.remove(event.nodeId)
                updatePeerCount()
            }
            is BleDriverEvent.DataReceived -> handleData(event)
            else -> {}
        }
    }

    private fun updatePeerCount() {
        val s = _state.value
        val count = driver.connectedPeers.value.size

        if (s is EngineState.RadioActive) {
            _state.value = s.copy(peerCount = count)
            refreshAdvertising(s.groupName)
        } else if (s is EngineState.Joining && count > 0) {
            transitionTo(EngineState.RadioActive(s.groupName, count))
        }
    }

    private fun handleDiscovery(node: TransportNode) {
        val s = _state.value

        // 1. Discovery Mode
        if (s is EngineState.Discovering) {
            val existing = foundGroupsMap[node.name]
            val rssi = max(node.rssi, existing?.highestRssi ?: Config.WORST_RSSI)
            foundGroupsMap[node.name] = DiscoveredGroup(node.name, rssi)
            _discoveredGroups.value = foundGroupsMap.values.sortedByDescending { it.highestRssi }
            return
        }

        // 2. Joining Mode
        if (s is EngineState.Joining && node.name == s.groupName) {
            connectSafely(node)
            return
        }

        // 3. Radio Mode (Mesh Logic)
        if (s is EngineState.RadioActive && node.name == s.groupName) {
            val topo = topology.getCurrentState()
            val currentPeers = driver.connectedPeers.value
            if (currentPeers.contains(node.nodeId)) return

            var shouldConnect = false

            // Priority 1: Merge Up (They have a better Root)
            if (node.networkId > topo.currentNetworkId) {
                shouldConnect = true
            }
            // Priority 2: Fill Slots (We are lonely)
            else if (currentPeers.size < Config.TARGET_PEERS) {
                // Connect if they are available OR if we can absorb them (Merge Down)
                if (node.isAvailable || node.networkId < topo.currentNetworkId) {
                    shouldConnect = true
                }
            }
            // Priority 3: Absorb (Merge Down)
            else if (currentPeers.size < Config.MAX_PEERS) {
                if (node.networkId < topo.currentNetworkId) {
                    shouldConnect = true
                }
            }

            if (shouldConnect) {
                connectSafely(node)
            }
        }
    }

    private fun connectSafely(node: TransportNode) {
        if (pendingConnections.contains(node.nodeId)) return

        pendingConnections.add(node.nodeId)
        scope.launch {
            try {
                driver.connectTo(node.id, node.nodeId)
            } finally {
                pendingConnections.remove(node.nodeId)
            }
        }
    }

    private fun handleData(event: BleDriverEvent.DataReceived) {
        lastHeardFrom[event.fromNodeId] = System.currentTimeMillis()

        val packetHash = event.data.contentHashCode()
        if (seenPackets.containsKey(packetHash)) return
        seenPackets[packetHash] = System.currentTimeMillis()

        if (event.type == TransportDataType.CONTROL) {
            val (type, payload) = PacketUtils.parseControlPacket(event.data) ?: return
            if (type == PacketUtils.TYPE_HEARTBEAT) {
                val (netId, seq, hops) = PacketUtils.parseHeartbeatPayload(payload) ?: return

                if (topology.onHeartbeatReceived(netId, seq, hops)) {
                    val s = _state.value
                    if (s is EngineState.RadioActive) refreshAdvertising(s.groupName)

                    val newPayload = PacketUtils.createHeartbeatPayload(netId, seq, hops + 1)
                    val newPacket = PacketUtils.createControlPacket(PacketUtils.TYPE_HEARTBEAT, newPayload)
                    broadcastGeneratedPacket(newPacket, TransportDataType.CONTROL)
                }
            }
        } else {
            try { audioEngine.pushIncomingPacket(event.data) } catch (e: Exception) { Log.e("MeshController", "Audio Error", e) }
            scope.launch { driver.broadcast(event.data, TransportDataType.AUDIO) }
        }
    }

    // Helper to join multiple jobs
    private suspend fun joinAll(vararg jobs: Job?) {
        jobs.filterNotNull().joinAll()
    }
}