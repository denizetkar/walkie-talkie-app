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
import com.denizetkar.walkietalkieapp.AudioDeviceUi
import com.denizetkar.walkietalkieapp.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import uniffi.walkie_talkie_engine.AudioConfig
import uniffi.walkie_talkie_engine.AudioEngine
import uniffi.walkie_talkie_engine.AudioErrorCallback
import uniffi.walkie_talkie_engine.PacketTransport
import java.util.concurrent.atomic.AtomicReference

class VoiceManager(
    context: Context,
    private val packetTransport: PacketTransport,
    private val ownNodeId: UInt,
    private val scope: CoroutineScope
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    // --- State ---
    private val _availableInputs = MutableStateFlow<List<AudioDeviceUi>>(emptyList())
    val availableInputs = _availableInputs.asStateFlow()
    private val _availableOutputs = MutableStateFlow<List<AudioDeviceUi>>(emptyList())
    val availableOutputs = _availableOutputs.asStateFlow()

    private val _selectedInputId = MutableStateFlow(0)
    val selectedInputId = _selectedInputId.asStateFlow()
    private val _selectedOutputId = MutableStateFlow(0)
    val selectedOutputId = _selectedOutputId.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    private val _isMicEnabled = MutableStateFlow(false)

    // Hot Path State
    private val activeEngine = AtomicReference<AudioEngine?>(null)

    // Bridge: System API (Callbacks) -> Coroutines (StateFlow)
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.d("VoiceManager", "System: Devices Added")
            scope.launch(Dispatchers.IO) { updateDeviceLists() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d("VoiceManager", "System: Devices Removed")
            scope.launch(Dispatchers.IO) { updateDeviceLists() }
        }
    }
    private val engineErrorCallback = object : AudioErrorCallback {
        override fun onEngineError(code: Int) {
            Log.e("VoiceManager", "CRITICAL: Native Engine Error $code. Restarting...")
            // Trigger reactive restart
            _isSessionActive.value = false
            _isSessionActive.value = true
        }
    }

    init {
        // We use the Main Looper ONLY for the signal trigger (negligible cost).
        // The actual work happens in the Coroutine above.
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))

        // Initial fetch on IO
        scope.launch(Dispatchers.IO) { updateDeviceLists() }

        // REACTIVE ENGINE MANAGEMENT
        scope.launch(Dispatchers.IO) {
            combine(_isSessionActive, _selectedInputId, _selectedOutputId) { active, inId, outId ->
                if (active) AudioConfig(
                    sampleRate = Config.AUDIO_SAMPLE_RATE,
                    frameSizeMs = Config.AUDIO_FRAME_SIZE_MS,
                    jitterBufferMs = Config.AUDIO_JITTER_BUFFER_MS,
                    inputDeviceId = inId,
                    outputDeviceId = outId
                ) else null
            }.collectLatest { config ->
                if (config != null) {
                    manageAudioSession(config)
                } else {
                    activeEngine.set(null)
                }
            }
        }
    }

    /**
     * Call this when entering the "Radio" screen (Joining a group).
     */
    fun start() {
        Log.i("VoiceManager", "Starting Voice System...")
        _isSessionActive.value = true
    }

    /**
     * Call this when leaving the "Radio" screen.
     */
    fun stop() {
        Log.i("VoiceManager", "Stopping Voice System...")
        setMicrophoneEnabled(false)
        _isSessionActive.value = false
    }

    fun setInputDevice(deviceId: Int) {
        if (_selectedInputId.value == deviceId) return
        Log.i("VoiceManager", "Changing Input -> $deviceId")
        _selectedInputId.value = deviceId
    }

    fun setOutputDevice(deviceId: Int) {
        if (_selectedOutputId.value == deviceId) return
        Log.i("VoiceManager", "Changing Output -> $deviceId")
        _selectedOutputId.value = deviceId
    }

    /**
     * The ONLY place where _isMicrophoneEnabled is modified.
     * This ensures the UI state and Rust Hardware state never drift apart.
     */
    fun setMicrophoneEnabled(enabled: Boolean) {
        activeEngine.get()?.setMicEnabled(enabled)
        _isMicEnabled.value = enabled
    }

    fun processIncomingPacket(data: ByteArray) {
        activeEngine.get()?.pushIncomingPacket(data)
    }

    fun destroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        _isSessionActive.value = false
    }

    // --- Private Helpers ---

    private suspend fun manageAudioSession(config: AudioConfig): Nothing = coroutineScope {
        var engine: AudioEngine? = null
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            requestAudioFocus()

            engine = AudioEngine(config, packetTransport, engineErrorCallback, ownNodeId)
            engine.startSession()
            engine.setMicEnabled(_isMicEnabled.value)

            activeEngine.set(engine)

            // Listen for mic changes. Because we are in coroutineScope,
            // this job will be auto-cancelled when manageAudioSession exits.
            launch(Dispatchers.IO) {
                _isMicEnabled.collect { enabled -> engine.setMicEnabled(enabled) }
            }

            awaitCancellation()
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.e("VoiceManager", "Failed to start Rust Engine", e)
            }
            throw e
        } finally {
            Log.i("VoiceManager", "Stopping Audio Engine (Cleanup)")
            activeEngine.set(null)
            try {
                engine?.stopSession()
            } catch (e: Exception) {
                Log.e("VoiceManager", "Error stopping engine", e)
            }
            abandonAudioFocus()
            audioManager.clearCommunicationDeviceCompat()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun updateDeviceLists() {
        // 1. Fetch latest lists from System
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

        // 2. VALIDATION: Check for "Ghost" Input Device
        val currentIn = _selectedInputId.value
        if (currentIn != 0 && inputs.none { it.id == currentIn }) {
            Log.i("VoiceManager", "Selected Mic (ID $currentIn) removed. Reverting to Default.")
            _selectedInputId.value = 0
        }
        // 3. VALIDATION: Check for "Ghost" Output Device
        val currentOut = _selectedOutputId.value
        if (currentOut != 0 && outputs.none { it.id == currentOut }) {
            Log.i("VoiceManager", "Selected Speaker (ID $currentOut) removed. Reverting to Default.")
            _selectedOutputId.value = 0
        }

        _availableInputs.value = inputs.map { it.toFriendlyName(isInput = true) }
        _availableOutputs.value = outputs.map { it.toFriendlyName(isInput = false) }
    }

    private fun AudioManager.clearCommunicationDeviceCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.clearCommunicationDevice()
        } else @Suppress("DEPRECATION") {
            this.isSpeakerphoneOn = false
            this.stopBluetoothSco()
        }
    }

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.i("VoiceManager", "Focus Lost: Stopping Audio")
                        // Safety mute if we lose focus (e.g. incoming phone call)
                        setMicrophoneEnabled(false)
                    }
                }
            }
            .build()

        audioManager.requestAudioFocus(focusRequest!!)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun AudioDeviceInfo.toFriendlyName(isInput: Boolean): AudioDeviceUi {
        val name = address.ifBlank { productName.toString() }

        val friendly = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loudspeaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset ($name)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Headphones ($name)"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device ($name)"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset ($name)"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> if (isInput) "Phone Microphone" else "Phone Mic"
            else -> name
        }

        // Handle cases where name might still be empty or generic
        val finalName = if (friendly.trim().isEmpty()) "Unknown Device" else friendly
        return AudioDeviceUi(id, finalName)
    }
}