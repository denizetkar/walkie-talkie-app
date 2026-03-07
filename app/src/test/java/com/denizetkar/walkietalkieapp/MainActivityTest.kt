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
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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

    // 1. Use an Empty Rule so the Activity doesn't launch until we are ready
    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Before
    fun setup() {
        // Prevent JNI crashes in Robolectric by mocking the UniFFI bridge
        mockkStatic("uniffi.walkie_talkie_engine.Walkie_talkie_engineKt")
        every { uniffi.walkie_talkie_engine.initLogger() } just Runs

        // 2. Create a real WalkieTalkieService
        val app = ApplicationProvider.getApplicationContext<Application>()
        val serviceIntent = Intent(app, WalkieTalkieService::class.java)

        // Build the service using Robolectric so it has a valid Context and Lifecycle
        val service = Robolectric.buildService(WalkieTalkieService::class.java).create().get()
        val binder = service.onBind(serviceIntent)

        // 3. Tell Robolectric to return this binder when bindService is called
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, WalkieTalkieService::class.java),
            binder
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `Permissions Denied - Shows Permission Required Screen`() {
        // Now we explicitly launch the activity AFTER setup is complete
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
            // The Activity automatically detects the pre-granted permissions and binds the service.
            // We just wait for the UI to arrive at the Create screen.
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                composeTestRule.onAllNodesWithText("Create Group").fetchSemanticsNodes().isNotEmpty()
            }

            // We should be on the Create screen
            composeTestRule.onNodeWithText("Create Group").assertIsDisplayed()

            // Test Bottom Navigation -> Click Join
            composeTestRule.onNodeWithText("Join").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Nearby Groups").assertIsDisplayed()

            // Test Bottom Navigation -> Click Create
            composeTestRule.onNodeWithText("Create").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Create Group").assertIsDisplayed()
        }
    }
}