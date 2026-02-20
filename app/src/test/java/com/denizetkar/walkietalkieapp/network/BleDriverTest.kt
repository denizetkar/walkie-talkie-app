package com.denizetkar.walkietalkieapp.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.bluetooth.BleAdvertiserModule
import com.denizetkar.walkietalkieapp.bluetooth.BleDiscoveryModule
import com.denizetkar.walkietalkieapp.bluetooth.GattClientHandler
import com.denizetkar.walkietalkieapp.bluetooth.GattServerHandler
import com.denizetkar.walkietalkieapp.domain.Action
import com.denizetkar.walkietalkieapp.domain.AppState
import com.denizetkar.walkietalkieapp.domain.Effect
import com.denizetkar.walkietalkieapp.domain.SessionContext
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLog

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

    // We control the events the ClientHandler emits to the Driver
    private val mockClientEvents = MutableSharedFlow<ClientEvent>()
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
        every { mockGattClientHandler.close() } just runs

        // Inject dependency via factory
        driver = BleDriver(
            context,
            testScope.backgroundScope,
            testDispatcher,
            clientHandlerFactory = { _, _, _, _, _ -> mockGattClientHandler },
            { action -> actions.add(action) },
        )
        driver.bind(stateFlow, effectFlow)
    }

    private fun mockModules() {
        // We still need to mock these to prevent them from doing real work
        mockkConstructor(GattServerHandler::class)
        every { anyConstructed<GattServerHandler>().startServer() } just runs
        every { anyConstructed<GattServerHandler>().stopServer() } just runs
        every { anyConstructed<GattServerHandler>().serverEvents } returns MutableSharedFlow()

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
        stateFlow.value = AppState(
            myself = 10u,
            session = session,
            connectedPeers = emptySet()
        )
        advanceUntilIdle()

        assertTrue(actions.isEmpty())

        // 2. Connected to a peer -> Should Start Advertising (Relay)
        stateFlow.value = AppState(
            myself = 10u,
            session = session,
            connectedPeers = setOf(99u)
        )
        advanceUntilIdle()

        assertTrue("Should transition to advertising without error", actions.none { it is Action.JoinGroupFailed })
    }

    @Test
    fun `Connection - Handles ConnectTo Effect without crashing`() = testScope.runTest {
        val targetMac = "AA:BB:CC:DD:EE:FF"
        // Ensure device exists in shadow adapter
        realAdapter.getRemoteDevice(targetMac)

        stateFlow.value = AppState(
            myself = 10u,
            session = SessionContext("Test", "1234", false)
        )
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

        stateFlow.value = AppState(
            myself = 10u,
            session = SessionContext("Test", "9999", false)
        )
        advanceUntilIdle()

        // 1. Order Connection
        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u))
        advanceUntilIdle()

        // 2. Simulate Error from the Mock Handler
        testScope.launch {
            mockClientEvents.emit(
                ClientEvent.Error(
                    device = device,
                    reason = ConnectionFailure.Io("Status 133")
                )
            )
        }
        advanceUntilIdle()

        // 3. Assert: The driver should NOT emit PeerConnected
        assertTrue(actions.none { it is Action.PeerConnected })
    }
}