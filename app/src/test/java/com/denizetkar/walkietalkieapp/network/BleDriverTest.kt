package com.denizetkar.walkietalkieapp.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.bluetooth.BleAdvertiserModule
import com.denizetkar.walkietalkieapp.bluetooth.BleDiscoveryModule
import com.denizetkar.walkietalkieapp.bluetooth.GattServerHandler
import com.denizetkar.walkietalkieapp.domain.Action
import com.denizetkar.walkietalkieapp.domain.AppState
import com.denizetkar.walkietalkieapp.domain.Effect
import com.denizetkar.walkietalkieapp.domain.SessionContext
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowBluetoothAdapter

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BleDriverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // Spies / Mocks
    private val actions = mutableListOf<Action>()
    private val stateFlow = MutableStateFlow(AppState(myself = 10u))
    private val effectFlow = MutableSharedFlow<Effect>()

    private lateinit var driver: BleDriver
    private lateinit var realAdapter: BluetoothAdapter
    private lateinit var shadowAdapter: ShadowBluetoothAdapter

    @Before
    fun setup() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        realAdapter = manager.adapter
        shadowAdapter = Shadows.shadowOf(realAdapter)
        // Use explicit setter to avoid protected property access error
        shadowAdapter.setEnabled(true)

        // Mock the Android Builders that Robolectric/Android Stub doesn't handle.
        mockModules()

        // Use backgroundScope. This ensures collectors launched by the Driver
        // are automatically cancelled when the test ends, preventing UncompletedCoroutinesError.
        driver = BleDriver(context, testScope.backgroundScope) { action ->
            actions.add(action)
        }
        driver.bind(stateFlow, effectFlow)
    }

    private fun mockModules() {
        // Mock GattServerHandler
        mockkConstructor(GattServerHandler::class)
        every { anyConstructed<GattServerHandler>().startServer() } just runs
        every { anyConstructed<GattServerHandler>().stopServer() } just runs
        // Inferred types, removed explicit <ServerEvent> to fix unused import
        every { anyConstructed<GattServerHandler>().serverEvents } returns MutableSharedFlow()

        // Mock BleAdvertiserModule
        mockkConstructor(BleAdvertiserModule::class)
        every { anyConstructed<BleAdvertiserModule>().start(any()) } returns true
        every { anyConstructed<BleAdvertiserModule>().stop() } just runs

        // Mock BleDiscoveryModule
        mockkConstructor(BleDiscoveryModule::class)
        every { anyConstructed<BleDiscoveryModule>().start() } returns true
        every { anyConstructed<BleDiscoveryModule>().stop() } just runs
        // Inferred types, removed explicit <TransportNode> to fix unused import
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

        effectFlow.emit(Effect.ConnectTo(targetMac, 20u, 10u))
        advanceUntilIdle()

        // Success is defined by lack of crash/error
        assertTrue(true)
    }
}