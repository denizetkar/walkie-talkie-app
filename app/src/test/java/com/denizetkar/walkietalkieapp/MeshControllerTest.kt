package com.denizetkar.walkietalkieapp

import com.denizetkar.walkietalkieapp.domain.*
import com.denizetkar.walkietalkieapp.logic.MeshController
import com.denizetkar.walkietalkieapp.protocol.Packet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshControllerTest {

    private lateinit var controller: MeshController
    private lateinit var testDispatcher: TestDispatcher
    private val effects = mutableListOf<Effect>()

    // Renamed to avoid conflict with TestScope.currentTime
    private var simulationTime = 1000L

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        // We initialize the controller with the Standard dispatcher
        controller = MeshController(TestScope(testDispatcher), testDispatcher)

        // Start the simulation clock
        simulationTime = 1000L
        controller.dispatch(Action.HeartbeatTick(simulationTime))
    }

    /**
     * Helper to advance both Coroutine time and the Controller's internal clock.
     * The Controller relies on explicit Tick actions to know time has passed.
     */
    private fun TestScope.advanceSimulation(durationMs: Long) {
        val stepSize = 1000L
        val steps = durationMs / stepSize

        repeat(steps.toInt()) {
            simulationTime += stepSize
            // Dispatch both ticks so all logic (Heartbeats + Cleanups) runs
            controller.dispatch(Action.HeartbeatTick(simulationTime))
            controller.dispatch(Action.CleanupTick(simulationTime))
            advanceTimeBy(stepSize)
            runCurrent()
        }

        // Handle remaining ms
        val remaining = durationMs % stepSize
        if (remaining > 0) {
            simulationTime += remaining
            controller.dispatch(Action.HeartbeatTick(simulationTime))
            controller.dispatch(Action.CleanupTick(simulationTime))
            advanceTimeBy(remaining)
            runCurrent()
        }
    }

    @Test
    fun `Deduplication - Duplicate packets do not trigger Audio or Relay`() = runTest(testDispatcher) {
        // Collect effects in background
        backgroundScope.launch { controller.effects.collect { effects.add(it) } }

        // 1. Setup: Join a group so we are listening
        controller.dispatch(Action.JoinGroup("Test", "1234"))
        runCurrent()
        effects.clear() // Clear "Connect" effects

        // 2. Action: Receive Audio Packet A (First Time)
        val packetA = Packet.Audio(byteArrayOf(0x01, 0x02, 0x03)).toBytes()
        controller.dispatch(Action.PacketReceived(packetA, source = 10u, isControl = false))
        runCurrent()

        // Assert: We rendered and relayed it
        assertTrue("Should render audio", effects.any { it is Effect.RenderAudio })
        assertTrue("Should relay packet", effects.any { it is Effect.Transmit })

        effects.clear()

        // 3. Action: Receive Packet A again (Duplicate)
        controller.dispatch(Action.PacketReceived(packetA, source = 10u, isControl = false))
        runCurrent()

        // Assert: NO new effects
        assertTrue("Should ignore duplicate packet", effects.isEmpty())
    }

    @Test
    fun `Split Horizon - Do not echo data back to sender`() = runTest(testDispatcher) {
        backgroundScope.launch { controller.effects.collect { effects.add(it) } }
        controller.dispatch(Action.JoinGroup("Test", "1234"))
        runCurrent()
        effects.clear()

        // 1. Action: Receive Audio from Peer 99
        val peerId = 99u
        val packet = Packet.Audio(byteArrayOf(0xFF.toByte())).toBytes()
        controller.dispatch(Action.PacketReceived(packet, source = peerId, isControl = false))
        runCurrent()

        // 2. Assert: Transmit effect has excludedSource = 99
        val transmit = effects.filterIsInstance<Effect.Transmit>().first()
        assertEquals(
            "Should exclude the original sender from the flood",
            peerId,
            transmit.excludedSource
        )
    }

    @Test
    fun `Peer Liveness - Disconnects silent peers after timeout`() = runTest(testDispatcher) {
        backgroundScope.launch { controller.effects.collect { effects.add(it) } }
        controller.dispatch(Action.JoinGroup("Test", "1234"))

        // 1. Setup: Peer 50 connects
        val peerId = 50u
        controller.dispatch(Action.PeerConnected(peerId))
        runCurrent()
        assertTrue(controller.state.value.connectedPeers.contains(peerId))

        // 2. Advance time JUST BEFORE timeout (Config.PEER_LIVENESS_TIMEOUT = 7000ms)
        advanceSimulation(6000)
        assertTrue("Peer should still be alive", controller.state.value.connectedPeers.contains(peerId))

        // 3. Keep peer alive by sending a packet
        controller.dispatch(Action.PacketReceived(byteArrayOf(1), source = peerId, isControl = false))
        runCurrent()

        // 4. Advance time past the ORIGINAL timeout, but within the NEW timeout
        // Total 10s from start, but only 4s since last packet
        advanceSimulation(4000)
        assertTrue("Peer should stay alive due to activity", controller.state.value.connectedPeers.contains(peerId))

        // 5. Advance time to trigger actual timeout (4s + 4s > 7s)
        advanceSimulation(4000)

        // Assert: Peer disconnected
        val disconnectEffect = effects.filterIsInstance<Effect.Disconnect>().firstOrNull()
        assertNotNull("Should have emitted Disconnect effect", disconnectEffect)
        assertEquals(peerId, disconnectEffect?.peerId)
    }

    @Test
    fun `Leave Group - Clears internal state (Cache)`() = runTest(testDispatcher) {
        backgroundScope.launch { controller.effects.collect { effects.add(it) } }

        // 1. Join Group 1
        controller.dispatch(Action.JoinGroup("Group1", "1111"))
        runCurrent()

        // 2. Receive Packet X (it gets cached)
        val packetX = Packet.Audio(byteArrayOf(0xAA.toByte())).toBytes()
        controller.dispatch(Action.PacketReceived(packetX, source = 1u, isControl = false))
        runCurrent()
        effects.clear()

        // 3. Receive Packet X again (Duplicate check)
        controller.dispatch(Action.PacketReceived(packetX, source = 1u, isControl = false))
        runCurrent()
        assertTrue("Should be ignored", effects.isEmpty())

        // 4. Leave Group
        controller.dispatch(Action.LeaveGroup())
        runCurrent()
        assertNull(controller.state.value.session)

        // 5. Create NEW Group (Logic: Should simulate a fresh start)
        controller.dispatch(Action.CreateGroup("Group2", "2222"))
        runCurrent()
        effects.clear()

        // 6. Receive Packet X again
        // Since we left the group, the deduplication cache should have been wiped.
        // Therefore, this packet should be processed as NEW.
        controller.dispatch(Action.PacketReceived(packetX, source = 1u, isControl = false))
        runCurrent()

        assertTrue("Should process packet again after re-joining", effects.isNotEmpty())
    }

    @Test
    fun `Heartbeat - Root generates heartbeats periodically`() = runTest(testDispatcher) {
        backgroundScope.launch { controller.effects.collect { effects.add(it) } }

        // 1. Create Group (Becomes Root)
        controller.dispatch(Action.CreateGroup("Test", "1234"))
        runCurrent()
        effects.clear()

        // 2. Advance time < Interval (1000ms)
        advanceSimulation(500)
        assertTrue(effects.isEmpty())

        // 3. Advance time > Interval
        advanceSimulation(600) // Total 1100ms

        val hbEffect = effects.filterIsInstance<Effect.Transmit>().firstOrNull { it.isControl }
        assertNotNull("Should generate heartbeat", hbEffect)

        val packet = Packet.fromBytes(hbEffect!!.data, true) as Packet.Control.Heartbeat
        assertEquals("Should be my ID", controller.state.value.myself, packet.netId)
        assertEquals("Hops should be 0", 0, packet.hops)
    }
}