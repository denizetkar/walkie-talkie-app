package com.denizetkar.walkietalkieapp.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.bluetooth.BleAdvertiserModule
import com.denizetkar.walkietalkieapp.bluetooth.BleDiscoveryModule
import com.denizetkar.walkietalkieapp.bluetooth.GattClientHandler
import com.denizetkar.walkietalkieapp.bluetooth.GattServerHandler
import com.denizetkar.walkietalkieapp.domain.Action
import com.denizetkar.walkietalkieapp.domain.AppState
import com.denizetkar.walkietalkieapp.domain.Effect
import com.denizetkar.walkietalkieapp.domain.SessionContext
import com.denizetkar.walkietalkieapp.domain.TransmissionStrategy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BleDriverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // Spies / Mocks
    private val actions = mutableListOf<Action>()
    private val stateFlow = MutableStateFlow(AppState(myself = 10u))
    private val effectFlow = MutableSharedFlow<Effect>()

    private lateinit var driver: BleDriver
    private lateinit var realAdapter: BluetoothAdapter

    // We control the events the Handlers emit to the Driver
    private val mockClientEvents = MutableSharedFlow<ClientEvent>()
    private val mockServerEvents = MutableSharedFlow<ServerEvent>()
    private val mockGattClientHandler = mockk<GattClientHandler>(relaxed = true)

    @Before
    fun setup() {
        ShadowLog.stream = System.out
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        realAdapter = manager.adapter
        Shadows.shadowOf(realAdapter).setEnabled(true)

        // Mock the Android Builders that Robolectric/Android Stub doesn't handle.
        mockModules()

        // Setup the Mock Client Handler
        every { mockGattClientHandler.clientEvents } returns mockClientEvents
        every { mockGattClientHandler.connect() } just runs
        every { mockGattClientHandler.disconnect() } just runs
        every { mockGattClientHandler.close() } just runs
        every { mockGattClientHandler.sendMessage(any(), any()) } just runs

        // Inject dependency via factory
        driver = BleDriver(
            context,
            testScope.backgroundScope,
            testDispatcher,
            clientHandlerFactory = { _, _, _, _, _, _ -> mockGattClientHandler },
            { action -> actions.add(action) },
        )
        driver.bind(stateFlow, effectFlow)
    }

    private fun mockModules() {
        mockkConstructor(GattServerHandler::class)
        every { anyConstructed<GattServerHandler>().startServer() } just runs
        every { anyConstructed<GattServerHandler>().stopServer() } just runs
        every { anyConstructed<GattServerHandler>().sendTo(any(), any(), any()) } just runs
        coEvery { anyConstructed<GattServerHandler>().disconnect(any()) } just runs
        every { anyConstructed<GattServerHandler>().serverEvents } returns mockServerEvents

        mockkConstructor(BleAdvertiserModule::class)
        every { anyConstructed<BleAdvertiserModule>().start(any()) } returns true
        every { anyConstructed<BleAdvertiserModule>().stop() } just runs

        mockkConstructor(BleDiscoveryModule::class)
        every { anyConstructed<BleDiscoveryModule>().start() } returns true
        every { anyConstructed<BleDiscoveryModule>().stop() } just runs
        every { anyConstructed<BleDiscoveryModule>().events } returns MutableSharedFlow()
    }

    @After
    fun tearDown() {
        driver.close()
        unmockkAll()
    }

    @Test
    fun `Scanning - Starts when isBrowsing becomes true`() = testScope.runTest {
        stateFlow.value = AppState(myself = 10u, isBrowsing = false)
        advanceUntilIdle()

        stateFlow.value = AppState(myself = 10u, isBrowsing = true)
        advanceUntilIdle()

        assertTrue("Should start scanning without error", actions.none { it is Action.ScanFailed })
    }

    @Test
    fun `Advertising - Starts when hosting a session`() = testScope.runTest {
        val session = SessionContext("Hiking", "1234", isJoinAttempt = false)
        stateFlow.value = AppState(myself = 10u, session = session)
        advanceUntilIdle()

        assertTrue("Should start advertising without error", actions.none { it is Action.JoinGroupFailed })
    }

    @Test
    fun `Advertising - Defers when joining (Client Mode)`() = testScope.runTest {
        // 1. Joining, no peers -> Should NOT advertise
        val session = SessionContext("Hiking", "1234", isJoinAttempt = true)
        stateFlow.value = AppState(myself = 10u, session = session, connectedPeers = emptySet())
        advanceUntilIdle()

        assertTrue(actions.isEmpty())

        // 2. Connected to a peer -> Should Start Advertising (Relay)
        stateFlow.value = AppState(myself = 10u, session = session, connectedPeers = setOf(99u))
        advanceUntilIdle()

        assertTrue("Should transition to advertising without error", actions.none { it is Action.JoinGroupFailed })
    }

    @Test
    fun `Connection - Handles ConnectTo Effect without crashing`() = testScope.runTest {
        val targetMac = "AA:BB:CC:DD:EE:FF"
        // Ensure device exists in shadow adapter
        realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext("Test", "1234", false))
        advanceUntilIdle()

        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u))
        advanceUntilIdle()

        // Verify we called connect() on the injected mock
        verify(exactly = 1) { mockGattClientHandler.connect() }
    }

    @Test
    fun `Connection - Handles GATT 133 Error gracefully`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        val device = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext("Test", "9999", false))
        advanceUntilIdle()

        // 1. Order Connection
        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u))
        advanceUntilIdle()

        // 2. Simulate Error from the Mock Handler
        testScope.launch {
            mockClientEvents.emit(ClientEvent.Error(device = device, reason = ConnectionFailure.Io("Status 133")))
        }
        advanceUntilIdle()

        // 3. Assert: The driver should NOT emit PeerConnected
        assertTrue(actions.none { it is Action.PeerConnected })
    }

    @Test
    fun `System Events - Turning off Bluetooth forces Leave Group`() = testScope.runTest {
        stateFlow.value = AppState(myself = 10u, session = SessionContext("Hiking", "1234", false))
        advanceUntilIdle()

        // 1. Send the broadcast
        val intent = Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
        }
        context.sendBroadcast(intent)

        // 2. CRITICAL: Force Robolectric to process the Android Main Thread queue
        ShadowLooper.shadowMainLooper().idle()
        // 3. Force Coroutines to process the resulting dispatch
        advanceUntilIdle()

        val leaveAction = actions.filterIsInstance<Action.LeaveGroup>().lastOrNull()
        assertNotNull("Should dispatch LeaveGroup when Bluetooth dies", leaveAction)
        assertTrue(leaveAction!!.reason.contains("Disabled"))
    }

    @Test
    fun `Client Connection - Authenticate, Receive Data, and Graceful Disconnect`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext("Test", "1234", false))
        advanceUntilIdle()

        // 1. Connect & Auth
        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u))
        advanceUntilIdle()

        mockClientEvents.emit(ClientEvent.Authenticated(mockDevice))
        advanceUntilIdle()

        assertTrue("Driver should dispatch PeerConnected", actions.contains(Action.PeerConnected(20u)))

        // 2. Receive Data
        val payload = byteArrayOf(0x01, 0x02)
        mockClientEvents.emit(ClientEvent.MessageReceived(mockDevice, payload, TransportDataType.CONTROL))
        advanceUntilIdle()

        assertTrue("Driver should route incoming packets", actions.contains(Action.PacketReceived(payload, 20u, true)))

        // 3. Disconnect
        mockClientEvents.emit(ClientEvent.Disconnected(mockDevice))
        advanceUntilIdle()

        assertTrue("Driver should dispatch PeerDisconnected", actions.contains(Action.PeerDisconnected(20u)))
    }

    @Test
    fun `Client Connection - Rejects on Auth Failure`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext("Test", "1234", false))
        advanceUntilIdle()

        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u))
        advanceUntilIdle()

        mockClientEvents.emit(ClientEvent.Error(mockDevice, ConnectionFailure.AuthRejected("Wrong Code")))
        advanceUntilIdle()

        val errorAction = actions.filterIsInstance<Action.JoinGroupFailed>().lastOrNull()
        assertNotNull("Should emit JoinGroupFailed on Auth Error", errorAction)
        assertTrue(errorAction!!.reason.contains("Rejected"))
    }

    @Test
    fun `Server Connection - Authenticate, Receive Data, and Disconnect`() = testScope.runTest {
        val targetMac = "AA:BB:CC:DD:EE:FF"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext("Test", "1234", false))
        advanceUntilIdle()

        // 1. Incoming Authentication
        mockServerEvents.emit(ServerEvent.ClientAuthenticated(mockDevice, 30u))
        advanceUntilIdle()

        assertTrue("Server should dispatch PeerConnected", actions.contains(Action.PeerConnected(30u)))

        // 2. Receive Audio Data
        val audioPayload = byteArrayOf(0xFF.toByte())
        mockServerEvents.emit(ServerEvent.MessageReceived(mockDevice, audioPayload, TransportDataType.AUDIO))
        advanceUntilIdle()

        assertTrue("Server should route incoming audio", actions.contains(Action.PacketReceived(audioPayload, 30u, false)))

        // 3. Disconnect
        mockServerEvents.emit(ServerEvent.ClientDisconnected(mockDevice))
        advanceUntilIdle()

        assertTrue("Server should dispatch PeerDisconnected", actions.contains(Action.PeerDisconnected(30u)))
    }

    @Test
    fun `Routing - Transmit respects Split Horizon (Loop Prevention)`() = testScope.runTest {
        stateFlow.value = AppState(myself = 10u, session = SessionContext("Test", "1234", false))
        advanceUntilIdle()

        // 1. Setup Client Connection (Node 20u)
        val clientDevice = realAdapter.getRemoteDevice("11:22:33:44:55:66")
        effectFlow.emit(Effect.ConnectTo(clientDevice.address, 20u, 10u))
        advanceUntilIdle()
        mockClientEvents.emit(ClientEvent.Authenticated(clientDevice))
        advanceUntilIdle()

        // 2. Setup Server Connection (Node 30u)
        val serverDevice = realAdapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        mockServerEvents.emit(ServerEvent.ClientAuthenticated(serverDevice, 30u))
        advanceUntilIdle()

        // 3. Action: Transmit payload, BUT exclude Node 20u (Because 20u sent it to us)
        val payload = byteArrayOf(0x01, 0x02)
        effectFlow.emit(Effect.Transmit(payload, TransmissionStrategy.FLOOD, isControl = false, excludedSource = 20u))
        advanceUntilIdle()

        // 4. Assert: Node 30 (Server) gets it. Node 20 (Client) does NOT.
        verify(exactly = 0) { mockGattClientHandler.sendMessage(any(), any()) }
        verify(exactly = 1) { anyConstructed<GattServerHandler>().sendTo(serverDevice, payload, TransportDataType.AUDIO) }
    }

    @Test
    fun `Collision Resolution - INCOMING vs OUTGOING Tie-Breaker keeps higher ID`() = testScope.runTest {
        // My ID is 10. Target ID is 50. Target > MyID, so Target wins the right to dictate connection.
        // Therefore, if Target connects to us (INCOMING), we accept it and kill our OUTGOING attempt.
        stateFlow.value = AppState(myself = 10u, session = SessionContext("Test", "1234", false))
        advanceUntilIdle()

        val targetMac = "11:22:33:44:55:66"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        // 1. Establish OUTGOING attempt to Node 50u
        effectFlow.emit(Effect.ConnectTo(targetMac, 50u, 10u))
        advanceUntilIdle()
        mockClientEvents.emit(ClientEvent.Authenticated(mockDevice))
        advanceUntilIdle()

        assertTrue(actions.contains(Action.PeerConnected(50u)))
        actions.clear() // Clear history

        // 2. Simulate INCOMING connection from Node 50u (Collision!)
        mockServerEvents.emit(ServerEvent.ClientAuthenticated(mockDevice, 50u))
        advanceUntilIdle()

        // Assert:
        // 1. The old outgoing job was cancelled, executing its `finally` block.
        // 2. However, because the new job overwrote the UUID in the registry, the `cleanupPeer` block
        //    aborts gracefully without emitting a PeerDisconnected action.
        assertTrue(
            "Should NOT emit PeerDisconnected during a graceful collision handoff",
            actions.none { it is Action.PeerDisconnected }
        )
        assertTrue(
            "Should emit PeerConnected for the new incoming session",
            actions.contains(Action.PeerConnected(50u))
        )
    }
}