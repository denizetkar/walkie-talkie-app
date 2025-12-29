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

    private val _connectedNodeIds = MutableStateFlow<Set<Int>>(emptySet())
    val peerCount = _connectedNodeIds.map { it.size }.stateIn(scope, SharingStarted.Lazily, 0)

    private val _discoveredGroups = MutableStateFlow<List<DiscoveredGroup>>(emptyList())
    val discoveredGroups = _discoveredGroups.asStateFlow()
    private val foundGroupsMap = ConcurrentHashMap<String, DiscoveredGroup>()
    private var groupCleanupJob: Job? = null

    val isScanning = transport.isScanning

    private var isMeshMode = false
    private var currentGroupName: String? = null
    private val seenPackets = ConcurrentHashMap<Int, Long>()
    private var startAdvertisementJob: Job? = null
    private var scanJob: Job? = null

    init {
        scope.launch {
            transport.events.collect { event -> handleTransportEvent(event) }
        }

        scope.launch {
            peerCount.collect { count ->
                if (isMeshMode) updateScanStrategy(count)
            }
        }

        startPacketCleanupLoop()
    }

    // ===========================================================================
    // MODE 1: GROUP SCANNING
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
        groupCleanupJob = null
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
    // MODE 2: MESH NETWORKING
    // ===========================================================================

    fun startMesh(groupName: String, accessCode: String, asHost: Boolean) {
        isMeshMode = true
        currentGroupName = groupName

        stopGroupScan()
        transport.setCredentials(accessCode, ownNodeId)
        val peerThreshold = if (asHost) 0 else 1
        startAdvertisementJob = scope.launch {
            peerCount.first { it >= peerThreshold }
            transport.startAdvertising(groupName)
        }

        updateScanStrategy(peerCount.value)
    }

    fun stopMesh() {
        isMeshMode = false
        startAdvertisementJob?.cancel()
        scanJob?.cancel()

        _connectedNodeIds.value = emptySet()
        scope.launch {
            transport.stopDiscovery()
            transport.stopAdvertising()
            transport.disconnectAll()
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
                _connectedNodeIds.update { it + event.nodeId }
            }
            is TransportEvent.ConnectionLost -> {
                Log.i("MeshManager", "Peer Lost: ${event.nodeId}")
                _connectedNodeIds.update { it - event.nodeId }
            }
            is TransportEvent.DataReceived -> handleDataReceived(event.fromNodeId, event.data, event.type)
            is TransportEvent.Error -> Log.e("MeshManager", "Error: ${event.message}")
        }
    }

    private fun handleNodeDiscovered(node: TransportNode) {
        if (!isMeshMode) {
            if (node.name == null) return

            val existing = foundGroupsMap[node.name]
            val rssi = max(node.extraInfo["rssi"] as? Int ?: Config.WORST_RSSI, existing?.highestRssi ?: Config.WORST_RSSI)
            foundGroupsMap[node.name] = DiscoveredGroup(node.name, rssi)
            _discoveredGroups.value = foundGroupsMap.values.toList().sortedByDescending { it.highestRssi }
            return
        }

        if (node.name != currentGroupName) return

        val nodeId = node.extraInfo["nodeId"] as? Int ?: return
        if (nodeId == ownNodeId) return
        if (_connectedNodeIds.value.contains(nodeId)) return
        if (_connectedNodeIds.value.size >= Config.MAX_PEERS) return

        val isAvailable = node.extraInfo["available"] as? Boolean ?: false
        if (_connectedNodeIds.value.size >= Config.TARGET_PEERS && !isAvailable) return

        Log.d("MeshManager", "Found Candidate: $nodeId. Connecting...")
        scope.launch { transport.connect(node.id, nodeId) }
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

    private fun handleDataReceived(fromNodeId: Int, data: ByteArray, type: TransportDataType) {
        val packetHash = data.contentHashCode()
        if (seenPackets.containsKey(packetHash)) return
        seenPackets[packetHash] = System.currentTimeMillis()

        if (type == TransportDataType.AUDIO) {
            // Play Audio
        } else {
            // Handle Control Message
        }

        scope.launch { transport.sendToAll(data, type, excludeNodeId = fromNodeId) }
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
}