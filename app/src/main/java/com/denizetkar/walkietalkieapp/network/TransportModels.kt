package com.denizetkar.walkietalkieapp.network

// --- Configuration ---

data class AdvertisingConfig(
    val groupName: String,
    val ownNodeId: UInt,
    val networkId: UInt,
    val hopsToRoot: Int,
    val isAvailable: Boolean
)

// --- Data Types ---

enum class TransportDataType {
    CONTROL, // Reliable, for Handshakes/Routing (Write with Response)
    AUDIO    // Fast, for Voice (Write without Response)
}

enum class TransportType {
    OUTGOING, // We dialed them (Client)
    INCOMING  // They dialed us (Server)
}

/**
 * Represents a raw node discovered via BLE.
 */
data class TransportNode(
    val id: String,           // MAC Address
    val name: String,         // Group Name
    val rssi: Int,
    val nodeId: UInt,          // The peer's random ID
    val networkId: UInt,       // The Root they follow
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
    data class PeerConnected(val nodeId: UInt) : BleDriverEvent
    data class PeerDisconnected(val nodeId: UInt) : BleDriverEvent
    data class DataReceived(val fromNodeId: UInt, val data: ByteArray, val type: TransportDataType) : BleDriverEvent
    data class Error(val message: String) : BleDriverEvent
}

/**
 * A generic interface for any way of sending data to a peer.
 */
interface TransportStrategy {
    val type: TransportType
    val address: String

    suspend fun send(data: ByteArray, type: TransportDataType)

    /**
     * Closes the underlying connection AND cancels any associated coroutines/jobs.
     * Polite disconnect.
     */
    fun disconnect()

    /**
     * Hard close. Releases resources immediately.
     */
    fun close()
}