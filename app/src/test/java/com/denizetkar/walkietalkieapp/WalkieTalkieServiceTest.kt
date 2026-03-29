package com.denizetkar.walkietalkieapp

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.domain.Action
import com.denizetkar.walkietalkieapp.logic.VoiceManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import uniffi.walkie_talkie_engine.initLogger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MainApplication::class)
class WalkieTalkieServiceTest {

    private lateinit var serviceController: org.robolectric.android.controller.ServiceController<WalkieTalkieService>
    private lateinit var service: WalkieTalkieService
    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic("uniffi.walkie_talkie_engine.Walkie_talkie_engineKt")
        every { initLogger() } just Runs

        // Prevent native crash leakage
        io.mockk.mockkConstructor(VoiceManager::class)
        every { anyConstructed<VoiceManager>().bind(any(), any()) } just Runs
        every { anyConstructed<VoiceManager>().renderAudio(any()) } just Runs
        every { anyConstructed<VoiceManager>().close() } just Runs

        context = ApplicationProvider.getApplicationContext()
        serviceController = Robolectric.buildService(WalkieTalkieService::class.java)
        service = serviceController.create().get()
    }

    @After
    fun tearDown() {
        serviceController.destroy()
        io.mockk.unmockkAll() // Ensure mockkConstructor is wiped clean
    }

    @Test
    fun `Lifecycle - Acquires WakeLock on create and releases on destroy`() {
        // 1. Verify WakeLock was acquired during onCreate()
        // FIX: getLatestWakeLock() is a STATIC method on ShadowPowerManager
        val wakeLock = ShadowPowerManager.getLatestWakeLock()

        assertNotNull("WakeLock should be created", wakeLock)
        assertTrue("WakeLock should be held after onCreate", wakeLock.isHeld)

        // 2. Destroy Service
        serviceController.destroy()

        // 3. Verify it was properly released
        assertFalse("WakeLock should be released after onDestroy", wakeLock.isHeld)
    }

    @Test
    fun `Foreground Service - Promotes when session starts and demotes when session ends`() = runBlocking {
        serviceController.startCommand(0, 0)
        val shadowService = shadowOf(service)

        // 1. Initial State: No session (Core assumes Idle). Should not be foreground.
        assertNull("Should not be foreground initially", shadowService.lastForegroundNotification)

        // 2. Get Binder and Dispatch an Action to join a group
        val binder = service.onBind(Intent()) as WalkieTalkieService.LocalBinder
        binder.dispatchAction(Action.CreateGroup(100u, "TestGroup", "1234"))

        // Wait for the Core to process the intent
        val controller = service.meshControllerState.filterNotNull().first()
        withTimeout(3000) { controller.state.first { it.session != null } }

        // Wait for the Service's effect collector (running on IO dispatcher) to process the state
        withTimeout(3000) {
            while (shadowService.lastForegroundNotification == null) delay(50)
        }

        // Assert: Service promoted itself to Foreground
        val notification = shadowService.lastForegroundNotification
        assertNotNull("Should promote to foreground when session starts", notification)
        assertEquals("Walkie Talkie Active", notification.extras.getString("android.title"))

        // 3. Dispatch Action to leave the group
        binder.dispatchAction(Action.LeaveGroup)

        withTimeout(3000) { controller.state.first { it.session == null } }

        // Wait for the collector to tear down the notification
        withTimeout(3000) {
            while (!shadowService.isForegroundStopped) delay(50)
        }

        // Assert: Service demoted itself to Background
        assertTrue("Should stop foreground when session ends", shadowService.isForegroundStopped)
    }
}