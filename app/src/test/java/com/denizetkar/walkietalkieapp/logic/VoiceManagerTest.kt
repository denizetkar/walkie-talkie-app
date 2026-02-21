package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.domain.Action
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import uniffi.walkie_talkie_engine.AudioEngine

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val actions = mutableListOf<Action>()
    private val mockContext = mockk<Context>(relaxed = true)

    private lateinit var spyAudioManager: AudioManager
    private lateinit var capturedDeviceCallback: AudioDeviceCallback

    @Before
    fun setup() {
        ShadowLog.stream = System.out

        // Setup AudioManager Spy to handle hardware lists naturally via Robolectric
        val realContext = ApplicationProvider.getApplicationContext<Context>()
        val realAudioManager = realContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        spyAudioManager = spyk(realAudioManager)

        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns spyAudioManager
        every { spyAudioManager.getDevices(any()) } returns emptyArray()

        val callbackSlot = slot<AudioDeviceCallback>()
        every { spyAudioManager.registerAudioDeviceCallback(capture(callbackSlot), any()) } just runs
        every { spyAudioManager.unregisterAudioDeviceCallback(any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createVoiceManager(
        micGateFlow: MutableStateFlow<Boolean>,
        configFlow: MutableStateFlow<Triple<Int, Int, UInt>?>,
        enginesList: MutableList<AudioEngine>? = null
    ): VoiceManager {
        // Use a Spy with recordPrivateCalls to bypass the Android Stub exception safely.
        val manager = spyk(VoiceManager(
            context = mockContext,
            scope = testScope.backgroundScope,
            ioDispatcher = testDispatcher,
            dispatch = { actions.add(it) },
            engineFactory = { _, _, _, _ ->
                val engine = mockk<AudioEngine>(relaxed = true)
                enginesList?.add(engine)
                engine
            }
        ), recordPrivateCalls = true)

        // COMPLETELY BYPASS Android Stub Builder exceptions for AudioFocusRequest.
        // Since we mock these private methods, `focusRequest` is never evaluated!
        every { manager["requestAudioFocus"]() } returns true
        every { manager["abandonAudioFocus"]() } returns Unit

        val callbackSlot = slot<AudioDeviceCallback>()
        verify { spyAudioManager.registerAudioDeviceCallback(capture(callbackSlot), any()) }
        capturedDeviceCallback = callbackSlot.captured

        manager.bind(micGateFlow, configFlow)
        return manager
    }

    @Test
    fun `Audio Focus - Mutes Mic when focus lost`() = testScope.runTest {
        val micGateFlow = MutableStateFlow(false)
        val configFlow = MutableStateFlow<Triple<Int, Int, UInt>?>(null)
        val manager = createVoiceManager(micGateFlow, configFlow)

        // Directly trigger the internal focus listener
        manager.focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        advanceUntilIdle()

        assertTrue("Should dispatch SetMic(false)", actions.contains(Action.SetMic(false)))
        manager.close()
    }

    @Test
    fun `Hardware Update - Adding a device updates UI lists`() = testScope.runTest {
        val micGateFlow = MutableStateFlow(false)
        val configFlow = MutableStateFlow<Triple<Int, Int, UInt>?>(null)
        val manager = createVoiceManager(micGateFlow, configFlow)
        actions.clear()

        val fakeHeadset = mockk<AudioDeviceInfo> {
            every { id } returns 42
            every { type } returns AudioDeviceInfo.TYPE_WIRED_HEADSET
            every { address } returns ""
            every { productName } returns "Test Headset"
        }

        every { spyAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns arrayOf(fakeHeadset)

        capturedDeviceCallback.onAudioDevicesAdded(arrayOf(fakeHeadset))
        advanceUntilIdle()

        val updateAction = actions.filterIsInstance<Action.AudioDevicesUpdated>().lastOrNull()
        assertTrue("Should dispatch AudioDevicesUpdated", updateAction != null)
        assertEquals("Should contain 1 input device", 1, updateAction!!.inputs.size)

        val firstInput = updateAction.inputs.first()
        assertEquals("Should map device ID correctly", 42, firstInput.id)
        assertEquals("Should map friendly name correctly", "Wired Headset", firstInput.displayName)

        manager.close()
    }

    @Test
    fun `Ghost Validation - Unplugging active mic reverts to default`() = testScope.runTest {
        val micGateFlow = MutableStateFlow(false)
        val configFlow = MutableStateFlow<Triple<Int, Int, UInt>?>(Triple(99, 0, 1u))
        val manager = createVoiceManager(micGateFlow, configFlow)

        advanceUntilIdle()
        actions.clear()

        every { spyAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns emptyArray()

        val fakeRemovedDevice = mockk<AudioDeviceInfo> { every { id } returns 99 }
        capturedDeviceCallback.onAudioDevicesRemoved(arrayOf(fakeRemovedDevice))
        advanceUntilIdle()

        val fallbackAction = actions.filterIsInstance<Action.SetAudioInput>().lastOrNull()
        assertTrue("Should dispatch SetAudioInput when ghost device detected", fallbackAction != null)
        assertEquals("Should revert to Default Mic (ID 0)", 0, fallbackAction!!.id)

        manager.close()
    }

    @Test
    fun `Lifecycle - Changing Hardware Config safely restarts Engine`() = testScope.runTest {
        val engines = mutableListOf<AudioEngine>()
        val localMicGateFlow = MutableStateFlow(false)
        val localConfigFlow = MutableStateFlow<Triple<Int, Int, UInt>?>(null)

        val testVoiceManager = createVoiceManager(localMicGateFlow, localConfigFlow, engines)

        // 1. Initial State: Start with Default Mic
        localConfigFlow.value = Triple(0, 0, 1u)
        advanceUntilIdle()

        assertEquals("First engine created", 1, engines.size)
        val engine1 = engines.first()
        verify(exactly = 1) { engine1.startSession() }

        // 2. Change Config: User selects Bluetooth Mic (ID 5)
        localConfigFlow.value = Triple(5, 0, 1u)
        advanceUntilIdle()

        // 3. Assert
        assertEquals("Second engine created", 2, engines.size)
        val engine2 = engines.last()

        verify(exactly = 1) { engine1.stopSession() }
        verify(exactly = 1) { engine2.startSession() }

        testVoiceManager.close()
    }
}