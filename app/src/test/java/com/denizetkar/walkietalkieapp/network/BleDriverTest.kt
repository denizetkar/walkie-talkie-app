package com.denizetkar.walkietalkieapp.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.bluetooth.BleAdvertiserModule
import com.denizetkar.walkietalkieapp.bluetooth.BleDiscoveryModule
import com.denizetkar.walkietalkieapp.bluetooth.GattClientHandler
import com.denizetkar.walkietalkieapp.bluetooth.GattServerHandler
import com.denizetkar.walkietalkieapp.domain.Action
import com.denizetkar.walkietalkieapp.domain.AppError
import com.denizetkar.walkietalkieapp.domain.AppState
import com.denizetkar.walkietalkieapp.domain.Effect
import com.denizetkar.walkietalkieapp.domain.SessionContext
import com.denizetkar.walkietalkieapp.domain.TransmissionStrategy
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val session = SessionContext(100u, "Hiking", "1234", isJoinAttempt = false)
        stateFlow.value = AppState(myself = 10u, session = session)
        advanceUntilIdle()

        assertTrue("Should start advertising without error", actions.none { it is Action.JoinGroupFailed })
    }

    @Test
    fun `Scanning & Advertising - Active sessions always scan to allow self-healing`() = testScope.runTest {
        // NOTE: setup() initialized stateFlow with session = null.
        // This already caused discovery.stop() to be called exactly 1 time.

        // 1. Joining, no peers -> Should NOT advertise, BUT MUST scan
        val session = SessionContext(100u, "Hiking", "1234", isJoinAttempt = true)
        stateFlow.value = AppState(myself = 10u, session = session, connectedPeers = emptySet())
        advanceUntilIdle()

        verify(exactly = 1) { anyConstructed<BleDiscoveryModule>().start() }
        verify(exactly = 0) { anyConstructed<BleAdvertiserModule>().start(any()) }

        // 2. Connected to a peer -> Should Start Advertising, AND KEEP scanning
        stateFlow.value = AppState(myself = 10u, session = session, connectedPeers = setOf(99u))
        advanceUntilIdle()

        verify(exactly = 1) { anyConstructed<BleAdvertiserModule>().start(any()) }

        // applyDriverConfig is triggered again because isAdvertising changed.
        // So discovery.start() is called a 2nd time (BleDiscoveryModule handles this idempotently).
        verify(exactly = 2) { anyConstructed<BleDiscoveryModule>().start() }

        // Assert stop() was only called ONCE overall (during the initial setup configuration)
        // and NEVER called when transitioning to the advertising state.
        verify(exactly = 1) { anyConstructed<BleDiscoveryModule>().stop() }
    }

    @Test
    fun `Connection - Handles ConnectTo Effect without crashing`() = testScope.runTest {
        val targetMac = "AA:BB:CC:DD:EE:FF"
        // Ensure device exists in shadow adapter
        realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "1234"))
        advanceUntilIdle()

        // Verify we called connect() on the injected mock
        verify(exactly = 1) { mockGattClientHandler.connect() }
    }

    @Test
    fun `Connection - Handles GATT 133 Error gracefully`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        val device = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "9999", false))
        advanceUntilIdle()

        // 1. Order Connection
        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "9999"))
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
    fun `System Events - Turning off Bluetooth dispatches BluetoothStateChanged`() = testScope.runTest {
        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Hiking", "1234", false))
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

        val stateChangeAction = actions.filterIsInstance<Action.BluetoothStateChanged>().lastOrNull()
        assertNotNull("Should dispatch BluetoothStateChanged when Bluetooth dies", stateChangeAction)
        assertFalse(stateChangeAction!!.enabled)
    }

    @Test
    fun `Client Connection - Authenticate, Receive Data, and Graceful Disconnect`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        // 1. Connect & Auth
        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "1234"))
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

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "1234"))
        advanceUntilIdle()

        mockClientEvents.emit(ClientEvent.Error(mockDevice, ConnectionFailure.AuthRejected("Wrong Code")))
        advanceUntilIdle()

        val errorAction = actions.filterIsInstance<Action.JoinGroupFailed>().lastOrNull()
        assertNotNull("Should emit JoinGroupFailed on Auth Error", errorAction)
        assertEquals(AppError.AccessCodeRejected, errorAction!!.error)
    }

    @Test
    fun `Server Connection - Authenticate, Receive Data, and Disconnect`() = testScope.runTest {
        val targetMac = "AA:BB:CC:DD:EE:FF"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
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
        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        // 1. Setup Client Connection (Node 20u)
        val clientDevice = realAdapter.getRemoteDevice("11:22:33:44:55:66")
        effectFlow.emit(Effect.ConnectTo(clientDevice.address, 20u, 10u, "1234"))
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
    fun `Collision Resolution - Innocent Kill Prevention (New Incoming survives Old Outgoing death)`() = testScope.runTest {
        // My ID is 10. Target ID is 50. Target > MyID, so Target wins the right to dictate connection.
        // Therefore, if Target connects to us (INCOMING), we accept it and kill our OUTGOING attempt.
        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        val oldMac = "11:22:33:44:55:66"
        val oldDevice = realAdapter.getRemoteDevice(oldMac)

        val newMac = "AA:BB:CC:DD:EE:FF"
        val newDevice = realAdapter.getRemoteDevice(newMac)

        // 1. Establish OUTGOING attempt to Node 50u (MAC: 11:22:...)
        effectFlow.emit(Effect.ConnectTo(oldMac, 50u, 10u, "1234"))
        advanceUntilIdle()
        mockClientEvents.emit(ClientEvent.Authenticated(oldDevice))
        advanceUntilIdle()

        assertTrue(actions.contains(Action.PeerConnected(50u)))
        actions.clear() // Clear history

        // 2. Simulate INCOMING connection from Node 50u (MAC: AA:BB:...) (Collision!)
        mockServerEvents.emit(ServerEvent.ClientAuthenticated(newDevice, 50u))
        advanceUntilIdle()

        assertTrue(
            "Should emit PeerConnected for the new incoming session",
            actions.contains(Action.PeerConnected(50u))
        )
        actions.clear()

        // 3. CRITICAL CONTRACT: Simulate OS dropping the OLD connection
        mockClientEvents.emit(ClientEvent.Disconnected(oldDevice))
        advanceUntilIdle()

        // 4. Assert NO disconnect is routed to the Core
        assertTrue(
            "Should NOT emit PeerDisconnected when the old zombie connection drops",
            actions.none { it is Action.PeerDisconnected }
        )
    }

    @Test
    fun `Collision Resolution - Zombie Prevention (Rejects INCOMING and disconnects if OUTGOING wins)`() = testScope.runTest {
        // My ID is 50. Target ID is 10. MyID > Target, so I win the right to dictate connection.
        // Therefore, if Target connects to us (INCOMING), we reject it and keep our OUTGOING attempt.
        stateFlow.value = AppState(myself = 50u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        val outgoingMac = "11:22:33:44:55:66"
        val outgoingDevice = realAdapter.getRemoteDevice(outgoingMac)

        val incomingMac = "AA:BB:CC:DD:EE:FF"
        val incomingDevice = realAdapter.getRemoteDevice(incomingMac)

        // 1. Establish OUTGOING attempt to Node 10u
        effectFlow.emit(Effect.ConnectTo(outgoingMac, 10u, 50u, "1234"))
        advanceUntilIdle()
        mockClientEvents.emit(ClientEvent.Authenticated(outgoingDevice))
        advanceUntilIdle()

        assertTrue(actions.contains(Action.PeerConnected(10u)))
        actions.clear()

        // 2. Simulate INCOMING connection from Node 10u (Collision!)
        mockServerEvents.emit(ServerEvent.ClientAuthenticated(incomingDevice, 10u))
        advanceUntilIdle()

        // 3. CRITICAL CONTRACT: The Driver MUST ask the Server to disconnect the rejected incoming connection
        coVerify(exactly = 1) {
            anyConstructed<GattServerHandler>().disconnect(incomingDevice)
        }

        // 4. Assert NO state changes are routed to the Core
        assertTrue(
            "Should ignore the incoming connection entirely at the Core level",
            actions.none { it is Action.PeerConnected || it is Action.PeerDisconnected }
        )
    }

    @Test
    fun `Connection - Ignores Duplicate Outgoing Connection`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        // 1. Establish first OUTGOING attempt to Node 50u
        effectFlow.emit(Effect.ConnectTo(targetMac, 50u, 10u, "1234"))
        advanceUntilIdle()

        // 2. Emit another ConnectTo for the SAME target and SAME direction
        effectFlow.emit(Effect.ConnectTo(targetMac, 50u, 10u, "1234"))
        advanceUntilIdle()

        // Verify connect() was only called ONCE for this client handler mock
        verify(exactly = 1) { mockGattClientHandler.connect() }
    }

    @Test
    fun `Server Connection - Ignores unknown devices safely`() = testScope.runTest {
        val unknownDevice = realAdapter.getRemoteDevice("FF:EE:DD:CC:BB:AA")

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        // 1. Message Received from an unknown device
        mockServerEvents.emit(ServerEvent.MessageReceived(unknownDevice, byteArrayOf(), TransportDataType.CONTROL))
        advanceUntilIdle()

        // 2. Disconnect from an unknown device
        mockServerEvents.emit(ServerEvent.ClientDisconnected(unknownDevice))
        advanceUntilIdle()

        // Assert no dispatch happened
        assertTrue("Should ignore data from unknown peer", actions.none { it is Action.PacketReceived })
        assertTrue("Should ignore disconnect from unknown peer", actions.none { it is Action.PeerDisconnected })
    }

    @Test
    fun `Client Connection - Handles Non-Auth Error`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "1234"))
        advanceUntilIdle()

        // Emit IO Error instead of AuthRejected
        mockClientEvents.emit(ClientEvent.Error(mockDevice, ConnectionFailure.Io("Connection lost")))
        advanceUntilIdle()

        // Should NOT emit JoinGroupFailed (that is strictly reserved for AuthRejected)
        assertTrue(actions.none { it is Action.JoinGroupFailed })
    }

    @Test
    fun `Client Connection - Polite Disconnect Exception caught safely`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "1234"))
        advanceUntilIdle()

        mockClientEvents.emit(ClientEvent.Authenticated(mockDevice))
        advanceUntilIdle()

        // Mock disconnect to throw an exception
        every { mockGattClientHandler.disconnect() } throws RuntimeException("Stack crash")

        // Leave group to trigger the polite teardown
        stateFlow.value = AppState(myself = 10u, session = null)
        advanceUntilIdle()

        // Assert it handled the exception and still called close()
        verify(exactly = 1) { mockGattClientHandler.close() }
    }

    @Test
    fun `Config Changes - Stops Scanning and Advertising when Bluetooth disabled`() = testScope.runTest {
        // Start a session
        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false), isBluetoothEnabled = true)
        advanceUntilIdle()
        verify { anyConstructed<BleAdvertiserModule>().start(any()) }

        // Disable Bluetooth
        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false), isBluetoothEnabled = false)
        advanceUntilIdle()

        // Verify the modules are told to spin down
        verify { anyConstructed<BleAdvertiserModule>().stop() }
        verify { anyConstructed<BleDiscoveryModule>().stop() }
        verify { anyConstructed<GattServerHandler>().stopServer() }
    }

    @Test
    fun `Config Changes - Stops Scanning and Advertising when session ends`() = testScope.runTest {
        // Start a session
        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()
        verify { anyConstructed<BleAdvertiserModule>().start(any()) }

        // Turn on browsing
        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false), isBrowsing = true)
        advanceUntilIdle()
        verify { anyConstructed<BleDiscoveryModule>().start() }

        // End session and browsing
        stateFlow.value = AppState(myself = 10u, session = null, isBrowsing = false)
        advanceUntilIdle()

        // Verify the modules are told to spin down
        verify { anyConstructed<BleAdvertiserModule>().stop() }
        verify { anyConstructed<BleDiscoveryModule>().stop() }
    }

    @Test
    fun `Server Connection - Zombie Connection Fuse Disconnects Unauthenticated Client`() = testScope.runTest {
        val targetMac = "AA:BB:CC:DD:EE:FF"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        // 1. Unauthenticated Client Connects
        mockServerEvents.emit(ServerEvent.ClientConnected(mockDevice))
        advanceUntilIdle()

        // 2. Fast forward past the timeout limit
        advanceTimeBy(Config.BLE_CONNECT_TIMEOUT + 500L)
        runCurrent()

        // 3. Verify BleDriver asked the ServerHandler to disconnect the zombie
        coVerify(exactly = 1) { anyConstructed<GattServerHandler>().disconnect(mockDevice) }
    }

    @Test
    fun `Server Connection - Outgoing Connection ignores Security Fuse`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        // 1. Initiate Outgoing Connection (Driver adds it to managed peers)
        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "1234"))
        advanceUntilIdle()

        // 2. Android Quirk: Server ALSO fires ClientConnected for the outgoing MAC
        mockServerEvents.emit(ServerEvent.ClientConnected(mockDevice))
        advanceUntilIdle()

        // 3. Fast forward past the timeout limit
        advanceTimeBy(Config.BLE_CONNECT_TIMEOUT + 500L)
        runCurrent()

        // 4. Verify the driver recognized it was managed and DID NOT disconnect it
        coVerify(exactly = 0) { anyConstructed<GattServerHandler>().disconnect(mockDevice) }
    }

    @Test
    fun `Hardware Start Failures - Dispatches JoinGroupFailed and ScanFailed`() = testScope.runTest {
        // 1. Override the mocks to simulate Android rejecting the hardware start requests
        every { anyConstructed<BleAdvertiserModule>().start(any()) } returns false
        every { anyConstructed<BleDiscoveryModule>().start() } returns false

        // 2. Trigger configuration update (Hosting a session + Browsing)
        val session = SessionContext(100u, "FailGroup", "1234", isJoinAttempt = false)
        stateFlow.value = AppState(myself = 10u, session = session, isBrowsing = true)
        advanceUntilIdle()

        // 3. Assert the driver notified the Core of the failures
        assertTrue(
            "Should dispatch JoinGroupFailed when advertising fails",
            actions.contains(Action.JoinGroupFailed(AppError.BluetoothRadioUnavailable))
        )
        val scanFailAction = actions.filterIsInstance<Action.ScanFailed>().lastOrNull()
        assertNotNull("Should dispatch ScanFailed", scanFailAction)
        assertEquals(AppError.BluetoothScannerUnavailable, scanFailAction!!.error)
    }

    @Test
    fun `Discovery Event Bridge - Maps events to Core Actions`() = testScope.runTest {
        // To accurately capture and emit to the specific Flow instance used by the driver,
        // we override the mock and instantiate a local driver specifically for this test.
        val localEventsFlow = MutableSharedFlow<DiscoveryEvent>()
        every { anyConstructed<BleDiscoveryModule>().events } returns localEventsFlow

        val localDriver = BleDriver(
            context,
            testScope.backgroundScope,
            testDispatcher,
            clientHandlerFactory = { _, _, _, _, _, _ -> mockGattClientHandler },
            dispatch = { actions.add(it) }
        )

        // 1. Test NodeFound bridging
        val node = TransportNode("AA:BB:CC:DD:EE:FF", 100u, "Test", -50, 1u, 2u, 3, true)
        localEventsFlow.emit(DiscoveryEvent.NodeFound(node))
        advanceUntilIdle()

        val adAction = actions.filterIsInstance<Action.AdvertisementSeen>().lastOrNull()
        assertNotNull("Should bridge NodeFound to AdvertisementSeen", adAction)
        assertEquals("AA:BB:CC:DD:EE:FF", adAction?.group?.id)

        // 2. Test ScanFailed bridging
        localEventsFlow.emit(DiscoveryEvent.ScanFailed(6)) // Error code 6 (Too Frequent)
        advanceUntilIdle()

        val scanAction = actions.filterIsInstance<Action.ScanFailed>().lastOrNull()
        assertNotNull("Should bridge ScanFailed event", scanAction)
        assertTrue(scanAction!!.error is AppError.BluetoothScannerFailed)
        assertEquals(6, (scanAction.error as AppError.BluetoothScannerFailed).errorCode)

        localDriver.close()
    }

    @Test
    fun `Connection Guard - Ignores ConnectTo if Bluetooth is off`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        realAdapter.getRemoteDevice(targetMac)

        // Force Bluetooth to OFF via Robolectric Shadow
        Shadows.shadowOf(realAdapter).setEnabled(false)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        // Attempt connection
        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "1234"))
        advanceUntilIdle()

        // Assert early exit prevented the connection attempt
        verify(exactly = 0) { mockGattClientHandler.connect() }
    }

    @Test
    fun `Polite Disconnect - Times out gracefully if stack hangs`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        val mockDevice = realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        // 1. Establish the connection so the peer job is running
        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "1234"))
        advanceUntilIdle()
        mockClientEvents.emit(ClientEvent.Authenticated(mockDevice))
        advanceUntilIdle()

        // 2. Trigger the teardown sequence (Leaving the group)
        stateFlow.value = AppState(myself = 10u, session = null)
        advanceUntilIdle()

        // 3. We intentionally DO NOT emit ClientEvent.Disconnected here.
        // This simulates the Android BLE stack completely hanging and never firing the callback.

        // Fast-forward time past the driver's internal polite timeout
        advanceTimeBy(Config.PEER_DISCONNECT_TIMEOUT + 500L)
        runCurrent()

        // 4. Assert that the driver still safely closed the connection
        verify(exactly = 1) { mockGattClientHandler.close() }
    }

    @Test
    fun `System Events - Turning ON Bluetooth dispatches BluetoothStateChanged`() = testScope.runTest {
        // Clear history from the initialization block
        actions.clear()

        // 1. Simulate the Android system turning Bluetooth ON
        val intent = Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
        }
        context.sendBroadcast(intent)

        // 2. Process the broadcast queue and coroutines
        ShadowLooper.shadowMainLooper().idle()
        advanceUntilIdle()

        // 3. Assert the driver passed the state ON up to the Core
        val stateChangeAction = actions.filterIsInstance<Action.BluetoothStateChanged>().lastOrNull()
        assertNotNull("Should dispatch BluetoothStateChanged", stateChangeAction)
        assertTrue("Enabled flag should be true", stateChangeAction!!.enabled)
    }

    @Test
    fun `Peer Job - Catastrophic Exception cleans up safely`() = testScope.runTest {
        val targetMac = "11:22:33:44:55:66"
        realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(myself = 10u, session = SessionContext(100u, "Test", "1234", false))
        advanceUntilIdle()

        // 1. Mock the client handler to throw an unexpected RuntimeException
        every { mockGattClientHandler.connect() } throws RuntimeException("Simulated catastrophic failure")

        // 2. Try to connect
        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u, "1234"))

        // 3. Fast-forward virtual time past the polite disconnect timeout
        // and force the NonCancellable context switch to execute.
        advanceTimeBy(Config.PEER_DISCONNECT_TIMEOUT + 1000L)
        runCurrent()
        advanceUntilIdle()

        // 4. Assert the generic try-catch-finally caught it and dispatched PeerDisconnected
        assertTrue(
            "Driver should emit PeerDisconnected to clean up the zombie peer",
            actions.contains(Action.PeerDisconnected(20u))
        )
    }
}