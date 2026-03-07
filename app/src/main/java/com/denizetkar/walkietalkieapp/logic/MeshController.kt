package com.denizetkar.walkietalkieapp.logic

import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.domain.*
import com.denizetkar.walkietalkieapp.protocol.Packet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.random.nextUInt

/**
 * The Brain of the Mesh.
 *
 * ARCHITECTURE (Actor Model):
 * This class isolates state mutations to a single sequential loop.
 * - INPUT:  [dispatch] enqueues [Action]s into a non-blocking Channel.
 * - LOGIC:  [processingLoop] consumes actions one by one.
 * - STATE:  [AppState] is the Single Source of Truth, updated only inside the loop.
 * - OUTPUT: [Effect]s are emitted to drivers/UI.
 */
class MeshController(
    // Scope is required to launch the Actor loop. It should match the Service lifecycle.
    scope: CoroutineScope,
    // Dispatcher for the actor loop. Defaults to Default (CPU-bound), but can be swapped for tests.
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    // --- State (Single Source of Truth) ---
    // Start with a random ID, but it will be rotated on every session start.
    private val _state = MutableStateFlow(AppState(myself = Random.nextUInt()))
    val state: StateFlow<AppState> = _state.asStateFlow()

    // --- Effects (Outputs to the World) ---
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 64)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    // --- Actor Input Channel ---
    // UNLIMITED capacity ensures we never block the sender (UI/Driver/Audio Threads).
    // The consumer loop is fast enough (CPU bound) to drain this instantly.
    private val actionChannel = Channel<Action>(Channel.UNLIMITED)

    // --- Internal Logic State (Confined to processingLoop) ---
    /**
     * Deduplication Cache. Maps PacketHash -> TimestampMs.
     * Used to ignore packets we have already processed or relayed.
     */
    private val packetCache = mutableMapOf<Int, Long>()

    /**
     * Liveness Tracker. Maps PeerId -> LastHeardTimestampMs.
     * If a peer stays silent too long, we disconnect them.
     */
    private val peerLiveness = mutableMapOf<PeerId, Long>()

    // Heartbeat State
    private var lastHeartbeatSent = 0L
    private var lastRootSeen = 0L

    // The internal clock. Tests can fast-forward this.
    // Defaults to system time for safety, but is overwritten by Ticks immediately.
    private var internalClockMs = System.currentTimeMillis()

    init {
        // START THE ACTOR LOOP
        // We use a dedicated Dispatcher for Logic to ensure it doesn't get starved by UI or blocking IO.
        scope.launch(dispatcher) {
            processingLoop()
        }
    }

    /**
     * Public API: Enqueues an action for processing.
     * This is non-blocking and thread-safe.
     */
    fun dispatch(action: Action) {
        val result = actionChannel.trySend(action)
        if (result.isFailure) {
            Log.e("MeshController", "Failed to enqueue action: ${action.javaClass.simpleName}")
        }
    }

    /**
     * The Main Event Loop.
     * Consumes actions sequentially. No Mutex needed because this block
     * is the ONLY place where internal state is mutated.
     */
    private suspend fun processingLoop() {
        for (action in actionChannel) {
            val currentState = _state.value

            try {
                when (action) {
                    // =================================================================
                    // USER INTENTS (UI -> Core)
                    // =================================================================

                    is Action.StartScanning -> {
                        Log.d("MeshController", "CMD: Start Scanning")
                        _state.update { it.copy(isBrowsing = true) }
                    }
                    is Action.StopScanning -> {
                        Log.d("MeshController", "CMD: Stop Scanning")
                        _state.update { it.copy(isBrowsing = false) }
                    }

                    is Action.CreateGroup -> {
                        Log.i("MeshController", "USER ACTION: Create Group '${action.name}'")
                        // ROTATE ID: To reset receiver Jitter Buffers
                        val newNodeId = Random.nextUInt()
                        // We are hosting, so isJoinAttempt = false (No timeout)
                        val session = SessionContext(action.name, action.code, isJoinAttempt = false, startTime = internalClockMs)
                        _state.update {
                            it.copy(
                                myself = newNodeId,
                                session = session,
                                // When creating, I am the Root of a new mesh.
                                network = NetworkTopology.Standalone(newNodeId),
                                // Implicitly stop browsing when live
                                isBrowsing = false
                            )
                        }
                        resetInternalTimers()
                    }

                    is Action.JoinGroup -> {
                        Log.i("MeshController", "USER ACTION: Join Group '${action.name}'")
                        // ROTATE ID: To reset receiver Jitter Buffers
                        val newNodeId = Random.nextUInt()
                        // We are joining, so isJoinAttempt = true (Global Timeout active)
                        val session = SessionContext(action.name, action.code, isJoinAttempt = true, startTime = internalClockMs)
                        _state.update {
                            it.copy(
                                myself = newNodeId,
                                session = session,
                                // When joining, I assume Standalone until I hear a Heartbeat from the group.
                                network = NetworkTopology.Standalone(newNodeId),
                                isBrowsing = false,
                                joinError = null // Clear previous errors
                            )
                        }
                        resetInternalTimers()
                    }

                    is Action.JoinGroupFailed -> {
                        Log.e("MeshController", "Join Failed: ${action.reason}")
                        _state.update {
                            it.copy(
                                session = null,
                                joinError = action.reason
                            )
                        }
                    }

                    is Action.AckJoinError -> {
                        _state.update { it.copy(joinError = null) }
                    }

                    is Action.ScanFailed -> {
                        Log.e("MeshController", "Scan Failed: ${action.reason}")
                        _state.update { it.copy(isBrowsing = false) }

                        if (currentState.session != null) {
                            _state.update {
                                it.copy(
                                    session = null, // <--- Triggers UI Navigation to Home
                                    joinError = "Scanning Failed: ${action.reason}"
                                )
                            }
                        }
                        else {
                            emit(Effect.ShowToast("Warning: Background Scanning Failed (${action.reason})"))
                        }
                    }

                    is Action.LeaveGroup -> {
                        Log.i("MeshController", "USER ACTION: Leave Group")
                        // 1. Reset State completely (Keep same ID for now, it rotates on next join)
                        _state.update {
                            AppState(
                                myself = it.myself,
                                availableMics = it.availableMics,
                                availableSpeakers = it.availableSpeakers,
                            )
                        }

                        // 2. Clear internal caches to prevent state bleeding if we rejoin
                        peerLiveness.clear()
                        packetCache.clear()

                        // 3. UI Feedback
                        emit(Effect.ShowToast(action.reason))
                    }

                    // =================================================================
                    // AUDIO CONTROLS (UI/Mic -> Core)
                    // =================================================================

                    is Action.SetMic -> {
                        // Only allow toggling mic if we are in a session
                        if (currentState.session != null) {
                            Log.d("MeshController", "Mic State Changed: ${action.enabled}")
                            _state.update { it.copy(isMicEnabled = action.enabled) }
                        }
                    }

                    is Action.SetAudioInput -> {
                        Log.i("MeshController", "Audio Input Selected: ${action.id}")
                        _state.update { it.copy(selectedInputId = action.id) }
                    }

                    is Action.SetAudioOutput -> {
                        Log.i("MeshController", "Audio Output Selected: ${action.id}")
                        _state.update { it.copy(selectedOutputId = action.id) }
                    }

                    is Action.AudioDevicesUpdated -> {
                        Log.d("MeshController", "Audio Devices Updated: ${action.inputs.size} Mics, ${action.outputs.size} Speakers")
                        _state.update {
                            it.copy(availableMics = action.inputs, availableSpeakers = action.outputs)
                        }
                    }

                    // =================================================================
                    // NETWORK EVENTS (Driver -> Core)
                    // =================================================================

                    is Action.PeerConnected -> {
                        Log.i("MeshController", "PEER CONNECTED: ${action.peerId}")

                        // FIX (Yo-Yo Behavior):
                        // Once we successfully connect to ANY peer, we consider the "Join Attempt" successful.
                        // We clear the flag so the 15s Global Timeout in handleHeartbeatTick no longer applies.
                        // This allows the user to lose connections (0 peers) without the session being killed.
                        val updatedSession = currentState.session?.let {
                            if (it.isJoinAttempt) it.copy(isJoinAttempt = false) else it
                        }

                        _state.update {
                            it.copy(
                                connectedPeers = it.connectedPeers + action.peerId,
                                session = updatedSession
                            )
                        }
                        // Mark them as alive immediately so they don't get reaped by the next cleanup tick
                        peerLiveness[action.peerId] = internalClockMs
                    }

                    is Action.PeerDisconnected -> {
                        Log.i("MeshController", "PEER DISCONNECTED: ${action.peerId}")
                        _state.update { it.copy(connectedPeers = it.connectedPeers - action.peerId) }
                        peerLiveness.remove(action.peerId)
                    }

                    is Action.PacketReceived -> {
                        // If source is known, this packet proves they are alive
                        handleIncomingPacket(currentState, action.data, action.source, action.isControl)
                    }

                    is Action.AdvertisementSeen -> {
                        handleAdvertisement(currentState, action.group)
                    }

                    is Action.AudioDataCaptured -> {
                        // Flood local audio to the mesh
                        if (currentState.isMicEnabled) {
                            // CRITICAL: Cache our own packet so we don't process our own echo
                            markPacketAsSeen(action.data)
                            // Audio is NOT Control (Unreliable/Fast)
                            emit(Effect.Transmit(action.data, TransmissionStrategy.FLOOD, isControl = false, excludedSource = null))
                        }
                    }

                    // =================================================================
                    // SYSTEM TICKS (Timers -> Core)
                    // =================================================================

                    is Action.HeartbeatTick -> {
                        internalClockMs = action.timeMs // Sync internal clock
                        handleHeartbeatTick()
                    }
                    is Action.CleanupTick -> {
                        internalClockMs = action.timeMs // Sync internal clock
                        handleCleanup()
                    }
                }
            } catch (e: Exception) {
                Log.e("MeshController", "Error processing action ${action.javaClass.simpleName}", e)
            }
        }
    }

    // --- Logic Implementation ---

    private suspend fun handleIncomingPacket(state: AppState, data: ByteArray, source: PeerId?, isControl: Boolean) {
        // ACTOR MODEL GUARD:
        // Due to mailbox lag, we might receive a packet AFTER the user has clicked "Leave Group"
        // but BEFORE the driver has fully shut down.
        // If we are not in a session, we must ignore all traffic to prevent state corruption.
        if (state.session == null) return

        // 1. Liveness Update
        if (source != null) {
            peerLiveness[source] = internalClockMs
        }

        // 2. Deduplication (Stop Flooding Loops)
        if (isPacketSeen(data)) return
        markPacketAsSeen(data)

        // 3. Parse Packet
        val packet = Packet.fromBytes(data, isControlChar = isControl) ?: return

        // 4. Process & Relay
        when (packet) {
            is Packet.Control.Heartbeat -> {
                // Only relay if the heartbeat contained NEW topology information
                val changedTopology = handleHeartbeat(packet)
                if (changedTopology) {
                    // Relay Logic: Increment Hops and Flood
                    val newPacket = packet.copy(hops = packet.hops + 1)
                    val bytes = newPacket.toBytes()
                    markPacketAsSeen(bytes) // Cache the relayed version too
                    // Heartbeat IS Control (Reliable)
                    // Echo back to sender as well to update the liveness timeout
                    emit(Effect.Transmit(bytes, TransmissionStrategy.FLOOD, isControl = true, excludedSource = null))
                }
            }
            is Packet.Audio -> {
                // Play Audio
                emit(Effect.RenderAudio(data))
                // Relay Logic: Audio is dumb flood. Always relay unique packets.
                emit(Effect.Transmit(data, TransmissionStrategy.FLOOD, isControl = false, excludedSource = source))
            }
            else -> {
                // Unknown or raw packet. No relaying for now.
            }
        }
    }

    /**
     * Processes a Heartbeat.
     * Returns TRUE if our topology state changed (or sequence updated), indicating we should relay this info.
     */
    private fun handleHeartbeat(hb: Packet.Control.Heartbeat): Boolean {
        val current = _state.value.network
        var changed = false

        // Rule 1: Always prefer a higher Network ID (Merge Island)
        if (hb.netId > current.rootId) {
            Log.i("MeshController", "Topology: Found Better Root ${hb.netId}. Merging.")
            _state.update {
                it.copy(network = NetworkTopology.Mesh(hb.netId, hb.hops + 1, hb.seq))
            }
            lastRootSeen = internalClockMs
            changed = true
        }
        // Rule 2: If same Network ID, update if Sequence is newer (Keepalive)
        else if (hb.netId == current.rootId) {
            if (current is NetworkTopology.Mesh && hb.seq > current.rootSeq) {
                _state.update {
                    it.copy(network = NetworkTopology.Mesh(hb.netId, hb.hops + 1, hb.seq))
                }
                changed = true
            }
            lastRootSeen = internalClockMs
        }

        return changed
    }

    private suspend fun handleAdvertisement(state: AppState, group: DiscoveredGroup) {
        // 1. Discovery Logic (UI List)
        // We group by NAME. If we see "Hiking" again, we check if it's a better signal.
        val existing = state.discoveredGroups.find { it.name == group.name }

        val updatedGroup = if (existing == null) {
            // New Group found
            group
        } else {
            // Existing Group found.
            // If it's the SAME device (MAC), always update (RSSI might fluctuate).
            if (existing.id == group.id) {
                group
            }
            // If it's a DIFFERENT device but has BETTER signal, switch to it.
            else if (group.rssi > existing.rssi) {
                group
            }
            // Otherwise (Different device, worse signal), keep existing but refresh timestamp.
            else {
                existing.copy(lastSeen = internalClockMs)
            }
        }

        // Rebuild list: Remove old entry with same name, add new/updated entry, sort.
        val newGroups = (state.discoveredGroups.filter { it.name != group.name } + updatedGroup)
            .sortedByDescending { it.rssi }

        _state.update { it.copy(discoveredGroups = newGroups) }

        // 2. Auto-Connect Logic
        val session = state.session ?: return
        // We only care about groups that match our current Session Name.
        if (group.name != session.groupName) return
        if (!calculateConnectionStrategy(state, group)) return

        Log.d("MeshController", "Strategy: Decided to connect to ${group.id} (NetID: ${group.netId})")
        emit(Effect.ConnectTo(group.id, group.nodeId, state.myself))
    }

    /**
     * The Convergence Logic.
     * Determines if we should initiate a connection to a discovered peer.
     */
    private fun calculateConnectionStrategy(state: AppState, target: DiscoveredGroup): Boolean {
        // Don't connect to myself (shouldn't happen with NodeID check, but safety first)
        if (target.netId == state.myself) return false

        // Don't connect if already connected
        if (state.connectedPeers.contains(target.netId)) return false

        // PRIORITY 1: Convergence (Island Merging)
        // If they have a higher Network ID, they are a "Better Root".
        // We ALWAYS want to connect to them, even if we are full.
        if (target.netId > state.network.rootId) return true

        // PRIORITY 2: Fill Stable Slots (up to 3)
        // If we are lonely, connect to anyone to form a mesh.
        // NOTE: If we just joined a group, connectedPeers is empty, so this returns TRUE.
        if (state.connectedPeers.size < Config.TARGET_PEERS) return true

        return false
    }

    private suspend fun handleHeartbeatTick() {
        val state = _state.value
        val session = state.session ?: return
        val now = internalClockMs
        val myId = state.myself

        // 1. GLOBAL JOIN TIMEOUT CHECK
        // Only applies if we are trying to join (not hosting) AND we have no peers yet.
        // NOTE: Because of the fix in PeerConnected, isJoinAttempt is cleared upon first connection.
        // So this block is effectively disabled once we have successfully joined the mesh at least once.
        if (session.isJoinAttempt && state.connectedPeers.isEmpty()) {
            if (now - session.startTime > Config.GROUP_JOIN_TIMEOUT) {
                Log.w("MeshController", "Join Timeout: Could not find peers in ${Config.GROUP_JOIN_TIMEOUT}ms")
                _state.update { it.copy(session = null, joinError = "Connection Timed Out") }
                return
            }
        }

        // 2. Timeout Check (Self-Healing)
        // If we haven't heard from Root in a while, we declare independence.
        if (state.network is NetworkTopology.Mesh) {
            if (now - lastRootSeen > Config.HEARTBEAT_TIMEOUT) {
                Log.w("MeshController", "Topology: Root ${state.network.rootId} Timed Out. Reverting to Standalone.")
                _state.update { it.copy(network = NetworkTopology.Standalone(myId)) }
            }
        }

        // 3. Transmit Heartbeat (Only if I am Root)
        // If I am Standalone or I am the Root of the Mesh, I generate the heartbeat.
        if (state.network is NetworkTopology.Standalone || state.network.rootId == myId) {
            if (now - lastHeartbeatSent > Config.HEARTBEAT_INTERVAL) {
                // Generate new sequence
                val currentSeq = (state.network as? NetworkTopology.Mesh)?.rootSeq ?: 0
                val newSeq = currentSeq + 1

                // Update local state
                _state.update { it.copy(network = NetworkTopology.Mesh(myId, 0, newSeq)) }

                // Transmit
                val hb = Packet.Control.Heartbeat(myId, newSeq, 0)
                val bytes = hb.toBytes()
                // CRITICAL: Cache our own heartbeat
                markPacketAsSeen(bytes)
                // Heartbeat IS Control (Reliable)
                emit(Effect.Transmit(bytes, TransmissionStrategy.FLOOD, isControl = true, excludedSource = null))

                // Update self-liveness so we don't timeout ourselves
                lastHeartbeatSent = now
                lastRootSeen = now
            }
        }
    }

    private suspend fun handleCleanup() {
        val now = internalClockMs

        // 1. Prune Packet Cache
        val iter = packetCache.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > Config.PACKET_CACHE_TIMEOUT) iter.remove()
        }

        // 2. Prune Dead Peers
        val peersToCheck = _state.value.connectedPeers.toList()
        peersToCheck.forEach { peerId ->
            val lastSeen = peerLiveness[peerId] ?: now
            if (now - lastSeen > Config.PEER_LIVENESS_TIMEOUT) {
                Log.w("MeshController", "Liveness: Peer $peerId timed out (${now - lastSeen}ms > ${Config.PEER_LIVENESS_TIMEOUT}ms). Disconnecting.")
                emit(Effect.Disconnect(peerId))
            }
        }

        // 3. Prune Discovered Groups
        val currentGroups = _state.value.discoveredGroups
        val freshGroups = currentGroups.filter { now - it.lastSeen < Config.GROUP_ADVERTISEMENT_TIMEOUT }
        if (freshGroups.size != currentGroups.size) {
            _state.update { it.copy(discoveredGroups = freshGroups) }
        }
    }

    private fun isPacketSeen(data: ByteArray): Boolean {
        return packetCache.containsKey(data.contentHashCode())
    }

    private fun markPacketAsSeen(data: ByteArray) {
        packetCache[data.contentHashCode()] = internalClockMs
    }

    private suspend fun emit(effect: Effect) {
        _effects.emit(effect)
    }

    private fun resetInternalTimers() {
        lastHeartbeatSent = internalClockMs
        lastRootSeen = internalClockMs
    }
}