package com.denizetkar.walkietalkieapp.network

import android.bluetooth.BluetoothDevice
import com.denizetkar.walkietalkieapp.domain.PeerId
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// --- Low Level DTOs (Bluetooth Layer) ---

data class TransportNode(
    val id: String,           // MAC Address
    val name: String,
    val rssi: Int,
    val nodeId: UInt,
    val networkId: UInt,
    val hopsToRoot: Int,
    val isAvailable: Boolean
)

data class AdvertisingConfig(
    val groupName: String,
    val ownNodeId: UInt,
    val networkId: UInt,
    val hopsToRoot: Int,
    val isAvailable: Boolean
)

@JvmInline
value class TransportAddress(val address: String) {
    override fun toString(): String = address

    companion object {
        fun from(raw: String): TransportAddress {
            return TransportAddress(raw.uppercase())
        }
    }
}

// --- Driver Configuration (Derived from AppState) ---
data class DriverConfig(
    val isBluetoothEnabled: Boolean,
    val isAdvertising: Boolean,
    val isScanning: Boolean,
    val groupName: String,
    val ownNodeId: PeerId,
    val netId: UInt,
    val hops: Int,
    val isFull: Boolean
)

// --- Internal Driver Models ---

data class OutgoingPacket(val data: ByteArray, val isControl: Boolean)

data class PeerSession(
    val job: Job,
    val channel: SendChannel<OutgoingPacket>,
    val type: TransportType,
    val address: TransportAddress,
    val connectionId: UUID,
    val isCollisionHandoff: AtomicBoolean = AtomicBoolean(false)
)

/**
 * Immutable Registry for O(1) lookups.
 */
data class PeerRegistry(
    val sessions: Map<PeerId, PeerSession> = emptyMap(),
    val addressIndex: Map<TransportAddress, PeerId> = emptyMap()
) {
    fun put(id: PeerId, session: PeerSession): PeerRegistry {
        return PeerRegistry(
            sessions + (id to session),
            addressIndex + (session.address to id)
        )
    }

    fun remove(id: PeerId): PeerRegistry {
        val session = sessions[id] ?: return this
        return PeerRegistry(
            sessions - id,
            addressIndex - session.address
        )
    }
}

enum class TransportDataType {
    CONTROL, // Reliable
    AUDIO    // Fast
}

enum class TransportType {
    OUTGOING, // Client
    INCOMING  // Server
}

// --- Failure Types ---

sealed interface ConnectionFailure {
    val message: String
    data class Io(override val message: String) : ConnectionFailure
    data class Timeout(override val message: String) : ConnectionFailure
    data class AuthRejected(override val message: String) : ConnectionFailure
}

// --- Events ---

sealed class ClientEvent {
    data class Connected(val device: BluetoothDevice) : ClientEvent()
    data class Authenticated(val device: BluetoothDevice) : ClientEvent()
    data class Disconnected(val device: BluetoothDevice) : ClientEvent()
    data class MessageReceived(val device: BluetoothDevice, val data: ByteArray, val type: TransportDataType) : ClientEvent()
    data class Error(val device: BluetoothDevice, val reason: ConnectionFailure) : ClientEvent()
}

sealed class ServerEvent {
    data class ClientConnected(val device: BluetoothDevice) : ServerEvent()
    data class ClientAuthenticated(val device: BluetoothDevice, val nodeId: PeerId) : ServerEvent()
    data class ClientDisconnected(val device: BluetoothDevice) : ServerEvent()
    data class MessageReceived(val device: BluetoothDevice, val data: ByteArray, val type: TransportDataType) : ServerEvent()
    data class Error(val device: BluetoothDevice, val reason: ConnectionFailure) : ServerEvent()
}