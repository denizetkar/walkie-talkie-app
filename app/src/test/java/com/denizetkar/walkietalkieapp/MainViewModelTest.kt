package com.denizetkar.walkietalkieapp

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import app.cash.turbine.test
import com.denizetkar.walkietalkieapp.domain.Action
import com.denizetkar.walkietalkieapp.domain.AppState
import com.denizetkar.walkietalkieapp.domain.Effect
import com.denizetkar.walkietalkieapp.domain.SessionContext
import com.denizetkar.walkietalkieapp.logic.MeshController
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application = mockk<Application>(relaxed = true)
    private val service = mockk<WalkieTalkieService>(relaxed = true)
    private val binder = mockk<WalkieTalkieService.LocalBinder>(relaxed = true)
    private val controller = mockk<MeshController>(relaxed = true)

    // Reactive State mocks
    private val controllerState = MutableStateFlow(AppState(myself = 1u))
    private val controllerEffects = MutableSharedFlow<Effect>()
    private val serviceControllerState = MutableStateFlow<MeshController?>(null)

    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        // Wiring Mocks
        every { binder.getService() } returns service
        every { service.meshControllerState } returns serviceControllerState
        every { controller.state } returns controllerState
        every { controller.effects } returns controllerEffects

        // Initialize ViewModel
        viewModel = MainViewModel(application, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    /**
     * Helper to simulate the Service Binding process.
     * This captures the ServiceConnection passed to bindService and triggers onServiceConnected.
     */
    private fun connectService() {
        // 1. Grant Permissions
        viewModel.onPermissionsGranted()

        val connectionSlot = slot<ServiceConnection>()
        verify {
            application.bindService(
                any(),
                capture(connectionSlot),
                Context.BIND_AUTO_CREATE
            )
        }

        // 2. Mock the Binder connection
        connectionSlot.captured.onServiceConnected(
            ComponentName(application, WalkieTalkieService::class.java),
            binder
        )

        // 3. Mock the Service initializing the Controller
        // Because we use UnconfinedTestDispatcher, this updates the state synchronously immediately.
        serviceControllerState.value = controller
    }

    @Test
    fun `Permissions - Granting permissions triggers Service Binding`() = runTest {
        viewModel.onPermissionsGranted()

        verify {
            application.startService(any<Intent>())
            application.bindService(any(), any(), Context.BIND_AUTO_CREATE)
        }

        viewModel.appState.test {
            val state = awaitItem()
            assertTrue(state.hasPermissions)
            assertFalse(state.isServiceBound)
        }
    }

    @Test
    fun `Service Connection - Binds and subscribes to Controller`() = runTest {
        // In this test, we call connectService() INSIDE the turbine block.
        // Therefore, we DO see all the transitions.
        viewModel.appState.test {
            awaitItem() // 1. Initial (Empty)

            connectService()

            // 2. Permissions Granted
            val permissionState = awaitItem()
            assertTrue(permissionState.hasPermissions)

            // 3. Service Bound
            val boundState = awaitItem()
            assertTrue(boundState.isServiceBound)

            // 4. Core Sync
            // When controller is attached, we get the initial state from controllerState flow
            val coreSyncState = awaitItem()
            assertTrue("Should sync core state", coreSyncState.isServiceBound)
            // By default, session is null, so isScanning becomes true
            assertTrue(coreSyncState.isScanning)
        }
    }

    @Test
    fun `State Mapping - Core AppState maps correctly to UI State`() = runTest {
        // Setup: Connect first. State flows settle to "Ready".
        connectService()

        viewModel.appState.test {
            // Because we connected BEFORE testing, we only get the CURRENT state.
            // We do NOT get the history (Permissions->Bound->Sync).
            val initialState = awaitItem()
            assertTrue("Should start in ready state", initialState.isServiceBound)

            // ACTION: Update Core State
            val session = SessionContext("Hiking", "9999", isJoinAttempt = false)
            controllerState.value = AppState(
                myself = 1u,
                session = session,
                connectedPeers = setOf(2u, 3u),
                isBluetoothEnabled = false,
            )

            // ASSERT: UI updates to match
            val uiState = awaitItem()
            assertEquals("Hiking", uiState.groupName)
            assertEquals("9999", uiState.accessCode)
            assertEquals(2, uiState.peerCount)
            assertEquals(false, uiState.isBluetoothEnabled)
        }
    }

    @Test
    fun `Join Logic - Join Action dispatches to Core and updates UI on Core response`() = runTest {
        connectService()

        viewModel.appState.test {
            awaitItem() // Consume current "Ready" state

            // ACTION: User taps join
            viewModel.joinGroup("Camp", "1234")

            // ASSERT: Action was dispatched to the Core
            verify { binder.dispatchAction(Action.JoinGroup("Camp", "1234")) }

            // SIMULATE CORE REACTION: The Core creates a Join session
            controllerState.value = AppState(
                myself = 1u,
                session = SessionContext("Camp", "1234", isJoinAttempt = true)
            )

            // ASSERT: UI reacts to the Core's state change
            val joiningState = awaitItem()
            assertTrue(joiningState.isJoining)
            assertNull(joiningState.joinError)
        }
    }

    @Test
    fun `Error Handling - Maps Join Error from Core`() = runTest {
        connectService()

        viewModel.appState.test {
            awaitItem() // Consume current "Ready" state

            // ACTION: Core reports error
            controllerState.value = AppState(
                myself = 1u,
                joinError = "Timeout"
            )

            // ASSERT: UI shows error
            val errorState = awaitItem()
            assertEquals("Timeout", errorState.joinError)
        }
    }

    @Test
    fun `Leave Group - Clears local state and dispatches action`() = runTest {
        connectService()

        // Setup: In a group
        controllerState.value = AppState(
            myself = 1u,
            session = SessionContext("Test", "1111", false)
        )

        viewModel.leaveGroup()

        verify { binder.dispatchAction(Action.LeaveGroup()) }
    }

    @Test
    fun `Actions - Scanning actions dispatch correctly`() = runTest {
        connectService()

        viewModel.startScanning()
        verify { binder.dispatchAction(Action.StartScanning) }

        viewModel.stopScanning()
        verify { binder.dispatchAction(Action.StopScanning) }
    }

    @Test
    fun `Actions - Audio toggle actions dispatch correctly`() = runTest {
        connectService()

        viewModel.startTalking()
        verify { binder.dispatchAction(Action.SetMic(true)) }

        viewModel.stopTalking()
        verify { binder.dispatchAction(Action.SetMic(false)) }
    }

    @Test
    fun `Actions - Device selection actions dispatch correctly`() = runTest {
        connectService()

        viewModel.setAudioDevice(42)
        verify { binder.dispatchAction(Action.SetAudioDevice(42)) }
    }

    @Test
    fun `Actions - Create Group triggers generation of random code and dispatch`() = runTest {
        connectService()

        viewModel.createGroup("Hiking")

        // We capture the action to verify the random code logic
        val actionSlot = slot<Action.CreateGroup>()
        verify { binder.dispatchAction(capture(actionSlot)) }

        assertEquals("Hiking", actionSlot.captured.name)
        assertTrue("Generated code should be 4 digits", actionSlot.captured.code.matches(Regex("\\d{4}")))
    }

    @Test
    fun `Lifecycle - onCleared unbinds the service`() = runTest {
        connectService() // Connects and marks isServiceBound = true

        // ViewModel.onCleared() is protected. We use reflection to invoke it
        // just as the Android framework would when the Activity dies.
        val onClearedMethod = MainViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(viewModel)

        verify { application.unbindService(any()) }
    }

    @Test
    fun `Factory - Creates ViewModel with Application injected`() {
        val extras = androidx.lifecycle.viewmodel.MutableCreationExtras().apply {
            set(androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, application)
        }

        val generatedViewModel = MainViewModel.Factory.create(MainViewModel::class.java, extras)

        org.junit.Assert.assertNotNull("Factory should successfully create the ViewModel", generatedViewModel)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}