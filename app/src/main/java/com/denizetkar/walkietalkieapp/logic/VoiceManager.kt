package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.denizetkar.walkietalkieapp.AudioDeviceUi
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.domain.Action
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.walkie_talkie_engine.AudioConfig
import uniffi.walkie_talkie_engine.AudioEngine
import uniffi.walkie_talkie_engine.AudioErrorCallback
import uniffi.walkie_talkie_engine.PacketTransport
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
    // STANDARD DIRECT LANE: Fast path for high-frequency events (Mic Audio)
    private val dispatch: (Action) -> Unit
) {
    // --- Internal State ---
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // "Hot Path" reference to the engine. Used by audio processing threads.
    private val activeEngine = AtomicReference<AudioEngine?>(null)

    // Tracks current selection for ghost-device validation
    private val currentInputId = AtomicInteger(0)
    private val currentOutputId = AtomicInteger(0)

    // Serializes hardware access to prevent starting a new stream while old one is closing
    private val hardwareMutex = Mutex()

    // --- Audio Focus Configuration (Immutable) ---
    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
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
            // The retry loop in manageEngineLifecycle will handle restart automatically
        }
    }

    // Callback from Android: Hardware changes
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.d("VoiceManager", "System: Devices Added")
            updateDeviceLists()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d("VoiceManager", "System: Devices Removed")
            updateDeviceLists()
        }
    }

    init {
        // 1. Register for updates (Requires Main Looper Handler)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))

        // 2. Initial scan of hardware to populate UI lists
        updateDeviceLists()
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
     * @param configFlow Flow<Triple<Int, Int, UInt>>: (InputId, OutputId, NodeId) for device selection and
     * atomic restarts on ID rotation.
     */
    fun bind(micGate: Flow<Boolean>, configFlow: Flow<Triple<Int, Int, UInt>>) {
        // 1. Lifecycle & Configuration
        // Whenever the device selection changes OR the Node ID rotates, restart engine.
        scope.launch(Dispatchers.IO) {
            configFlow.collectLatest { (inputId, outputId, nodeId) ->
                // CRITICAL: Acquire lock. This waits for the previous 'manageEngineLifecycle'
                // to finish its 'finally' block (cleanup) before starting the new one.
                hardwareMutex.withLock {
                    currentInputId.set(inputId)
                    currentOutputId.set(outputId)
                    manageEngineLifecycle(inputId, outputId, nodeId)
                }
            }
        }

        // 2. Mic Gate (Hot Mic Control)
        // Whenever the PTT button is pressed/released, we toggle the software gate.
        scope.launch(Dispatchers.IO) {
            micGate.collectLatest { isOpen ->
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
    private suspend fun manageEngineLifecycle(inputId: Int, outputId: Int, nodeId: UInt) {
        Log.i("VoiceManager", "Lifecycle: Requesting Engine Start (In=$inputId, Out=$outputId, Node=$nodeId)")

        // Retry Loop (Restored Feature from Legacy Code)
        while (currentCoroutineContext().isActive) {
            var localEngine: AudioEngine? = null
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!requestAudioFocus()) {
                    Log.w("VoiceManager", "Failed to obtain Audio Focus. Waiting...")
                    delay(Config.AUDIO_SESSION_START_DELAY)
                    continue
                }

                val config = AudioConfig(
                    sampleRate = Config.AUDIO_SAMPLE_RATE,
                    frameSizeMs = Config.AUDIO_FRAME_SIZE_MS,
                    jitterBufferMs = Config.AUDIO_JITTER_BUFFER_MS,
                    inputDeviceId = inputId,
                    outputDeviceId = outputId
                )
                val ownNodeId = nodeId.toInt().toUInt() // UniFFI mapping check
                localEngine = AudioEngine(config, packetTransport, errorCallback, ownNodeId)

                // This call interacts with Oboe/AAudio.
                // CRITICAL: This throws SecurityException if the Service is not yet promoted
                // to Foreground (Android 14+ Microphone privacy restrictions).
                localEngine.startSession()

                // Publish Success
                // Only set the atomic reference AFTER successful start to avoid race conditions
                activeEngine.set(localEngine)
                Log.i("VoiceManager", "Audio Engine Started Successfully")

                // 3. Keep alive until cancelled
                awaitCancellation()

            } catch (e: Exception) {
                // If the coroutine was cancelled (e.g. user selected different mic),
                // we must rethrow to exit the loop and allow collectLatest to start the new block.
                if (e is CancellationException) {
                    cleanupEngine(localEngine)
                    throw e
                }

                // Abnormal Error (SecurityException, Hardware Busy, etc.)
                Log.w("VoiceManager", "Engine Start Failed/Crashed: ${e.message}. Retrying in ${Config.AUDIO_SESSION_START_DELAY}ms...", e)
                // Clean up partial state (if any) before retrying
                cleanupEngine(localEngine)
                delay(Config.AUDIO_SESSION_START_DELAY)
            } finally {
                // Ensure we always clean up when leaving this scope (just in case)
                cleanupEngine(localEngine)
                abandonAudioFocus()
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
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
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map {
            AudioDeviceUi(it.id, it.productName.toString())
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
            AudioDeviceUi(it.id, it.productName.toString())
        }

        // VALIDATION: Ghost Device Check
        // If the currently selected device is NOT in the new list, revert to default.
        val curIn = currentInputId.get()
        if (curIn != 0 && inputs.none { it.id == curIn }) {
            Log.w("VoiceManager", "Device removed (ID $curIn). Reverting Input to Default.")
            dispatch(Action.SetAudioInput(0))
        }

        val curOut = currentOutputId.get()
        if (curOut != 0 && outputs.none { it.id == curOut }) {
            Log.w("VoiceManager", "Device removed (ID $curOut). Reverting Output to Default.")
            dispatch(Action.SetAudioOutput(0))
        }

        // Update UI
        dispatch(Action.AudioDevicesUpdated(inputs, outputs))
    }
}