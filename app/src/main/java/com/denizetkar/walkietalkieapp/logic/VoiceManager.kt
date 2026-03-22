package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.denizetkar.walkietalkieapp.domain.AudioDeviceUi
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.domain.Action
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.walkie_talkie_engine.AudioConfig
import uniffi.walkie_talkie_engine.AudioEngine
import uniffi.walkie_talkie_engine.AudioErrorCallback
import uniffi.walkie_talkie_engine.PacketTransport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Reactive Wrapper around the Rust Audio Engine.
 *
 * RESPONSIBILITIES:
 * 1. Managing the Engine Lifecycle (Start/Stop).
 * 2. Mapping "Actions" (Device selection) to Config.
 * 3. Feeding incoming audio into the engine.
 * 4. Streaming outgoing audio (encoded) to the Core.
 * 5. Handling Retry logic for hardware initialization failures.
 * 6. Monitoring Hardware Changes (Headphones plug/unplug).
 */
class VoiceManager(
    context: Context,
    private val scope: CoroutineScope,
    // INJECTED DISPATCHER: Allows tests to swap IO for TestDispatcher
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // STANDARD DIRECT LANE: Fast path for high-frequency events (Mic Audio)
    private val dispatch: (Action) -> Unit,
    // TEST HOOK: Allows injecting a mock AudioEngine
    private val engineFactory: (AudioConfig, PacketTransport, AudioErrorCallback, UInt) -> AudioEngine = ::AudioEngine
) {
    // --- Internal State ---
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // "Hot Path" reference to the engine. Used by audio processing threads.
    private val activeEngine = AtomicReference<AudioEngine?>(null)

    // Tracks current selection for ghost-device validation
    private val currentDeviceId = AtomicInteger(0)

    // Serializes hardware access to prevent starting a new stream while old one is closing
    private val hardwareMutex = Mutex()

    // ACTOR: Trigger channel to serialize device updates on background thread.
    // Conflated = We only care about the latest hardware state.
    private val deviceUpdateTrigger = Channel<Unit>(Channel.CONFLATED)

    // Cache the user's PTT intent to fix race condition during engine startup
    private val isMicEnabled = AtomicBoolean(false)

    // A conflated channel ensures we don't queue up multiple crash signals
    private val engineCrashSignal = Channel<Unit>(Channel.CONFLATED)

    // --- Audio Focus Configuration (Immutable) ---
    internal val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i("VoiceManager", "Audio Focus Lost: Muting Mic")
                dispatch(Action.SetMic(false))
            }
        }
    }

    private val focusRequest: AudioFocusRequest? by lazy {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    // --- Bridges (Rust <-> Kotlin) ---

    // Callback from Rust: Encoded packets ready for transmission
    private val packetTransport = object : PacketTransport {
        override fun sendPacket(data: ByteArray) {
            // Emit directly to the Core
            dispatch(Action.AudioDataCaptured(data))
        }
    }

    // Callback from Rust: Critical errors (e.g. Device disconnect)
    private val errorCallback = object : AudioErrorCallback {
        override fun onEngineError(code: Int) {
            Log.e("VoiceManager", "CRITICAL: Rust Engine Error Code $code")
            engineCrashSignal.trySend(Unit)
        }
    }

    // Callback from Android: Hardware changes
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.d("VoiceManager", "System: Devices Added")
            deviceUpdateTrigger.trySend(Unit)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d("VoiceManager", "System: Devices Removed")
            deviceUpdateTrigger.trySend(Unit)
        }
    }

    init {
        // 1. Start the Actor Loop to handle device updates off the main thread
        scope.launch(ioDispatcher) {
            deviceUpdateTrigger.consumeEach { updateDeviceLists() }
        }

        // 2. Register for updates (Requires Main Looper Handler)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))

        // 3. Initial scan of hardware to populate UI lists
        deviceUpdateTrigger.trySend(Unit)
    }

    /**
     * Feeds incoming UDP/BLE Audio packets into the jitter buffer.
     * Thread-safe. Can be called from any thread.
     */
    fun renderAudio(data: ByteArray) {
        activeEngine.get()?.pushIncomingPacket(data)
    }

    /**
     * Binds the Voice Manager to the Control Flows.
     * @param micGate Flow<Boolean>: True = Unmute (PTT Pressed), False = Mute.
     * @param configFlow Flow<Triple<Int, Int, UInt>?>: (InputId, OutputId, NodeId). NULL stops the engine.
     */
    fun bind(micGate: Flow<Boolean>, configFlow: Flow<Pair<Int, UInt>?>) {
        // 1. Lifecycle & Configuration
        // Whenever the device selection changes OR the Node ID rotates, restart engine.
        scope.launch(ioDispatcher) {
            configFlow.collectLatest { config ->
                // CRITICAL: Acquire lock. This waits for the previous 'manageEngineLifecycle'
                // to finish its 'finally' block (cleanup) before starting the new one.
                hardwareMutex.withLock {
                    if (config != null) {
                        val (deviceId, nodeId) = config
                        currentDeviceId.set(deviceId)
                        manageEngineLifecycle(deviceId, nodeId)
                    } else {
                        stopEngine()
                    }
                }
            }
        }

        // 2. Mic Gate (Hot Mic Control)
        // Whenever the PTT button is pressed/released, we toggle the software gate.
        scope.launch(ioDispatcher) {
            micGate.collectLatest { isOpen ->
                isMicEnabled.set(isOpen)
                activeEngine.get()?.setMicEnabled(isOpen)
            }
        }
    }

    /**
     * Proper cleanup to prevent leaks.
     */
    fun close() {
        Log.i("VoiceManager", "Shutting down...")
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopEngine()
    }

    /**
     * The Main Audio Lifecycle Loop.
     * Tries to start the engine, and keeps retrying if it fails.
     * Only exits if the coroutine is cancelled (Config Change or App Exit).
     */
    private suspend fun manageEngineLifecycle(deviceId: Int, ownNodeId: UInt) {
        Log.i("VoiceManager", "Lifecycle: Requesting Engine Start (Route=$deviceId, Node=$ownNodeId)")

        try {
            // 1. Establish the Communication Route ONCE for the entire lifecycle
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            applyAudioRouting(deviceId)

            // 2. The Stream Retry Loop
            while (currentCoroutineContext().isActive) {
                var localEngine: AudioEngine? = null
                try {
                    if (!requestAudioFocus()) {
                        Log.w("VoiceManager", "Failed to obtain Audio Focus. Waiting...")
                        delay(Config.AUDIO_SESSION_START_DELAY)
                        continue
                    }

                    val config = AudioConfig(
                        sampleRate = Config.AUDIO_SAMPLE_RATE,
                        frameSizeMs = Config.AUDIO_FRAME_SIZE_MS,
                        jitterBufferMs = Config.AUDIO_JITTER_BUFFER_MS,
                        inputDeviceId = 0, // OS automatically matches the Communication Device
                        outputDeviceId = deviceId
                    )
                    // Use the factory (Real by default, Mock in tests)
                    localEngine = engineFactory(config, packetTransport, errorCallback, ownNodeId)
                    // This call interacts with Oboe/AAudio.
                    // CRITICAL: This throws SecurityException if the Service is not yet promoted
                    // to Foreground (Android 14+ Microphone privacy restrictions).
                    localEngine.startSession()
                    // Apply cached mic state immediately (Fixes PTT race condition)
                    localEngine.setMicEnabled(isMicEnabled.get())

                    // Publish Success
                    // Only set the atomic reference AFTER successful start to avoid race conditions
                    activeEngine.set(localEngine)
                    Log.i("VoiceManager", "Audio Engine Started Successfully")

                    // 3. Keep alive until cancelled externally OR an internal crash occurs
                    engineCrashSignal.tryReceive() // Clear any stale signals from previous runs
                    engineCrashSignal.receive()    // Suspend here.
                    throw Exception("Rust Engine internal crash")
                } catch (e: Exception) {
                    // If the coroutine was cancelled (e.g. user selected different mic),
                    // we must rethrow to exit the loop and allow collectLatest to start the new block.
                    if (e is CancellationException) throw e
                    // Abnormal Error (SecurityException, Hardware Busy, etc.)
                    Log.w("VoiceManager", "Engine Start Failed/Crashed: ${e.message}. Retrying in ${Config.AUDIO_SESSION_START_DELAY}ms...", e)
                    delay(Config.AUDIO_SESSION_START_DELAY)
                } finally {
                    // Tear down ONLY the stream, NOT the routing
                    cleanupEngine(localEngine)
                    abandonAudioFocus()
                }
            }
        } finally {
            // 4. Tear down the routing ONLY when the coroutine is cancelled (e.g. user selected a new device or left the group)
            withContext(NonCancellable) {
                clearAudioRouting()
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    private fun applyAudioRouting(deviceId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (deviceId != 0) {
                // setCommunicationDevice strictly requires an Output/Sink device
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val targetDevice = devices.find { it.id == deviceId }
                if (targetDevice != null) {
                    audioManager.setCommunicationDevice(targetDevice)
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else @Suppress("DEPRECATION") {
            if (isBluetoothScoDevice(deviceId)) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            } else {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        }
    }

    private fun clearAudioRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else @Suppress("DEPRECATION") {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
    }

    private fun isBluetoothScoDevice(id: Int): Boolean {
        if (id == 0) return false
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.id == id }
        return device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun requestAudioFocus(): Boolean {
        return focusRequest?.let {
            audioManager.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } ?: false
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun cleanupEngine(engine: AudioEngine?) {
        // Remove from active use immediately
        activeEngine.compareAndSet(engine, null)
        try {
            engine?.stopSession()
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error stopping", e)
        }
    }

    private fun stopEngine() {
        Log.i("VoiceManager", "Lifecycle: Stopping Engine")
        val oldEngine = activeEngine.getAndSet(null)
        try {
            oldEngine?.stopSession()
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error stopping", e)
        }
    }

    private fun updateDeviceLists() {
        // Warning: getDevices is blocking. The Actor in init {} ensures this runs on IO thread.
        // We only scan OUTPUTS because setCommunicationDevice requires a sink.
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
            AudioDeviceUi(it.id, it.type, it.address, it.productName?.toString() ?: "")
        }

        // VALIDATION: Ghost Device Check
        // If the currently selected device is NOT in the new list, revert to default.
        val curDev = currentDeviceId.get()
        if (curDev != 0 && outputs.none { it.id == curDev }) {
            Log.w("VoiceManager", "Device removed (ID $curDev). Reverting to Default.")
            dispatch(Action.SetAudioDevice(0))
        }

        // Update UI
        dispatch(Action.AudioDevicesUpdated(outputs))
    }
}
