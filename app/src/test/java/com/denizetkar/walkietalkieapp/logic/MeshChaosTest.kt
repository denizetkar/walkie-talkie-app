package com.denizetkar.walkietalkieapp.logic

import com.denizetkar.walkietalkieapp.domain.Action
import com.denizetkar.walkietalkieapp.domain.Effect
import com.denizetkar.walkietalkieapp.protocol.Packet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MeshChaosTest {

    private lateinit var controller: MeshController
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // Capture effects to verify outputs
    private val effects = mutableListOf<Effect>()

    // Deterministic start time for simulation
    private var simulationTime = 1000L

    @Before
    fun setup() {
        // Use backgroundScope to keep the collector alive during the test
        controller = MeshController(testScope.backgroundScope, testDispatcher)

        // Initialize the internal clock to a known value (1000L)
        // forcing it away from System.currentTimeMillis()
        controller.dispatch(Action.HeartbeatTick(simulationTime))
        testScope.runCurrent()

        testScope.backgroundScope.launch(UnconfinedTestDispatcher(testScope.testScheduler)) {
            controller.effects.toList(effects)
        }
    }

    /**
     * SCENARIO: The "Zombie" Peer
     * A peer disconnects, but due to UDP/BLE lag, an audio packet arrives 50ms later.
     */
    @Test
    fun `Zombie Peer - Traffic after disconnect does not resurrect peer`() = testScope.runTest {
        // 1. Setup: Active Session with Peer 50
        controller.dispatch(Action.JoinGroup("Chaos", "1234"))
        runCurrent()
        controller.dispatch(Action.PeerConnected(50u))
        runCurrent()
        assertTrue(controller.state.value.connectedPeers.contains(50u))

        // 2. Action: Disconnect Peer
        controller.dispatch(Action.PeerDisconnected(50u))
        runCurrent()
        assertTrue(!controller.state.value.connectedPeers.contains(50u))

        effects.clear()

        // 3. Action: Receive Packet from "Zombie" 50
        val packet = Packet.Audio(byteArrayOf(0xDE.toByte(), 0xAD.toByte())).toBytes()
        controller.dispatch(Action.PacketReceived(packet, source = 50u, isControl = false))
        runCurrent()

        // 4. Assert:
        // - Peer must NOT be added back to connected list
        assertTrue(
            "Zombie peer should not be added back to roster",
            !controller.state.value.connectedPeers.contains(50u)
        )

        // - We DO expect relaying (Mesh Logic: relay everything unless strictly forbidden)
        assertTrue(
            "Should relay packets even from non-handshaked sources (Open Mesh)",
            effects.any { it is Effect.Transmit }
        )
    }

    /**
     * SCENARIO: The "Ghost" Session
     * The Service is running, but the User has left the group (Session = null).
     */
    @Test
    fun `Ghost Session - Ignores all traffic when session is null`() = testScope.runTest {
        // 1. Ensure Session is NULL (Default)
        assertTrue(controller.state.value.session == null)

        // 2. Action: Driver delivers a packet
        val packet = Packet.Audio(byteArrayOf(1, 2, 3)).toBytes()
        controller.dispatch(Action.PacketReceived(packet, source = 99u, isControl = false))
        runCurrent()

        // 3. Assert: No effects, No state change
        assertTrue("Should produce NO effects", effects.isEmpty())
        assertTrue("Roster should remain empty", controller.state.value.connectedPeers.isEmpty())
    }

    /**
     * SCENARIO: Rapid State Churn
     * Connection events arrive out of order or extremely fast due to flaky hardware.
     */
    @Test
    fun `Race Condition - Rapid Connect-Disconnect-Connect`() = testScope.runTest {
        controller.dispatch(Action.JoinGroup("Chaos", "1234"))
        runCurrent()

        // Rapid Fire
        controller.dispatch(Action.PeerConnected(10u))
        controller.dispatch(Action.PeerDisconnected(10u))
        controller.dispatch(Action.PeerConnected(10u))
        runCurrent()

        // Assert
        assertTrue(
            "Peer should be connected (Last Action wins)",
            controller.state.value.connectedPeers.contains(10u)
        )
    }

    /**
     * SCENARIO: Protocol Fuzzing (Logic Layer)
     * We receive a Control Packet with an OpCode that doesn't exist (e.g. Future Protocol v3).
     */
    @Test
    fun `Security - Unknown OpCode is ignored`() = testScope.runTest {
        controller.dispatch(Action.JoinGroup("Chaos", "1234"))
        runCurrent()
        effects.clear()

        // 1. Construct valid BLE packet structure but with unknown OpCode 0xFF
        val badPacket = byteArrayOf(0x10, 0xFF.toByte(), 0x00)

        // 2. Action: Receive it
        controller.dispatch(Action.PacketReceived(badPacket, source = 20u, isControl = true))
        runCurrent()

        // 3. Assert: No Transmit, No Render
        assertTrue("Should ignore unknown OpCode", effects.isEmpty())
        assertTrue("Topology should not change", controller.state.value.connectedPeers.isEmpty())
    }

    /**
     * SCENARIO: Self-Reflection (The Echo)
     * We receive a packet that WE sent, but the deduplication cache has expired.
     */
    @Test
    fun `Echo Chamber - Relays own packet if cache expired`() = testScope.runTest {
        controller.dispatch(Action.JoinGroup("Chaos", "1234"))
        runCurrent()

        // Enable Mic, otherwise AudioDataCaptured is ignored
        controller.dispatch(Action.SetMic(true))
        runCurrent()

        effects.clear()

        // 1. Action: Capture Audio (Self)
        val audioData = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        controller.dispatch(Action.AudioDataCaptured(audioData))
        runCurrent()

        // Verify it was sent (this puts it in the cache)
        assertTrue("Should transmit when mic is enabled", effects.any { it is Effect.Transmit })
        effects.clear()

        // 2. Immediate Echo: Receive same data back from peer
        controller.dispatch(Action.PacketReceived(audioData, source = 20u, isControl = false))
        runCurrent()

        // Assert: Deduplicated (No new transmit/render)
        assertTrue("Should deduplicate immediate echo", effects.isEmpty())

        // 3. Fast Forward past Cache Timeout (5000ms)
        // We simulate time passing by ticking the cleanup
        simulationTime += 6000L
        controller.dispatch(Action.CleanupTick(timeMs = simulationTime))

        // Advance coroutine clock too
        testScope.advanceTimeBy(6000.milliseconds)
        runCurrent()

        // 4. Delayed Echo: Receive same data back again
        controller.dispatch(Action.PacketReceived(audioData, source = 20u, isControl = false))
        runCurrent()

        // Assert: Processed again (Cache expired)
        assertTrue(
            "Should re-process packet after cache expiry",
            effects.any { it is Effect.RenderAudio }
        )
    }
}