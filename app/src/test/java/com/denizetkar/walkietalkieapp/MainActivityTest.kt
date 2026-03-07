package com.denizetkar.walkietalkieapp

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.logic.VoiceManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
            composeTestRule.onNodeWithText("Permissions Needed").assertIsDisplayed()
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
                composeTestRule.onAllNodesWithText("Create Group").fetchSemanticsNodes().isNotEmpty()
            }

            composeTestRule.onNodeWithText("Create Group").assertIsDisplayed()

            composeTestRule.onNodeWithText("Join").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Nearby Groups").assertIsDisplayed()

            composeTestRule.onNodeWithText("Create").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Create Group").assertIsDisplayed()
        }
    }
}