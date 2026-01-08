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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.walkie_talkie_engine.AudioConfig
import uniffi.walkie_talkie_engine.AudioEngine
import uniffi.walkie_talkie_engine.AudioErrorCallback
import uniffi.walkie_talkie_engine.PacketTransport

/**
 * The Single Source of Truth for the Voice Subsystem.
 * Thread-Safety: All mutable state modifications are protected by the monitor lock (@Synchronized).
 * High-frequency reads (packet processing) are lock-free via Volatile visibility.
 */
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

    private val _isMicrophoneEnabled = MutableStateFlow(false)
    val isMicrophoneEnabled = _isMicrophoneEnabled.asStateFlow()

    private val baseRustConfig = AudioConfig(
        sampleRate = Config.AUDIO_SAMPLE_RATE,
        frameSizeMs = Config.AUDIO_FRAME_SIZE_MS,
        jitterBufferMs = Config.AUDIO_JITTER_BUFFER_MS,
        inputDeviceId = 0,
        outputDeviceId = 0
    )
    // SAFETY: @Volatile ensures that when the Main Thread replaces the engine,
    // the Network Thread (processIncomingPacket) sees the new reference immediately.
    @Volatile
    private var engine: AudioEngine? = null

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
            scope.launch(Dispatchers.IO) { restartEngineIfActive() }
        }
    }

    init {
        // We use the Main Looper ONLY for the signal trigger (negligible cost).
        // The actual work happens in the Coroutine above.
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))

        // Initial fetch on IO
        scope.launch(Dispatchers.IO) { updateDeviceLists() }
    }

    /**
     * Call this when entering the "Radio" screen (Joining a group).
     */
    @Synchronized
    fun start() {
        Log.i("VoiceManager", "Starting Voice System...")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus()
        applyAudioRouting(_selectedOutputId.value)
        createAndStartEngine()
        setMicrophoneEnabled(false)
    }

    /**
     * Call this when leaving the "Radio" screen.
     */
    @Synchronized
    fun stop() {
        Log.i("VoiceManager", "Stopping Voice System...")
        setMicrophoneEnabled(false)
        try {
            engine?.stopSession()
            engine = null
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error stopping engine", e)
        }
        abandonAudioFocus()
        audioManager.clearCommunicationDeviceCompat()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    @Synchronized
    fun setInputDevice(deviceId: Int) {
        if (_selectedInputId.value == deviceId) return
        Log.i("VoiceManager", "Changing Input -> $deviceId")
        _selectedInputId.value = deviceId
        restartEngineIfActive()
    }

    @Synchronized
    fun setOutputDevice(deviceId: Int) {
        if (_selectedOutputId.value == deviceId) return
        Log.i("VoiceManager", "Changing Output -> $deviceId")
        _selectedOutputId.value = deviceId
        applyAudioRouting(deviceId)
        restartEngineIfActive()
    }

    /**
     * The ONLY place where _isMicrophoneEnabled is modified.
     * This ensures the UI state and Rust Hardware state never drift apart.
     */
    @Synchronized
    fun setMicrophoneEnabled(enabled: Boolean) {
        if (enabled && engine?.isSessionActive() != true) {
            Log.w("VoiceManager", "Ignored mic toggle: Audio Engine is dead.")
            _isMicrophoneEnabled.value = false
            return
        }
        engine?.setMicEnabled(enabled)
        _isMicrophoneEnabled.value = enabled
    }

    /**
     * High-frequency call from the Network Layer.
     * SAFETY: We do NOT lock here to prevent blocking the network thread.
     */
    fun processIncomingPacket(data: ByteArray) {
        engine?.pushIncomingPacket(data)
    }

    @Synchronized
    fun destroy() {
        stop()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    // --- Private Helpers ---

    private fun createAndStartEngine() {
        try {
            engine?.stopSession()

            val currentConfig = baseRustConfig.copy(
                inputDeviceId = _selectedInputId.value,
                outputDeviceId = _selectedOutputId.value
            )
            val newEngine = AudioEngine(currentConfig, packetTransport, engineErrorCallback, ownNodeId)
            newEngine.startSession()
            newEngine.setMicEnabled(_isMicrophoneEnabled.value)
            engine = newEngine
        } catch (e: Exception) {
            Log.e("VoiceManager", "Failed to start Rust Engine", e)
        }
    }

    private fun restartEngineIfActive() {
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            createAndStartEngine()
        }
    }

    private fun updateDeviceLists() {
        // 1. Fetch latest lists from System
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

        var mustRestartEngine = false
        // 2. VALIDATION: Check for "Ghost" Input Device
        val currentIn = _selectedInputId.value
        if (currentIn != 0 && inputs.none { it.id == currentIn }) {
            Log.i("VoiceManager", "Selected Mic (ID $currentIn) removed. Reverting to Default.")
            _selectedInputId.value = 0
            mustRestartEngine = true
        }
        // 3. VALIDATION: Check for "Ghost" Output Device
        val currentOut = _selectedOutputId.value
        if (currentOut != 0 && outputs.none { it.id == currentOut }) {
            Log.i("VoiceManager", "Selected Speaker (ID $currentOut) removed. Reverting to Default.")
            _selectedOutputId.value = 0
            applyAudioRouting(0)
            mustRestartEngine = true
        }
        if (mustRestartEngine) restartEngineIfActive()

        _availableInputs.value = inputs.map { it.toFriendlyName(isInput = true) }
        _availableOutputs.value = outputs.map { it.toFriendlyName(isInput = false) }
    }

    private fun applyAudioRouting(deviceId: Int) {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val target = devices.find { it.id == deviceId }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: Use setCommunicationDevice
            if (target != null) {
                audioManager.setCommunicationDevice(target)
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else @Suppress("DEPRECATION") {
            // Android 11 and below: Legacy SCO/Speakerphone logic
            if (target != null) {
                if (target.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    audioManager.isSpeakerphoneOn = true
                    audioManager.stopBluetoothSco()
                } else if (target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || target.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } else {
                    // Wired Headset / Earpiece
                    audioManager.isSpeakerphoneOn = false
                    audioManager.stopBluetoothSco()
                }
            } else {
                // Default -> Earpiece usually
                audioManager.isSpeakerphoneOn = false
                audioManager.stopBluetoothSco()
            }
        }
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

    // --- Friendly Name Mapper ---
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