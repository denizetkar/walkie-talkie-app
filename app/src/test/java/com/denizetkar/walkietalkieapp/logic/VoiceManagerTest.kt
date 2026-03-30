package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.domain.Action
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import uniffi.walkie_talkie_engine.AudioErrorCallback

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val actions = mutableListOf<Action>()

    private lateinit var mockContext: Context
    private lateinit var spyAudioManager: AudioManager
    private lateinit var capturedDeviceCallback: AudioDeviceCallback

    @Before
    fun setup() {
        ShadowLog.stream = System.out

        // Setup AudioManager Spy to handle hardware lists naturally via Robolectric
        val realContext = ApplicationProvider.getApplicationContext<Context>()
        val realAudioManager = realContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        spyAudioManager = spyk(realAudioManager)

        mockContext = spyk(realContext)  // Use a real Spy so resources resolve properly!
        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns spyAudioManager
        every { spyAudioManager.getDevices(any()) } returns emptyArray()

        val callbackSlot = slot<AudioDeviceCallback>()
        every { spyAudioManager.registerAudioDeviceCallback(capture(callbackSlot), any()) } just runs
        every { spyAudioManager.unregisterAudioDeviceCallback(any()) } just runs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            every { spyAudioManager.setCommunicationDevice(any()) } returns true
            every { spyAudioManager.clearCommunicationDevice() } just runs
        } else @Suppress("DEPRECATION") {
            every { spyAudioManager.startBluetoothSco() } just runs
            every { spyAudioManager.stopBluetoothSco() } just runs
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createVoiceManager(
        micGateFlow: MutableStateFlow<Boolean>,
        configFlow: MutableStateFlow<Pair<Int, UInt>?>,
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
        val configFlow = MutableStateFlow<Pair<Int, UInt>?>(null)
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
        val configFlow = MutableStateFlow<Pair<Int, UInt>?>(null)
        val manager = createVoiceManager(micGateFlow, configFlow)
        actions.clear()

        val fakeHeadset = mockk<AudioDeviceInfo> {
            every { id } returns 42
            every { type } returns AudioDeviceInfo.TYPE_WIRED_HEADSET
            every { address } returns ""
            every { productName } returns "Test Headset"
        }

        every { spyAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(fakeHeadset)

        capturedDeviceCallback.onAudioDevicesAdded(arrayOf(fakeHeadset))
        advanceUntilIdle()

        val updateAction = actions.filterIsInstance<Action.AudioDevicesUpdated>().lastOrNull()
        assertTrue("Should dispatch AudioDevicesUpdated", updateAction != null)
        assertEquals("Should contain 1 input device", 1, updateAction!!.devices.size)

        val firstInput = updateAction.devices.first()
        assertEquals("Should map device ID correctly", 42, firstInput.id)
        assertEquals("Should extract type natively", AudioDeviceInfo.TYPE_WIRED_HEADSET, firstInput.type)
        assertEquals("Should map hardware name correctly", "Test Headset", firstInput.productName)

        manager.close()
    }

    @Test
    fun `Hardware Update - Device Types Map to Friendly Names`() = testScope.runTest {
        val micGateFlow = MutableStateFlow(false)
        val configFlow = MutableStateFlow<Pair<Int, UInt>?>(null)
        val manager = createVoiceManager(micGateFlow, configFlow)
        actions.clear()

        // Create mock devices hitting various logic branches in `toFriendlyName`
        val mockEarPiece = mockk<AudioDeviceInfo> {
            every { id } returns 1
            every { type } returns AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            every { address } returns ""
            every { productName } returns ""
        }
        val mockSco = mockk<AudioDeviceInfo> {
            every { id } returns 2
            every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            every { address } returns "AA:BB"
            every { productName } returns "BT Headset"
        }
        val mockUsb = mockk<AudioDeviceInfo> {
            every { id } returns 3
            every { type } returns AudioDeviceInfo.TYPE_USB_DEVICE
            every { address } returns ""
            every { productName } returns "USB DAC"
        }
        val mockSpeaker = mockk<AudioDeviceInfo> {
            every { id } returns 4
            every { type } returns AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            every { address } returns ""
            every { productName } returns ""
        }

        every { spyAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns arrayOf()
        every { spyAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(mockEarPiece, mockSco, mockUsb, mockSpeaker)

        capturedDeviceCallback.onAudioDevicesAdded(null)
        advanceUntilIdle()

        val updateAction = actions.filterIsInstance<Action.AudioDevicesUpdated>().lastOrNull()
        org.junit.Assert.assertNotNull(updateAction)

        val devices = updateAction!!.devices
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, devices.find { it.id == 1 }?.type)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, devices.find { it.id == 2 }?.type)
        assertEquals(AudioDeviceInfo.TYPE_USB_DEVICE, devices.find { it.id == 3 }?.type)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, devices.find { it.id == 4 }?.type)

        manager.close()
    }

    @Test
    fun `Ghost Validation - Unplugging active mic reverts to default`() = testScope.runTest {
        val micGateFlow = MutableStateFlow(false)
        val configFlow = MutableStateFlow<Pair<Int, UInt>?>(Pair(99, 1u))
        val manager = createVoiceManager(micGateFlow, configFlow)

        advanceUntilIdle()
        actions.clear()

        every { spyAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns emptyArray()

        val fakeRemovedDevice = mockk<AudioDeviceInfo> { every { id } returns 99 }
        capturedDeviceCallback.onAudioDevicesRemoved(arrayOf(fakeRemovedDevice))
        advanceUntilIdle()

        val fallbackAction = actions.filterIsInstance<Action.SetAudioDevice>().lastOrNull()
        assertTrue("Should dispatch SetAudioInput when ghost device detected", fallbackAction != null)
        assertEquals("Should revert to Default Mic (ID 0)", 0, fallbackAction!!.id)

        manager.close()
    }

    @Test
    fun `Lifecycle - Changing Hardware Config safely restarts Engine`() = testScope.runTest {
        val engines = mutableListOf<AudioEngine>()
        val localMicGateFlow = MutableStateFlow(false)
        val localConfigFlow = MutableStateFlow<Pair<Int, UInt>?>(null)

        val testVoiceManager = createVoiceManager(localMicGateFlow, localConfigFlow, engines)

        // 1. Initial State: Start with Default Device
        localConfigFlow.value = Pair(0, 1u)
        advanceUntilIdle()

        assertEquals("First engine created", 1, engines.size)
        val engine1 = engines.first()
        verify(exactly = 1) { engine1.startSession() }

        // 2. Change Config: User selects Bluetooth Device (ID 5)
        localConfigFlow.value = Pair(5, 1u)
        advanceUntilIdle()

        // 3. Assert
        assertEquals("Second engine created", 2, engines.size)
        val engine2 = engines.last()

        verify(exactly = 1) { engine1.stopSession() }
        verify(exactly = 1) { engine2.startSession() }

        testVoiceManager.close()
    }

    @Test
    fun `Lifecycle - Stop Session Exception is caught and ignored`() = testScope.runTest {
        val micGateFlow = MutableStateFlow(false)
        val configFlow = MutableStateFlow<Pair<Int, UInt>?>(Pair(0, 1u))

        val engine = mockk<AudioEngine>(relaxed = true)
        // Simulate a native crash when trying to close the Oboe stream
        every { engine.stopSession() } throws RuntimeException("Oboe Crash")

        val manager = spyk(VoiceManager(
            context = mockContext,
            scope = testScope.backgroundScope,
            ioDispatcher = testDispatcher,
            dispatch = { actions.add(it) },
            engineFactory = { _, _, _, _ -> engine }
        ), recordPrivateCalls = true)

        every { manager["requestAudioFocus"]() } returns true
        every { manager["abandonAudioFocus"]() } returns Unit

        manager.bind(micGateFlow, configFlow)
        advanceUntilIdle()

        // Set config to null to trigger stopEngine
        configFlow.value = null
        advanceUntilIdle()

        // The test passes if no unhandled exception crashed the coroutine
        verify(exactly = 1) { engine.stopSession() }
        manager.close()
    }

    @Test
    fun `Lifecycle - Audio Focus Denied Retries Until Granted`() = testScope.runTest {
        val engines = mutableListOf<AudioEngine>()
        val micGateFlow = MutableStateFlow(false)
        val configFlow = MutableStateFlow<Pair<Int, UInt>?>(Pair(0, 1u))

        val manager = spyk(VoiceManager(
            context = mockContext,
            scope = testScope.backgroundScope,
            ioDispatcher = testDispatcher,
            dispatch = { actions.add(it) },
            engineFactory = { _, _, _, _ ->
                val engine = mockk<AudioEngine>(relaxed = true)
                engines.add(engine)
                engine
            }
        ), recordPrivateCalls = true)

        // Mock focus to be DENIED initially
        var focusGranted = false
        every { manager["requestAudioFocus"]() } answers { focusGranted }
        every { manager["abandonAudioFocus"]() } returns Unit

        manager.bind(micGateFlow, configFlow)
        advanceUntilIdle()

        // Engine should not be started yet because focus was denied
        assertEquals(0, engines.size)

        // Now grant focus (e.g. user hung up a GSM call)
        focusGranted = true
        // Advance time to surpass Config.AUDIO_SESSION_START_DELAY retry loop
        advanceTimeBy(Config.AUDIO_SESSION_START_DELAY + 100L)
        advanceUntilIdle()

        // Now it should have successfully bypassed the wait and started
        assertEquals(1, engines.size)

        manager.close()
    }

    @Test
    fun `Error Recovery - Rust Engine crash triggers automatic restart`() = testScope.runTest {
        val engines = mutableListOf<AudioEngine>()
        val localMicGateFlow = MutableStateFlow(false)
        val localConfigFlow = MutableStateFlow<Pair<Int, UInt>?>(Pair(0, 1u))

        var capturedErrorCallback: AudioErrorCallback? = null

        // 1. MOCK THE SYSTEM DEPENDENCY, NOT THE TARGET OBJECT
        // This ensures Robolectric/MockK handles the focus safely without breaking coroutines.
        every { spyAudioManager.requestAudioFocus(any<android.media.AudioFocusRequest>()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        every { spyAudioManager.abandonAudioFocusRequest(any()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        // 2. USE A REAL, UN-SPIED VOICEMANAGER
        val testVoiceManager = VoiceManager(
            context = mockContext,
            scope = testScope.backgroundScope,
            ioDispatcher = testDispatcher,
            dispatch = { actions.add(it) },
            engineFactory = { _, _, cb, _ ->
                capturedErrorCallback = cb
                val engine = mockk<AudioEngine>(relaxed = true)
                engines.add(engine)
                engine
            }
        ) // Notice: No spyk() here!

        testVoiceManager.bind(localMicGateFlow, localConfigFlow)
        advanceUntilIdle()

        assertEquals("Should start first engine", 1, engines.size)
        verify(exactly = 1) { engines[0].startSession() }

        // Trigger the crash signal from Rust
        capturedErrorCallback?.onEngineError(-899)

        // Advance time to surpass the AUDIO_SESSION_START_DELAY in the try/catch block
        advanceTimeBy(Config.AUDIO_SESSION_START_DELAY + 100L)
        runCurrent()

        assertEquals("Should create a second engine after crash", 2, engines.size)
        verify(exactly = 1) { engines[0].stopSession() }
        verify(exactly = 1) { engines[1].startSession() }

        testVoiceManager.close()
    }

    @Test
    fun `Engine Factory - PacketTransport dispatches AudioDataCaptured`() = testScope.runTest {
        val micGateFlow = MutableStateFlow(false)
        val configFlow = MutableStateFlow<Pair<Int, UInt>?>(Pair(0, 1u))

        var capturedTransport: uniffi.walkie_talkie_engine.PacketTransport? = null

        val manager = spyk(VoiceManager(
            context = mockContext,
            scope = testScope.backgroundScope,
            ioDispatcher = testDispatcher,
            dispatch = { actions.add(it) },
            engineFactory = { _, transport, _, _ ->
                capturedTransport = transport
                mockk<AudioEngine>(relaxed = true)
            }
        ), recordPrivateCalls = true)

        every { manager["requestAudioFocus"]() } returns true
        every { manager["abandonAudioFocus"]() } returns Unit

        manager.bind(micGateFlow, configFlow)
        advanceUntilIdle()

        // Manually invoke the callback interface generated by UniFFI from Rust
        val dummyData = byteArrayOf(0x01, 0x02, 0x03)
        capturedTransport?.sendPacket(dummyData)
        advanceUntilIdle()

        val capturedAction = actions.filterIsInstance<Action.AudioDataCaptured>().lastOrNull()
        org.junit.Assert.assertNotNull("Should dispatch AudioDataCaptured from Rust transport", capturedAction)
        org.junit.Assert.assertArrayEquals(dummyData, capturedAction!!.data)

        manager.close()
    }

    @Test
    @org.robolectric.annotation.Config(sdk = [30])
    fun `Legacy SDK - applyAudioRouting uses Bluetooth SCO`() = testScope.runTest {
        val micGateFlow = MutableStateFlow(false)
        val configFlow = MutableStateFlow<Pair<Int, UInt>?>(Pair(2, 1u)) // ID 2 = BT SCO

        val mockSco = mockk<AudioDeviceInfo> {
            every { id } returns 2
            every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            // Provide answers for the UI mapping fields
            every { address } returns "AA:BB:CC"
            every { productName } returns "Legacy Headset"
        }
        every { spyAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(mockSco)

        val manager = createVoiceManager(micGateFlow, configFlow)
        advanceUntilIdle()

        // Verifies older Android hardware routing API
        @Suppress("DEPRECATION")
        verify { spyAudioManager.startBluetoothSco() }
        verify { spyAudioManager.isBluetoothScoOn = true }

        configFlow.value = null // trigger engine stop
        advanceUntilIdle()

        @Suppress("DEPRECATION")
        verify { spyAudioManager.stopBluetoothSco() }
        manager.close()
    }
}