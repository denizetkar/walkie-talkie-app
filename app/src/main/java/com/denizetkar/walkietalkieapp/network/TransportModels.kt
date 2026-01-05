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

/**
 * A generic interface for any way of sending data to a peer.
 */
interface TransportStrategy {
    val type: TransportType
    val address: String

    suspend fun send(data: ByteArray, type: TransportDataType)

    /**
     * Closes the underlying connection AND cancels any associated coroutines/jobs.
     */
    fun disconnect()
}

/**
 * The Single Source of Truth for a connected peer.
 * Holds exactly ONE active transport.
 * Thread-safe.
 */
class PeerConnection(val nodeId: Int) {

    @Volatile
    var transport: TransportStrategy? = null
        private set

    val address: String
        get() = transport?.address ?: "Unknown"

    @Synchronized
    fun setStrategy(strategy: TransportStrategy) {
        // If we are replacing an existing one, ensure the old one is closed
        if (transport != null && transport !== strategy) {
            transport?.disconnect()
        }
        transport = strategy
    }

    @Synchronized
    fun clearStrategy() {
        transport = null
    }

    fun isActive(): Boolean = transport != null

    suspend fun send(data: ByteArray, type: TransportDataType) {
        transport?.send(data, type)
    }

    @Synchronized
    fun disconnect() {
        transport?.disconnect()
        transport = null
    }
}