package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.AdvertisingConfig
import com.denizetkar.walkietalkieapp.network.BleDriver
import com.denizetkar.walkietalkieapp.network.BleDriverEvent
import com.denizetkar.walkietalkieapp.network.DiscoveredGroup
import com.denizetkar.walkietalkieapp.network.TransportDataType
import com.denizetkar.walkietalkieapp.network.TransportNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val scope: CoroutineScope,
    private val ownNodeId: UInt
) {
    // --- State ---
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state = _state.asStateFlow()

    // Protection against parallel state transitions
    private val stateMutex = Mutex()

    private val _discoveredGroups = MutableStateFlow<List<DiscoveredGroup>>(emptyList())
    val discoveredGroups = _discoveredGroups.asStateFlow()
    private val foundGroupsMap = ConcurrentHashMap<String, DiscoveredGroup>()

    // --- Logic & Subsystems ---
    private val topology = TopologyEngine(ownNodeId)
    private val seenPackets = ConcurrentHashMap<Int, Long>()
    private val lastHeardFrom = ConcurrentHashMap<UInt, Long>()

    // Cache to prevent spamming the driver with identical advertising requests
    private var lastAdvertisingConfig: AdvertisingConfig? = null

    // Define Transport Callback (Rust -> Kotlin -> BLE)
    private val packetTransport = object : PacketTransport {
        override fun sendPacket(data: ByteArray) {
            broadcastPayload(data, TransportDataType.AUDIO)
        }
    }

    // Voice Manager (Single Source of Truth for Audio)
    private val voiceManager = VoiceManager(context, packetTransport, ownNodeId, scope)

    // Exposed State for UI
    val availableInputs = voiceManager.availableInputs
    val availableOutputs = voiceManager.availableOutputs
    val selectedInputId = voiceManager.selectedInputId
    val selectedOutputId = voiceManager.selectedOutputId

    // --- Jobs ---
    private var heartbeatJob: Job? = null
    private var cleanupJob: Job? = null
    private var packetCleanupJob: Job? = null
    private var livenessJob: Job? = null

    init {
        try { initLogger() } catch (_: Exception) {}
        scope.launch { driver.events.collect { handleDriverEvent(it) } }
        scope.launch { driver.connectedPeers.collect { handlePeerListChange(it) } }
        startPacketCleanup()
    }

    // --- Public API ---

    fun checkSystemRequirements(): Result<Unit> = driver.validateCapabilities()

    fun startGroupScan() {
        Log.d("MeshController", "Action: Start Group Scan")
        scope.launch { transitionTo(EngineState.Discovering) }
    }

    fun stopGroupScan() {
        scope.launch {
            if (_state.value is EngineState.Discovering) {
                transitionTo(EngineState.Idle)
            }
        }
    }

    fun createGroup(name: String, code: String) {
        Log.d("MeshController", "Action: Create Group $name")
        driver.setCredentials(code, ownNodeId)
        scope.launch { transitionTo(EngineState.RadioActive(name, 0)) }
    }

    fun joinGroup(name: String, code: String) {
        Log.d("MeshController", "Action: Join Group $name")
        driver.setCredentials(code, ownNodeId)
        scope.launch { transitionTo(EngineState.Joining(name)) }
    }

    fun leave() {
        Log.d("MeshController", "Action: Leave")
        scope.launch { transitionTo(EngineState.Idle) }
    }

    fun startTalking() = voiceManager.setMicrophoneEnabled(true)
    fun stopTalking() = voiceManager.setMicrophoneEnabled(false)

    fun setInputDevice(id: Int) = voiceManager.setInputDevice(id)
    fun setOutputDevice(id: Int) = voiceManager.setOutputDevice(id)

    // --- State Machine ---

    private suspend fun transitionTo(newState: EngineState): Unit = stateMutex.withLock {
        val oldState = _state.value
        if (oldState == newState) return

        _state.value = newState
        Log.i("MeshController", "State Change: $oldState -> $newState")

        // Reset advertising cache on state change to ensure fresh config is applied
        lastAdvertisingConfig = null

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

    /**
     * Hard Destroy. Called from Service.onDestroy().
     * This method must be synchronous (non-suspending) and fast.
     */
    fun destroy() {
        Log.d("MeshController", "CMD: Destroy (Hard)")

        // 1. Cancel Local Jobs immediately.
        heartbeatJob?.cancel()
        livenessJob?.cancel()
        cleanupJob?.cancel()
        packetCleanupJob?.cancel()

        // 2. Clean up VoiceManager
        voiceManager.destroy()

        // 3. Clean up BLE Driver
        driver.destroy()
    }

    // --- Radio Logic ---

    private fun startRadioLogic(groupName: String) {
        // 1. Audio Setup
        voiceManager.start()

        // 2. Start Concurrent Background Jobs
        livenessJob = scope.launch { runLivenessCheck() }
        heartbeatJob = scope.launch { runHeartbeatLoop(groupName) }

        // 3. Start Scanning (Always on to receive Heartbeats)
        driver.startScanning()

        // 4. Initial Advertising
        refreshAdvertising(groupName)
    }

    /**
     * Soft Stop. Called during State Transitions.
     * Suspends to allow jobs to finish gracefully.
     */
    private suspend fun stopRadioLogic() {
        // 1. Cancel Background Jobs
        heartbeatJob?.cancel()
        livenessJob?.cancel()
        joinAll(heartbeatJob, livenessJob)

        // 2. Teardown Audio
        voiceManager.stop()

        // 3. Teardown Hardware
        driver.stop()
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
            if (topology.checkTimeout()) refreshAdvertising(groupName)

            // generateHeartbeat returns the PAYLOAD (NetID, Seq, Hops)
            val payload = topology.generateHeartbeat()
            if (payload != null) {
                broadcastPayload(payload, TransportDataType.CONTROL)
            }
            delay(Config.HEARTBEAT_INTERVAL)
        }
    }

    private fun refreshAdvertising(groupName: String) {
        val topo = topology.getCurrentState()
        val isAvailable = driver.connectedPeers.value.size < Config.MAX_PEERS
        val config = AdvertisingConfig(groupName, ownNodeId, topo.currentNetworkId, topo.hopsToRoot, isAvailable)

        // OPTIMIZATION: If the topology hasn't changed, don't wake up the radio.
        // This filters out the 1Hz heartbeat updates where only the Sequence Number changed.
        if (config == lastAdvertisingConfig) {
            return
        }
        lastAdvertisingConfig = config

        driver.startAdvertising(config)
    }

    private fun broadcastPayload(payload: ByteArray, type: TransportDataType) {
        markPacketAsSeen(payload)
        scope.launch { driver.broadcast(payload, type) }
    }

    // --- Cleanup Helpers ---

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

    // --- Event Handling ---

    private suspend fun handleDriverEvent(event: BleDriverEvent) {
        when (event) {
            is BleDriverEvent.PeerDiscovered -> handleDiscovery(event.node)
            is BleDriverEvent.PeerConnected -> lastHeardFrom[event.nodeId] = System.currentTimeMillis()
            is BleDriverEvent.PeerDisconnected -> lastHeardFrom.remove(event.nodeId)
            is BleDriverEvent.DataReceived -> handleData(event)
            else -> {}
        }
    }

    private suspend fun handlePeerListChange(peers: Set<UInt>) {
        // STRICT LOCKING: Acquire lock immediately to avoid race conditions
        stateMutex.withLock {
            val s = _state.value
            val count = peers.size
            when (s) {
                is EngineState.RadioActive -> {
                    if (s.peerCount != count) {
                        _state.value = s.copy(peerCount = count)
                        refreshAdvertising(s.groupName)
                    }
                }
                is EngineState.Joining -> {
                    if (count > 0) {
                        // We are inside the lock, so we can modify state directly
                        _state.value = EngineState.RadioActive(s.groupName, count)
                        Log.i("MeshController", "State Change: Joining -> RadioActive")

                        // We need to start the Radio Logic (Heartbeats, etc)
                        startRadioLogic(s.groupName)
                    }
                }
                else -> {}
            }
        }
    }

    private suspend fun handleDiscovery(node: TransportNode) {
        val s = _state.value

        if (s is EngineState.Discovering) {
            val existing = foundGroupsMap[node.name]
            val rssi = max(node.rssi, existing?.highestRssi ?: Config.WORST_RSSI)
            foundGroupsMap[node.name] = DiscoveredGroup(node.name, rssi)
            _discoveredGroups.value = foundGroupsMap.values.sortedByDescending { it.highestRssi }
            return
        }
        if (s is EngineState.Joining && node.name == s.groupName) {
            connectSafely(node)
            return
        }
        if (s is EngineState.RadioActive && node.name == s.groupName) {
            val topo = topology.getCurrentState()
            val currentPeers = driver.connectedPeers.value

            // Check existing connection (simple optimization)
            // BUT: We rely on Driver to deduplicate if we are wrong
            if (currentPeers.contains(node.nodeId)) return

            var shouldConnect = false
            if (node.networkId > topo.currentNetworkId) {
                shouldConnect = true
            } else if (currentPeers.size < Config.TARGET_PEERS) {
                if (node.isAvailable || node.networkId < topo.currentNetworkId) shouldConnect = true
            } else if (currentPeers.size < Config.MAX_PEERS) {
                if (node.networkId < topo.currentNetworkId) shouldConnect = true
            }

            if (shouldConnect) connectSafely(node)
        }
    }

    private fun connectSafely(node: TransportNode) {
        scope.launch { driver.connectTo(node.id, node.nodeId) }
    }

    private fun handleData(event: BleDriverEvent.DataReceived) {
        lastHeardFrom[event.fromNodeId] = System.currentTimeMillis()
        val packetHash = event.data.contentHashCode()
        if (seenPackets.containsKey(packetHash)) return
        seenPackets[packetHash] = System.currentTimeMillis()

        if (event.type == TransportDataType.CONTROL) {
            // FIX: The Driver/Handler has already stripped the [Header][Type] bytes.
            // 'event.data' is the PAYLOAD.
            val (netId, seq, hops) = PacketUtils.parseHeartbeatPayload(event.data) ?: return
            if (topology.onHeartbeatReceived(netId, seq, hops)) {
                val s = _state.value
                if (s is EngineState.RadioActive) refreshAdvertising(s.groupName)

                // RELAY LOGIC
                val newPayload = PacketUtils.createHeartbeatPayload(netId, seq, hops + 1)
                broadcastPayload(newPayload, TransportDataType.CONTROL)
            }
        } else {
            voiceManager.processIncomingPacket(event.data)
            scope.launch { driver.broadcast(event.data, TransportDataType.AUDIO) }
        }
    }

    private suspend fun joinAll(vararg jobs: Job?) {
        jobs.filterNotNull().joinAll()
    }
}