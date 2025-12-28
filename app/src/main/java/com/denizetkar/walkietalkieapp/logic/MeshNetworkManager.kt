package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.bluetooth.BleTransport
import com.denizetkar.walkietalkieapp.network.NetworkTransport
import com.denizetkar.walkietalkieapp.network.TransportEvent
import com.denizetkar.walkietalkieapp.network.TransportNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
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

    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers = _connectedPeers.asStateFlow()

    val peerCount: StateFlow<Int> = _connectedPeers
        .map { it.size }
        .stateIn(scope, SharingStarted.Lazily, 0)

    private val _discoveredGroups = MutableStateFlow<List<DiscoveredGroup>>(emptyList())
    val discoveredGroups = _discoveredGroups.asStateFlow()
    private val foundGroupsMap = ConcurrentHashMap<String, DiscoveredGroup>()

    val isScanning = transport.isScanning

    private var isMeshMode = false
    private var currentGroupName: String? = null
    private val seenPackets = ConcurrentHashMap<Int, Long>()
    private var scanJob: Job? = null

    init {
        scope.launch {
            transport.events.collect { event -> handleTransportEvent(event) }
        }

        // Only run the reactive scan strategy if we are in Mesh Mode
        scope.launch {
            peerCount.collect { count ->
                if (isMeshMode) updateScanStrategy(count)
            }
        }

        startPacketCleanupLoop()
    }

    // ===========================================================================
    // MODE 1: GROUP SCANNING (Join Screen)
    // ===========================================================================

    fun startGroupScan() {
        isMeshMode = false
        currentGroupName = null
        scanJob?.cancel() // Stop any mesh logic

        foundGroupsMap.clear()
        _discoveredGroups.value = emptyList()

        scope.launch { transport.startDiscovery() }
    }

    fun stopGroupScan() {
        scope.launch { transport.stopDiscovery() }
    }

    // ===========================================================================
    // MODE 2: MESH NETWORKING (Radio Screen)
    // ===========================================================================

    fun startMesh(groupName: String, accessCode: String) {
        isMeshMode = true
        currentGroupName = groupName

        stopGroupScan()
        transport.setCredentials(accessCode, ownNodeId)
        scope.launch {
            transport.startAdvertising(groupName)
        }

        updateScanStrategy(peerCount.value)
    }

    fun stopMesh() {
        isMeshMode = false
        scanJob?.cancel()

        val peersToDisconnect = _connectedPeers.value.toSet()
        _connectedPeers.value = emptySet()
        scope.launch {
            transport.stopDiscovery()
            transport.stopAdvertising()
            peersToDisconnect.forEach { transport.disconnect(it) }
        }
    }

    // ===========================================================================
    // EVENT HANDLING
    // ===========================================================================

    private fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.NodeDiscovered -> handleNodeDiscovered(event.node)
            is TransportEvent.ConnectionEstablished -> {
                Log.i("MeshManager", "Peer Connected: ${event.nodeId}")
                _connectedPeers.update { it + event.nodeId }
            }
            is TransportEvent.ConnectionLost -> {
                Log.i("MeshManager", "Peer Lost: ${event.nodeId}")
                _connectedPeers.update { it - event.nodeId }
            }
            is TransportEvent.DataReceived -> handleDataReceived(event.fromNodeId, event.data)
            is TransportEvent.Error -> Log.e("MeshManager", "Error: ${event.message}")
        }
    }

    private fun handleNodeDiscovered(node: TransportNode) {
        if (!isMeshMode) {
            if (node.name == null) return

            val rssi = node.extraInfo["rssi"] as? Int ?: -100
            val existing = foundGroupsMap[node.name]

            if (existing == null || rssi > existing.highestRssi) {
                foundGroupsMap[node.name] = DiscoveredGroup(node.name, rssi)
                _discoveredGroups.value = foundGroupsMap.values.toList().sortedByDescending { it.highestRssi }
            }
            return
        }

        if (node.name != currentGroupName) return
        val currentPeers = _connectedPeers.value
        if (currentPeers.contains(node.id)) return
        if (currentPeers.size >= Config.MAX_PEERS) return

        val nodeId = node.extraInfo["nodeId"] as? Int ?: return
        val isAvailable = node.extraInfo["available"] as? Boolean ?: false

        if (currentPeers.size >= Config.TARGET_PEERS && !isAvailable) return

        Log.d("MeshManager", "Found Candidate: $nodeId. Connecting...")
        scope.launch { transport.connect(node.id) }
    }

    // ===========================================================================
    // REACTIVE SCAN LOGIC
    // ===========================================================================

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

    private fun handleDataReceived(fromNodeId: String, data: ByteArray) {
        val packetHash = data.contentHashCode()
        if (seenPackets.containsKey(packetHash)) return
        seenPackets[packetHash] = System.currentTimeMillis()

        // Process Data (Play Audio, etc.)
        // For now, we just expose it to the UI/Audio Engine via a callback or flow.
        // onAudioPacketReceived(data)

        scope.launch { transport.broadcast(data) }
    }

    private fun startPacketCleanupLoop() {
        scope.launch {
            while (isActive) {
                delay(Config.CLEANUP_PERIOD)
                val now = System.currentTimeMillis()
                seenPackets.entries.removeIf { (now - it.value) > Config.TIMEOUT }
            }
        }
    }
}