package com.denizetkar.walkietalkieapp.logic

import com.denizetkar.walkietalkieapp.domain.Action
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.domain.Effect
import com.denizetkar.walkietalkieapp.domain.NetworkTopology
import com.denizetkar.walkietalkieapp.protocol.Packet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MeshControllerTest {

    private lateinit var controller: MeshController
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // Renamed to avoid conflict with TestScope.currentTime
    private var simulationTime = 1000L

    @Before
    fun setup() {
        // Use backgroundScope so the actor loop is cancelled at end of test
        controller = MeshController(testScope.backgroundScope, testDispatcher)
        // Initialize the simulation clock
        controller.dispatch(Action.HeartbeatTick(simulationTime))
    }

    /**
     * Helper to advance time in the simulation.
     * Ticks the internal clock and the coroutine scheduler.
     */
    private fun tick(durationMs: Long) {
        val step = 100L
        var elapsed = 0L
        while (elapsed < durationMs) {
            simulationTime += step
            elapsed += step
            controller.dispatch(Action.HeartbeatTick(simulationTime))
            controller.dispatch(Action.CleanupTick(simulationTime))
            testScope.advanceTimeBy(step.milliseconds)
            testScope.runCurrent()
        }
    }

    @Test
    fun `Deduplication - Duplicate packets do not trigger Audio or Relay`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        // 1. Setup: Join a group
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
    fun `Split Horizon - Do not echo data back to sender`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        controller.dispatch(Action.JoinGroup("Test", "1234"))
        runCurrent()
        effects.clear()

        // 1. Action: Receive Audio from Peer 99
        val peerId = 99u
        val packet = Packet.Audio(byteArrayOf(0xFF.toByte())).toBytes()
        controller.dispatch(Action.PacketReceived(packet, source = peerId, isControl = false))
        runCurrent()

        // 2. Assert: Transmit effect has excludedSource = 99
        val transmit = effects.filterIsInstance<Effect.Transmit>().firstOrNull()
        assertNotNull(transmit)
        assertEquals(
            "Should exclude the original sender from the flood",
            peerId,
            transmit?.excludedSource
        )
    }

    @Test
    fun `Peer Liveness - Disconnects silent peers after timeout`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        controller.dispatch(Action.JoinGroup("Test", "1234"))
        runCurrent()

        // 1. Setup: Peer 50 connects
        val peerId = 50u
        controller.dispatch(Action.PeerConnected(peerId))
        runCurrent()
        assertTrue(controller.state.value.connectedPeers.contains(peerId))

        // 2. Advance time JUST BEFORE timeout (7000ms)
        tick(6000)
        assertTrue("Peer should still be alive", controller.state.value.connectedPeers.contains(peerId))

        // 3. Keep peer alive by sending a packet
        controller.dispatch(Action.PacketReceived(byteArrayOf(1), source = peerId, isControl = false))
        runCurrent()

        // 4. Advance time past the ORIGINAL timeout, but within the NEW timeout
        tick(4000)
        assertTrue("Peer should stay alive due to activity", controller.state.value.connectedPeers.contains(peerId))

        // 5. Advance time to trigger actual timeout
        tick(4000)

        // Assert: Peer disconnected
        val disconnectEffect = effects.filterIsInstance<Effect.Disconnect>().firstOrNull { it.peerId == peerId }
        assertNotNull("Should have emitted Disconnect effect", disconnectEffect)
    }

    @Test
    fun `Leave Group - Clears internal state`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        // 1. Join Group 1
        controller.dispatch(Action.JoinGroup("Group1", "1111"))
        runCurrent()

        // 2. Receive Packet X (cached)
        val packetX = Packet.Audio(byteArrayOf(0xAA.toByte())).toBytes()
        controller.dispatch(Action.PacketReceived(packetX, source = 1u, isControl = false))
        runCurrent()
        effects.clear()

        // 3. Leave Group
        controller.dispatch(Action.LeaveGroup())
        runCurrent()
        assertNull(controller.state.value.session)

        // 4. Create NEW Group
        controller.dispatch(Action.CreateGroup("Group2", "2222"))
        runCurrent()
        effects.clear()

        // 5. Receive Packet X again (Should be treated as NEW)
        controller.dispatch(Action.PacketReceived(packetX, source = 1u, isControl = false))
        runCurrent()

        assertTrue("Should process packet again after re-joining", effects.isNotEmpty())
    }

    @Test
    fun `Heartbeat - Root generates heartbeats periodically`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        // 1. Create Group (Becomes Root)
        controller.dispatch(Action.CreateGroup("Test", "1234"))
        runCurrent()
        effects.clear()

        // 2. Advance time > Interval (1000ms)
        tick(1100)

        val hbEffect = effects.filterIsInstance<Effect.Transmit>().firstOrNull { it.isControl }
        assertNotNull("Should generate heartbeat", hbEffect)

        val packet = Packet.fromBytes(hbEffect!!.data, true) as Packet.Control.Heartbeat
        assertEquals("Should be my ID", controller.state.value.myself, packet.netId)
        assertEquals("Hops should be 0", 0, packet.hops)
    }

    @Test
    fun `Topology - Merges with Better Root (Island Merging)`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        // 1. Setup: Node starts a group (Random Root ID)
        controller.dispatch(Action.CreateGroup("Hiking", "1234"))
        runCurrent()
        effects.clear()

        // Get the ACTUAL generated ID and calculate a strictly higher ID
        val myId = controller.state.value.myself
        val betterRootId = myId + 100u

        // 2. Action: Receive Heartbeat from Better Root
        val hbPacket = Packet.Control.Heartbeat(
            netId = betterRootId,
            seq = 50,
            hops = 0
        ).toBytes()

        controller.dispatch(Action.PacketReceived(hbPacket, source = 50u, isControl = true))
        tick(100)

        // 3. Assert: We adopted the new root
        val newState = controller.state.value
        assertEquals("Should adopt better root ID", betterRootId, newState.network.rootId)
        assertTrue("Should be Mesh type", newState.network is NetworkTopology.Mesh)
        assertEquals("Hops should increment", 1, newState.network.hops)

        // 4. Assert: We relayed the new topology
        val transmit = effects.filterIsInstance<Effect.Transmit>().lastOrNull()
        assertNotNull("Should transmit new topology", transmit)
        assertTrue("Should be reliable control packet", transmit!!.isControl)
    }

    @Test
    fun `Topology - Ignores Worse Root (Loop Prevention)`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        controller.dispatch(Action.CreateGroup("Hiking", "1234"))
        runCurrent()
        effects.clear()

        // Get the ACTUAL generated ID and calculate a strictly lower ID
        val myId = controller.state.value.myself
        // Ensure we don't underflow 0 (unlikely random would be 0, but good practice)
        val worseRootId = if (myId > 0u) myId - 1u else 0u

        if (myId == 0u) {
            // Edge case: If random was 0, we can't test "worse" easily in this unit test structure
            // without mocking Random. Skipping assertion for this rare 1/4billion case.
            return@runTest
        }

        // 2. Action: Receive Heartbeat from Worse Root
        val hbPacket = Packet.Control.Heartbeat(
            netId = worseRootId,
            seq = 50,
            hops = 0
        ).toBytes()

        controller.dispatch(Action.PacketReceived(hbPacket, source = 50u, isControl = true))
        tick(100)

        // 3. Assert: We stayed with our own ID
        assertEquals("Should ignore worse root", myId, controller.state.value.network.rootId)
        assertTrue("Should NOT relay worse topology", effects.isEmpty())
    }

    @Test
    fun `Self Healing - Reverts to Standalone after Root Timeout`() = testScope.runTest {
        controller.dispatch(Action.CreateGroup("Hiking", "1234"))
        runCurrent()

        // Use relative ID
        val myId = controller.state.value.myself
        val betterRootId = myId + 100u

        // 1. Force Merge to Better Root
        val hbPacket = Packet.Control.Heartbeat(betterRootId, 10, 0).toBytes()
        controller.dispatch(Action.PacketReceived(hbPacket, source = 50u, isControl = true))
        tick(100)

        assertEquals(betterRootId, controller.state.value.network.rootId)

        // 2. Wait 2 seconds (Still Alive)
        tick(2000)
        assertEquals(betterRootId, controller.state.value.network.rootId)

        // 3. Wait 2 more seconds (Total 4s > Timeout 3s)
        tick(2000)

        // 4. Assert: Reverted to Myself (Discarded betterRootId)
        val finalState = controller.state.value
        assertEquals("Should be my own root", myId, finalState.network.rootId)

        // Note: We do NOT assert is Standalone, because the node immediately promotes itself
        // to Mesh(myId) to start generating sequence numbers.
    }

    @Test
    fun `Audio - Only floods if Mic Enabled`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        controller.dispatch(Action.JoinGroup("Hiking", "1234"))
        runCurrent()

        // 1. Set Mic DISABLED
        controller.dispatch(Action.SetMic(false))
        runCurrent()
        effects.clear()

        // 2. Capture Audio from Mic
        controller.dispatch(Action.AudioDataCaptured(byteArrayOf(1, 2, 3)))
        runCurrent()

        // Assert: NO Transmission
        assertTrue("Should not transmit when Mic is OFF", effects.isEmpty())

        // 3. Set Mic ENABLED
        controller.dispatch(Action.SetMic(true))
        runCurrent()

        // 4. Capture Audio from Mic
        controller.dispatch(Action.AudioDataCaptured(byteArrayOf(4, 5, 6)))
        runCurrent()

        // Assert: Transmit Effect
        val transmit = effects.filterIsInstance<Effect.Transmit>().lastOrNull()
        assertNotNull("Should transmit when Mic is ON", transmit)
        assertTrue("Should be unreliable (flood)", !transmit!!.isControl)
    }

    @Test
    fun `Join Timeout - Drops session if no peers connect within 15 seconds`() = testScope.runTest {
        // 1. Action: Try to join a group
        controller.dispatch(Action.JoinGroup("GhostCamp", "1234"))
        runCurrent()

        assertNotNull("Session should be active while attempting to join", controller.state.value.session)
        assertTrue("isJoinAttempt flag should be true", controller.state.value.session?.isJoinAttempt == true)

        // 2. Advance time past the 15-second Global Join Timeout
        tick(Config.GROUP_JOIN_TIMEOUT + 500L) // 15,500 ms

        // 3. Assert: Session killed, Error populated
        assertNull("Session should be dropped after timeout", controller.state.value.session)
        assertEquals("Connection Timed Out", controller.state.value.joinError)
    }

    @Test
    fun `Yo-Yo Fix - Session survives if peer drops to zero after successfully joining once`() = testScope.runTest {
        controller.dispatch(Action.JoinGroup("Hiking", "1234"))
        runCurrent()

        // 1. Peer connects! (We have found the mesh)
        controller.dispatch(Action.PeerConnected(10u))
        runCurrent()

        // Assert the fix worked: isJoinAttempt is cleared
        assertTrue("isJoinAttempt should be cleared after first connection", controller.state.value.session?.isJoinAttempt == false)

        // 2. Peer immediately disconnects (The user walked out of range)
        controller.dispatch(Action.PeerDisconnected(10u))
        runCurrent()

        assertTrue("Roster should be empty", controller.state.value.connectedPeers.isEmpty())

        // 3. Wait past the global timeout limit (15.5 seconds)
        tick(Config.GROUP_JOIN_TIMEOUT + 500L)

        // 4. Assert: The user is still in the group, waiting to get back in range!
        assertNotNull("Session MUST survive the Yo-Yo effect", controller.state.value.session)
        assertNull("There should be no timeout error", controller.state.value.joinError)
    }

    @Test
    fun `Driver Failures - ScanFailed handles background and active sessions gracefully`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        // --- SCENARIO A: Background Browsing ---
        controller.dispatch(Action.StartScanning)
        runCurrent()
        assertTrue(controller.state.value.isBrowsing)

        controller.dispatch(Action.ScanFailed("BLE Crash"))
        runCurrent()

        // Browsing NOT aborted, we stay on the Home Screen (Toast is emitted)
        assertTrue("Browsing should not stop on failure", controller.state.value.isBrowsing)
        val toast = effects.filterIsInstance<Effect.ShowToast>().lastOrNull()
        assertTrue("Should emit warning toast", toast?.message?.contains("BLE Crash") == true)

        effects.clear()

        // --- SCENARIO B: Active Session (Critical Failure) ---
        controller.dispatch(Action.JoinGroup("Test", "1234"))
        runCurrent()
        assertNotNull(controller.state.value.session)

        // Driver reports the scanner died while we were trying to maintain the mesh
        controller.dispatch(Action.ScanFailed("Hardware Reset"))
        runCurrent()

        // Assert: Session is aborted, user kicked back to Home Screen with an error dialog
        assertNull("Session should be dropped on critical hardware failure", controller.state.value.session)
        assertTrue("Join error should contain hardware failure reason", controller.state.value.joinError?.contains("Hardware Reset") == true)
    }

    @Test
    fun `Bluetooth State - Updates state and leaves group if disabled during session`() = testScope.runTest {
        // 1. Initial State
        assertTrue(controller.state.value.isBluetoothEnabled)

        // 2. Disable Bluetooth while idle
        controller.dispatch(Action.BluetoothStateChanged(false))
        runCurrent()
        assertFalse(controller.state.value.isBluetoothEnabled)
        assertNull(controller.state.value.session)

        // 3. Enable Bluetooth, Join Group
        controller.dispatch(Action.BluetoothStateChanged(true))
        controller.dispatch(Action.JoinGroup("Test", "1234"))
        runCurrent()
        assertTrue(controller.state.value.isBluetoothEnabled)
        assertNotNull(controller.state.value.session)

        // 4. Disable Bluetooth while in session
        controller.dispatch(Action.BluetoothStateChanged(false))
        runCurrent()

        // Assert: State updated and session cleared (via LeaveGroup)
        assertFalse(controller.state.value.isBluetoothEnabled)
        assertNull(controller.state.value.session)
    }

    @Test
    fun `Hardware & UI State - Updates available devices and selection`() = testScope.runTest {
        // 1. Available Devices List
        val inputs = listOf(com.denizetkar.walkietalkieapp.AudioDeviceUi(1, "Mic1"))
        val outputs = listOf(com.denizetkar.walkietalkieapp.AudioDeviceUi(2, "Speaker1"))

        controller.dispatch(Action.AudioDevicesUpdated(inputs, outputs))
        runCurrent()

        assertEquals(inputs, controller.state.value.availableMics)
        assertEquals(outputs, controller.state.value.availableSpeakers)

        // 2. Device Selection
        controller.dispatch(Action.SetAudioInput(1))
        controller.dispatch(Action.SetAudioOutput(2))
        runCurrent()

        assertEquals(1, controller.state.value.selectedInputId)
        assertEquals(2, controller.state.value.selectedOutputId)
    }

    @Test
    fun `Error Handling - JoinGroupFailed clears session and sets error`() = testScope.runTest {
        // Setup: Active Join Attempt
        controller.dispatch(Action.JoinGroup("Hiking", "1234"))
        runCurrent()
        assertNotNull(controller.state.value.session)

        // Action: Driver reports auth failure
        controller.dispatch(Action.JoinGroupFailed("Access Code Rejected"))
        runCurrent()

        // Assert: Session cleared, Error populated
        assertNull("Session should be null after failure", controller.state.value.session)
        assertEquals("Access Code Rejected", controller.state.value.joinError)
    }

    @Test
    fun `Discovery - Advertisement lists update based on RSSI and MAC`() = testScope.runTest {
        val groupName = "Hiking"

        // 1. New Group Discovered
        val ad1 = com.denizetkar.walkietalkieapp.domain.DiscoveredGroup("MAC_1", groupName, -80, 100u, 101u, simulationTime)
        controller.dispatch(Action.AdvertisementSeen(ad1))
        runCurrent()
        assertEquals("MAC_1", controller.state.value.discoveredGroups.first().id)
        assertEquals(-80, controller.state.value.discoveredGroups.first().rssi)

        // 2. Same MAC, Better RSSI (Updates existing)
        val ad1Better = ad1.copy(rssi = -70)
        controller.dispatch(Action.AdvertisementSeen(ad1Better))
        runCurrent()
        assertEquals("MAC_1", controller.state.value.discoveredGroups.first().id)
        assertEquals(1, controller.state.value.discoveredGroups.size)
        assertEquals(-70, controller.state.value.discoveredGroups.first().rssi)

        // 3. Different MAC, Better RSSI (Replaces MAC_1 because name is the same)
        val ad2 = com.denizetkar.walkietalkieapp.domain.DiscoveredGroup("MAC_2", groupName, -60, 200u, 201u, simulationTime)
        controller.dispatch(Action.AdvertisementSeen(ad2))
        runCurrent()
        assertEquals("MAC_2", controller.state.value.discoveredGroups.first().id)
        assertEquals(1, controller.state.value.discoveredGroups.size)

        // 4. Different MAC, Worse RSSI (Ignored entirely)
        val ad3 = com.denizetkar.walkietalkieapp.domain.DiscoveredGroup("MAC_3", groupName, -90, 300u, 301u, simulationTime)
        controller.dispatch(Action.AdvertisementSeen(ad3))
        runCurrent()
        assertEquals("MAC_2", controller.state.value.discoveredGroups.first().id) // Unchanged
    }

    @Test
    fun `Discovery Eviction - Stale advertisements are cleared`() = testScope.runTest {
        // 1. Discover a group
        val ad = com.denizetkar.walkietalkieapp.domain.DiscoveredGroup("MAC", "Hiking", -50, 100u, 101u, simulationTime)
        controller.dispatch(Action.AdvertisementSeen(ad))
        runCurrent()
        assertEquals(1, controller.state.value.discoveredGroups.size)

        // 2. Advance time strictly past the timeout (6000ms)
        tick(Config.GROUP_ADVERTISEMENT_TIMEOUT + 100L)

        // 3. Assert it was evicted
        assertEquals("Stale group should be removed", 0, controller.state.value.discoveredGroups.size)
    }

    @Test
    fun `Auto-Connect - Respects Topology Rules and Target Peers constraint`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        // 1. Setup Session
        controller.dispatch(Action.CreateGroup("Hiking", "1234"))
        runCurrent()
        effects.clear()
        val myId = controller.state.value.myself

        // 2. Self-Reject: Ignore own ID
        controller.dispatch(Action.AdvertisementSeen(com.denizetkar.walkietalkieapp.domain.DiscoveredGroup("MAC", "Hiking", -50, myId, myId, simulationTime)))
        runCurrent()
        assertTrue("Should ignore self", effects.isEmpty())

        // 3. Simulate FULL capacity (3 peers)
        controller.dispatch(Action.PeerConnected(1u))
        controller.dispatch(Action.PeerConnected(2u))
        controller.dispatch(Action.PeerConnected(3u))
        runCurrent()

        // 4. Ignore Weak Roots when Full
        val weakRootId = if (myId > 0u) myId - 1u else return@runTest
        controller.dispatch(Action.AdvertisementSeen(com.denizetkar.walkietalkieapp.domain.DiscoveredGroup("MAC2", "Hiking", -50, weakRootId, weakRootId, simulationTime)))
        runCurrent()
        assertTrue("Should ignore weak root when full", effects.isEmpty())

        // 5. Connect to Better Roots EVEN WHEN FULL (Island Merging)
        val betterRootId = myId + 100u
        controller.dispatch(Action.AdvertisementSeen(com.denizetkar.walkietalkieapp.domain.DiscoveredGroup("MAC3", "Hiking", -50, betterRootId, betterRootId, simulationTime)))
        runCurrent()
        val connectEffect = effects.filterIsInstance<Effect.ConnectTo>().lastOrNull()
        assertNotNull("Should forcefully connect to a better root to merge islands", connectEffect)
        assertEquals(betterRootId, connectEffect?.targetNodeId)
    }

    @Test
    fun `Heartbeat - Updates Sequence Number of existing Root`() = testScope.runTest {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.effects.toList(effects)
        }

        controller.dispatch(Action.JoinGroup("Hiking", "1234"))
        runCurrent()
        effects.clear()

        // Ensure the incoming rootId is strictly greater than our random ID
        val myId = controller.state.value.myself
        val rootId = myId + 500u

        // 1. Initial Heartbeat establishes the Mesh Root
        val hb1 = Packet.Control.Heartbeat(rootId, 10, 0).toBytes()
        controller.dispatch(Action.PacketReceived(hb1, source = 10u, isControl = true))
        tick(100)

        assertTrue(controller.state.value.network is NetworkTopology.Mesh)
        assertEquals(10, (controller.state.value.network as NetworkTopology.Mesh).rootSeq)
        effects.clear() // Clear the relay effect

        // 2. Newer Heartbeat from the SAME Root
        val hb2 = Packet.Control.Heartbeat(rootId, 11, 0).toBytes()
        controller.dispatch(Action.PacketReceived(hb2, source = 10u, isControl = true))
        tick(100)

        // Assert: Sequence updated
        assertEquals(11, (controller.state.value.network as NetworkTopology.Mesh).rootSeq)

        // Assert: Heartbeat relayed
        val relayEffect = effects.filterIsInstance<Effect.Transmit>().lastOrNull()
        assertNotNull("Should relay updated heartbeat", relayEffect)
    }
}