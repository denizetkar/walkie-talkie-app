package com.denizetkar.walkietalkieapp

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.logic.VoiceManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MainApplication::class)
class MainActivityTest {

    /**
     * Helper function to launch the Activity with a fully mocked ViewModel.
     * This bypasses the real Service bindings and allows us to test the UI deterministically.
     */
    private fun launchWithMockViewModel(
        initialState: AppUiState,
        block: (MainViewModel, MutableStateFlow<AppUiState>) -> Unit
    ) {
        val mockViewModel = mockk<MainViewModel>(relaxed = true)
        val stateFlow = MutableStateFlow(initialState)
        every { mockViewModel.appState } returns stateFlow

        // Intercept the ViewModel creation
        mockkObject(MainViewModel.Companion)
        try {
            every { MainViewModel.Factory } returns object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: androidx.lifecycle.viewmodel.CreationExtras
                ): T {
                    return mockViewModel as T
                }
            }

            ActivityScenario.launch(MainActivity::class.java).use {
                block(mockViewModel, stateFlow)
            }
        } finally {
            unmockkObject(MainViewModel.Companion)
        }
    }

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Before
    fun setup() {
        // Prevent JNI crashes in Robolectric by mocking the UniFFI bridge
        mockkStatic("uniffi.walkie_talkie_engine.Walkie_talkie_engineKt")
        every { uniffi.walkie_talkie_engine.initLogger() } just Runs

        // Prevent the Service from running the real VoiceManager,
        // which attempts to load the Rust .so/.dll via JNA and crashes the background coroutine.
        mockkConstructor(VoiceManager::class)
        every { anyConstructed<VoiceManager>().bind(any(), any()) } just Runs
        every { anyConstructed<VoiceManager>().renderAudio(any()) } just Runs
        every { anyConstructed<VoiceManager>().close() } just Runs

        val app = ApplicationProvider.getApplicationContext<Application>()
        val serviceIntent = Intent(app, WalkieTalkieService::class.java)

        val service = Robolectric.buildService(WalkieTalkieService::class.java).create().get()
        val binder = service.onBind(serviceIntent)

        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, WalkieTalkieService::class.java),
            binder
        )
    }

    @After
    fun tearDown() {
        unmockkAll() // This cleans up mockkConstructor to prevent cross-test pollution
    }

    @Test
    fun `Permissions Denied - Shows Permission Required Screen`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeTestRule.onNodeWithStringId(R.string.permission_required_title).assertIsDisplayed()
        }
    }

    @Test
    fun `Navigation - Bottom Bar routes between Create and Join when permissions granted`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                composeTestRule.onAllNodesWithStringId(R.string.create_group_title).fetchSemanticsNodes().isNotEmpty()
            }

            composeTestRule.onNodeWithStringId(R.string.create_group_title).assertIsDisplayed()

            composeTestRule.onNodeWithStringId(R.string.navigation_join).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithStringId(R.string.join_group_nearby_groups_title).assertIsDisplayed()

            composeTestRule.onNodeWithStringId(R.string.navigation_create).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithStringId(R.string.create_group_title).assertIsDisplayed()
        }
    }

    @Test
    fun `App State - Displays Loading and Error screens based on UI State`() {
        // Start in "Loading" state (isServiceBound = false)
        val initialState = AppUiState(
            hasPermissions = true,
            isServiceBound = false,
            serviceStartupFailed = false
        )

        launchWithMockViewModel(initialState) { mockViewModel, stateFlow ->
            // Assert Loading screen is shown
            composeTestRule.onNodeWithStringId(R.string.starting_audio_engine).assertIsDisplayed()

            // Update to "Error" state
            stateFlow.value = initialState.copy(serviceStartupFailed = true)
            composeTestRule.waitForIdle()

            // Assert Error screen is shown and retry works
            composeTestRule.onNodeWithStringId(R.string.service_error_screen_title).assertIsDisplayed()
            composeTestRule.onNodeWithStringId(R.string.service_error_screen_button).performClick()
            composeTestRule.waitForIdle()

            io.mockk.verify { mockViewModel.retryConnection() }
        }
    }

    @Test
    fun `Join Screen - Manages Scanning Lifecycle via DisposableEffect`() {
        val initialState = AppUiState(
            hasPermissions = true,
            isServiceBound = true,
            groupName = null, // Null groupName means we start on the Create/Join tabs
        )

        launchWithMockViewModel(initialState) { mockViewModel, _ ->
            // By default, the NavHost starts on "create".
            composeTestRule.onNodeWithStringId(R.string.create_group_title).assertIsDisplayed()

            // 1. Navigate to "Join"
            composeTestRule.onNodeWithStringId(R.string.navigation_join).performClick()
            composeTestRule.waitForIdle()

            // Verify the DisposableEffect triggered startScanning
            io.mockk.verify { mockViewModel.startScanning() }

            // 2. Navigate away from "Join" (Back to Create)
            composeTestRule.onNodeWithStringId(R.string.navigation_create).performClick()
            composeTestRule.waitForIdle()

            // Verify the DisposableEffect's onDispose triggered stopScanning
            io.mockk.verify { mockViewModel.stopScanning() }
        }
    }

    @Test
    fun `Navigation - Auto-navigates to Radio when groupName is set`() {
        val initialState = AppUiState(
            hasPermissions = true,
            isServiceBound = true,
            groupName = null
        )

        launchWithMockViewModel(initialState) { _, stateFlow ->
            composeTestRule.onNodeWithStringId(R.string.create_group_title).assertIsDisplayed()

            // Update State (Simulating the user successfully joining/creating a group)
            stateFlow.value = stateFlow.value.copy(groupName = "Hiking", accessCode = "1234")
            composeTestRule.waitForIdle()

            // The LaunchedEffect inside the "create" composable should automatically navigate
            composeTestRule.onNodeWithStringId(R.string.radio_title, "Hiking").assertIsDisplayed()
        }
    }

    @Test
    fun `Navigation - Auto-navigates to Create when groupName is cleared`() {
        val initialState = AppUiState(
            hasPermissions = true,
            isServiceBound = true,
            groupName = "Hiking", // Starting directly in a group
            accessCode = "1234"
        )

        launchWithMockViewModel(initialState) { _, stateFlow ->
            composeTestRule.onNodeWithStringId(R.string.radio_title, "Hiking").assertIsDisplayed()

            // Update State (Simulating the user leaving the group or being disconnected)
            stateFlow.value = stateFlow.value.copy(groupName = null, accessCode = null)
            composeTestRule.waitForIdle()

            // The LaunchedEffect inside the "radio" composable should pop back to "create"
            composeTestRule.onNodeWithStringId(R.string.create_group_title).assertIsDisplayed()
        }
    }

    @Test
    fun `Radio Screen - PTT Button wires to ViewModel`() {
        val initialState = AppUiState(
            hasPermissions = true,
            isServiceBound = true,
            groupName = "Hiking",
            accessCode = "1234",
            peerCount = 1, // Ensures network is ready and PTT is enabled
            isBluetoothEnabled = true
        )

        launchWithMockViewModel(initialState) { mockViewModel, _ ->
            composeTestRule.onNodeWithStringId(R.string.radio_title, "Hiking").assertIsDisplayed()

            // 1. Anchor the touch to text that DOES NOT change during the press.
            // The "1 Peers Online" text is inside the same clickable Box, so this works perfectly
            // and preserves the Compose gesture state!
            val pttNode = composeTestRule.onNodeWithStringId(R.string.radio_ptt_ble_peers, 1, substring = true)

            pttNode.performTouchInput { down(center) }
            composeTestRule.waitForIdle()
            io.mockk.verify { mockViewModel.startTalking() }

            pttNode.performTouchInput { up() }
            composeTestRule.waitForIdle()

            // Note: LaunchedEffect(isPressed) runs on initial composition (false) AND on release (false).
            // So stopTalking() is actually called twice in the lifecycle of this screen.
            io.mockk.verify(atLeast = 1) { mockViewModel.stopTalking() }
        }
    }

    @Test
    @Config(qualifiers = "w1080dp-h2400dp")
    fun `Radio Screen - Leave Group wires to ViewModel`() {
        val initialState = AppUiState(
            hasPermissions = true,
            isServiceBound = true,
            groupName = "Hiking",
            accessCode = "1234",
            peerCount = 1,
            isBluetoothEnabled = true
        )

        launchWithMockViewModel(initialState) { mockViewModel, _ ->
            // Wait for the UI to settle
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithStringId(R.string.radio_title, "Hiking").assertIsDisplayed()

            // 1. The @Config annotation guarantees a large screen so the button center isn't clipped.
            // 2. We use performTouchInput { click() } to simulate a raw hardware tap,
            //    which safely bypasses any NavHost transition interceptors.
            composeTestRule.onNodeWithStringId(R.string.radio_leave_group)
                .assertIsDisplayed()
                .performTouchInput { click() }

            composeTestRule.waitForIdle()

            io.mockk.verify(exactly = 1) { mockViewModel.leaveGroup() }
        }
    }
}
