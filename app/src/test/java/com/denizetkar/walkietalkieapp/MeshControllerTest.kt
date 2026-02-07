package com.denizetkar.walkietalkieapp

import com.denizetkar.walkietalkieapp.domain.*
import com.denizetkar.walkietalkieapp.logic.MeshController
import com.denizetkar.walkietalkieapp.protocol.Packet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshControllerTest {

    @Test
    fun `Create Group sets state to Standalone Root`() = runTest {
        // USE UNCONFINED: Ensures the actor loop processes actions immediately when dispatched.
        val controller = MeshController(backgroundScope, UnconfinedTestDispatcher(testScheduler))

        controller.dispatch(Action.HeartbeatTick(1000L))
        controller.dispatch(Action.CreateGroup("Hiking", "1234"))

        // With Unconfined, we don't even strictly need advanceUntilIdle() for logic updates,
        // but keeping it is good practice for any side-effects.
        testScheduler.advanceUntilIdle()

        val state = controller.state.value

        assertNotNull("Session should be created", state.session)
        assertEquals("Hiking", state.session?.groupName)
        assertEquals("1234", state.session?.accessCode)

        // I should be the root of my own network
        assertEquals(state.myself, state.network.rootId)
        assertEquals(0, state.network.hops)
    }

    @Test
    fun `Convergence - Merges with Better Root`() = runTest {
        val controller = MeshController(backgroundScope, UnconfinedTestDispatcher(testScheduler))

        controller.dispatch(Action.HeartbeatTick(1000L))
        controller.dispatch(Action.CreateGroup("Hiking", "1234"))

        // No advance needed due to Unconfined, logic happens instantly

        val myId = controller.state.value.myself
        // Peer has higher ID (Better Root)
        val betterRootId = myId + 10u

        // Simulate hearing a Heartbeat from this better root
        val hb = Packet.Control.Heartbeat(netId = betterRootId, seq = 1, hops = 0)
        controller.dispatch(Action.PacketReceived(hb.toBytes(), source = betterRootId, isControl = true))

        val newState = controller.state.value
        assertEquals("Should have adopted the higher Root ID", betterRootId, newState.network.rootId)
        assertEquals("Should be 1 hop away", 1, newState.network.hops)
    }

    @Test
    fun `Relaying - Floods Heartbeat`() = runTest {
        val controller = MeshController(backgroundScope, UnconfinedTestDispatcher(testScheduler))

        controller.dispatch(Action.HeartbeatTick(1000L))
        controller.dispatch(Action.CreateGroup("Hiking", "1234"))

        val myId = controller.state.value.myself
        val betterRootId = myId + 10u

        // Capture effects
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.collect { effects.add(it) }
        }

        // Simulate incoming heartbeat
        val hb = Packet.Control.Heartbeat(netId = betterRootId, seq = 10, hops = 0)
        controller.dispatch(Action.PacketReceived(hb.toBytes(), source = betterRootId, isControl = true))

        // Verify we re-transmitted (flooded) the packet
        val transmit = effects.filterIsInstance<Effect.Transmit>().firstOrNull { it.isControl }
        assertNotNull("Should have relayed the heartbeat", transmit)

        // Decode the relayed packet to verify hop count increment
        val relayedPacket = Packet.fromBytes(transmit!!.data, true) as Packet.Control.Heartbeat
        assertEquals("Hops should increment on relay", 1, relayedPacket.hops)
        assertEquals("Sequence number should be preserved", 10, relayedPacket.seq)
    }

    @Test
    fun `Self Healing - Reverts to Standalone on Timeout`() = runTest {
        val controller = MeshController(backgroundScope, UnconfinedTestDispatcher(testScheduler))

        // 1. Start at Time = 1000
        var virtualTime = 1000L
        controller.dispatch(Action.HeartbeatTick(virtualTime))

        controller.dispatch(Action.CreateGroup("Hiking", "1234"))

        val myId = controller.state.value.myself

        // 2. Merge with external network
        val betterRootId = myId + 100u
        val hb = Packet.Control.Heartbeat(netId = betterRootId, seq = 1, hops = 0)
        controller.dispatch(Action.PacketReceived(hb.toBytes(), source = betterRootId, isControl = true))

        // Assertion 1: Ensure we actually merged (This failed before because setup failed)
        assertTrue("Should currently be Mesh", controller.state.value.network is NetworkTopology.Mesh)

        // 3. Advance Time past the timeout threshold
        // Config.HEARTBEAT_TIMEOUT is 3000ms. We move 4000ms forward.
        virtualTime += 4000L
        controller.dispatch(Action.HeartbeatTick(virtualTime))

        // 4. Verify reversion
        val network = controller.state.value.network
        assertTrue("Should revert to Standalone", network is NetworkTopology.Standalone)
        assertEquals("Root should be myself again", myId, network.rootId)
    }
}