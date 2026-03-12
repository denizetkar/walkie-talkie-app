package com.denizetkar.walkietalkieapp.domain

import com.denizetkar.walkietalkieapp.AudioDeviceUi

// ===========================================================================
// STATE (The Single Source of Truth)
// ===========================================================================

/**
 * Represents the complete state of the application at any given point in time.
 * Drivers and UI observe this to know what to do.
 */
data class AppState(
    // --- Identity ---
    val myself: PeerId,

    /**
     * The active session context.
     * - If NULL: The app is "Idle" (not in a group).
     * - If SET: The app is either "Joining" or "Active" in a group.
     */
    val session: SessionContext? = null,

    // --- Topology & Network ---
    /**
     * The list of verified, authenticated peers we are currently connected to.
     * This acts as the "Roster" for the UI.
     */
    val connectedPeers: Set<PeerId> = emptySet(),

    /**
     * The list of groups discovered via BLE Advertising.
     * Used solely for the "Join Group" screen.
     */
    val discoveredGroups: List<DiscoveredGroup> = emptyList(),

    /**
     * Our current view of the mesh network hierarchy.
     * This determines our advertising payload (Network ID / Hops).
     */
    val network: NetworkTopology = NetworkTopology.Standalone(myself),

    // --- Activity Flags ---
    /**
     * If true, the BLE Driver should be actively scanning.
     * This is derived from:
     * 1. User is on the Join Screen (Browsing).
     * 2. User is in a Group (Maintenance Scanning to find neighbors).
     */
    val isBrowsing: Boolean = false,

    /**
     * Holds the error message if a Join attempt fails (Timeout/Rejected).
     * The UI observes this to show a dialog.
     */
    val joinError: String? = null,

    // --- Audio State ---
    /**
     * The Software Gate for the Microphone.
     * True = PTT is pressed (Unmuted).
     * False = PTT released (Muted).
     */
    val isMicEnabled: Boolean = false,

    // Hardware list (Outputs only, since OS auto-matches the Input)
    val availableAudioDevices: List<AudioDeviceUi> = emptyList(),

    // Currently selected route (0 = Default)
    val selectedAudioDevice: Int = 0,

    // --- Hardware State ---
    val isBluetoothEnabled: Boolean = true,
)

data class SessionContext(
    val groupName: String,
    val accessCode: String,
    // Logic: Track if we are the Host (Create) or Client (Join)
    val isJoinAttempt: Boolean,
    // Logic: Track when the session started for Timeouts
    val startTime: Long = System.currentTimeMillis()
)

data class DiscoveredGroup(
    val id: String, // MAC Address
    val name: String,
    val rssi: Int,
    val netId: UInt,
    val nodeId: PeerId,
    // Timestamp used to prune old groups from the UI list
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Defines our place in the Mesh Hierarchy.
 * We use a Root-Based Convergence algorithm to merge islands.
 */
sealed class NetworkTopology {
    abstract val rootId: PeerId
    abstract val hops: Int

    /**
     * We are the Root of our own small world (or just alone).
     */
    data class Standalone(override val rootId: PeerId) : NetworkTopology() {
        override val hops: Int = 0
    }

    /**
     * We have found a "Better Root" (higher Node ID) and are following them.
     * @param rootSeq The sequence number of the last heartbeat received from Root.
     */
    data class Mesh(override val rootId: PeerId, override val hops: Int, val rootSeq: Int) : NetworkTopology()
}

// Type Alias for clarity (it's just a UInt, but represents a unique Node ID)
typealias PeerId = UInt

// ===========================================================================
// ACTIONS (Inputs to the Core)
// ===========================================================================

/**
 * All events that change state must flow through here.
 */
sealed class Action {
    // --- User Intents (UI -> Core) ---
    data class CreateGroup(val name: String, val code: String) : Action()
    data class JoinGroup(val name: String, val code: String) : Action()
    data class LeaveGroup(val reason: String = "User Request") : Action()

    // Explicit scanning control for the "Join" screen
    data object StartScanning : Action()
    data object StopScanning : Action()

    // --- Audio Control ---
    data class SetMic(val enabled: Boolean) : Action()
    data class SetAudioDevice(val id: Int) : Action()

    // --- Network Events (Driver -> Core) ---
    data class PeerConnected(val peerId: PeerId) : Action()
    data class PeerDisconnected(val peerId: PeerId) : Action()
    data class JoinGroupFailed(val reason: String) : Action()
    data object AckJoinError : Action()
    data class ScanFailed(val reason: String) : Action()
    data class BluetoothStateChanged(val enabled: Boolean) : Action()

    /**
     * A raw packet received from the wire.
     * @param isControl True if received via the Reliable GATT Characteristic.
     */
    data class PacketReceived(val data: ByteArray, val source: PeerId? = null, val isControl: Boolean) : Action() {
        override fun equals(other: Any?) =
            other === this || (other is PacketReceived
                    && data.contentEquals(other.data)
                    && source == other.source
                    && isControl == other.isControl)

        override fun hashCode(): Int = 31 * data.contentHashCode() + (source?.hashCode() ?: 0) + isControl.hashCode()
    }

    data class AdvertisementSeen(val group: DiscoveredGroup) : Action()

    // --- System Events (System/Drivers -> Core) ---
    // Periodic tickers for maintenance tasks
    data class HeartbeatTick(val timeMs: Long) : Action() // ~1Hz
    data class CleanupTick(val timeMs: Long) : Action()   // ~2-5Hz

    /**
     * Audio chunk captured from the microphone (encoded Opus).
     * This enters the system as an Action so the Core can decide how/where to route it.
     */
    data class AudioDataCaptured(val data: ByteArray) : Action() {
        override fun equals(other: Any?) =
            other === this || (other is AudioDataCaptured
                    && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()
    }

    /** The VoiceManager reports a change in available hardware. */
    data class AudioDevicesUpdated(val devices: List<AudioDeviceUi>) : Action()
}

// ===========================================================================
// EFFECTS (Outputs from the Core)
// ===========================================================================

/**
 * One-off commands generated by the Core.
 * These are "fire-and-forget" events (e.g., "Send Packet", "Show Toast").
 */
sealed class Effect {
    // --- Network Commands ---
    /**
     * @param isControl If TRUE, this packet MUST be sent reliably (GATT Write Request / Indication).
     *                  If FALSE, it is sent unreliably (GATT Write Command / Notification).
     * @param excludedSource If non-null, the driver will NOT send the packet to this PeerId.
     *                       Used for Split Horizon (don't echo data back to sender).
     */
    data class Transmit(
        val data: ByteArray,
        val strategy: TransmissionStrategy,
        val isControl: Boolean,
        val excludedSource: PeerId? = null
    ) : Effect() {
        override fun equals(other: Any?) =
            other === this || (other is Transmit
                    && data.contentEquals(other.data)
                    && strategy == other.strategy
                    && isControl == other.isControl
                    && excludedSource == other.excludedSource)

        override fun hashCode(): Int = 31 * data.contentHashCode() + strategy.hashCode() + isControl.hashCode() + (excludedSource?.hashCode() ?: 0)
    }

    /**
     * Intent to connect to a specific node (Idempotent).
     * @param originNodeId The identity WE should assume for this connection attempt.
     */
    data class ConnectTo(
        val targetId: String,
        val targetNodeId: PeerId,
        val originNodeId: PeerId,
        val accessCode: String,
    ) : Effect()

    data class Disconnect(val peerId: PeerId) : Effect()

    // --- Hardware Commands ---
    /**
     * Received audio data that should be decoded and played.
     */
    data class RenderAudio(val data: ByteArray) : Effect() {
        override fun equals(other: Any?) =
            other === this || (other is RenderAudio
                    && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()
    }

    // --- UI Feedback ---
    data class ShowToast(val message: String) : Effect()
}

enum class TransmissionStrategy {
    FLOOD,      // Send to ALL connected peers (Relay)
}