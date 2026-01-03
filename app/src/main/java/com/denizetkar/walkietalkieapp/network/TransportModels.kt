package com.denizetkar.walkietalkieapp.network

// --- Configuration ---

data class AdvertisingConfig(
    val groupName: String,
    val ownNodeId: Int,
    val networkId: Int,
    val hopsToRoot: Int,
    val isAvailable: Boolean
)

// --- Data Types ---

enum class TransportDataType {
    CONTROL, // Reliable, for Handshakes/Routing (Write with Response)
    AUDIO    // Fast, for Voice (Write without Response)
}

/**
 * Represents a raw node discovered via BLE.
 */
data class TransportNode(
    val id: String,           // MAC Address
    val name: String,         // Group Name
    val rssi: Int,
    val nodeId: Int,          // The peer's random ID
    val networkId: Int,       // The Root they follow
    val hopsToRoot: Int,
    val isAvailable: Boolean
)

/**
 * Represents a processed Group for the UI (Join Screen).
 */
data class DiscoveredGroup(
    val name: String,
    val highestRssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

// --- Driver Events ---

sealed interface BleDriverEvent {
    data class PeerDiscovered(val node: TransportNode) : BleDriverEvent
    data class PeerConnected(val nodeId: Int) : BleDriverEvent
    data class PeerDisconnected(val nodeId: Int) : BleDriverEvent
    data class DataReceived(val fromNodeId: Int, val data: ByteArray, val type: TransportDataType) : BleDriverEvent
    data class Error(val message: String) : BleDriverEvent
}