package com.denizetkar.walkietalkieapp.logic

import com.denizetkar.walkietalkieapp.Config

data class TopologyState(
    val currentNetworkId: UInt,
    val hopsToRoot: Int,
    val rootSequence: Int
)

class TopologyEngine(private val ownNodeId: UInt) {

    private var state = TopologyState(ownNodeId, 0, 0)
    private var lastHeartbeatTime = System.currentTimeMillis()

    fun getCurrentState() = state

    /**
     * Processes an incoming heartbeat.
     * Returns TRUE if our local state changed (meaning we should update advertising).
     */
    fun onHeartbeatReceived(netId: UInt, seq: Int, hops: Int): Boolean {
        val now = System.currentTimeMillis()

        // 1. Merge: Found a higher Network ID (Better Root)
        if (netId > state.currentNetworkId) {
            state = TopologyState(netId, hops + 1, seq)
            lastHeartbeatTime = now
            return true
        }

        // 2. Update: Same Network, newer sequence number
        if (netId == state.currentNetworkId && seq > state.rootSequence) {
            state = state.copy(rootSequence = seq, hopsToRoot = hops + 1)
            lastHeartbeatTime = now
            return true
        }

        return false
    }

    /**
     * Checks if the Root has timed out.
     * Returns TRUE if we downgraded to being our own Root.
     */
    fun checkTimeout(): Boolean {
        // If I am already Root, I cannot timeout
        if (state.currentNetworkId == ownNodeId) return false

        val now = System.currentTimeMillis()
        if (now - lastHeartbeatTime > Config.HEARTBEAT_TIMEOUT) {
            // Downgrade: I am now the Root of my own shard
            state = TopologyState(ownNodeId, 0, 0)
            return true
        }
        return false
    }

    /**
     * Generates a Heartbeat packet ONLY if we are the Root.
     */
    fun generateHeartbeat(): ByteArray? {
        if (state.currentNetworkId != ownNodeId) return null

        // Increment sequence
        state = state.copy(rootSequence = state.rootSequence + 1)
        lastHeartbeatTime = System.currentTimeMillis()

        return PacketUtils.createHeartbeatPayload(state.currentNetworkId, state.rootSequence, 0)
    }
}