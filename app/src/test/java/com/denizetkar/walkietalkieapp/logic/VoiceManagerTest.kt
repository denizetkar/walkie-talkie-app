package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.domain.Action
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.annotation.Config
import uniffi.walkie_talkie_engine.AudioEngine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val actions = mutableListOf<Action>()
    private val micGateFlow = MutableStateFlow(false)
    private val configFlow = MutableStateFlow<Triple<Int, Int, UInt>?>(null)

    private val mockEngine = mockk<AudioEngine>(relaxed = true)

    private lateinit var voiceManager: VoiceManager
    private lateinit var audioManager: AudioManager

    @Before
    fun setup() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // No static mocks here. Trusting Robolectric for AudioAttributes/Request.

        voiceManager = VoiceManager(
            context = context,
            scope = testScope.backgroundScope,
            dispatch = { actions.add(it) },
            engineFactory = { _, _, _, _ -> mockEngine }
        )

        voiceManager.bind(micGateFlow, configFlow)
    }

    @After
    fun tearDown() {
        voiceManager.close()
    }

    @Test
    fun `Audio Focus - Mutes Mic when focus lost (GSM Call)`() = testScope.runTest {
        // 1. Start Engine and Enable Mic
        configFlow.value = Triple(0, 0, 1u)
        micGateFlow.value = true
        advanceUntilIdle()

        verify { mockEngine.startSession() }
        verify { mockEngine.setMicEnabled(true) }
        actions.clear()

        // 2. Simulate Focus Loss (Direct Call to Internal Listener)
        // This validates: "If the listener fires, do we dispatch the action?"
        voiceManager.focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        advanceUntilIdle()

        // 3. Assert
        assertTrue("Should dispatch SetMic(false)", actions.contains(Action.SetMic(false)))
    }
}