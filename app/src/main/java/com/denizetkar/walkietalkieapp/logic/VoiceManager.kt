package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import uniffi.walkie_talkie_engine.AudioConfig
import uniffi.walkie_talkie_engine.AudioEngine
import uniffi.walkie_talkie_engine.PacketTransport

/**
 * The Single Source of Truth for the Voice Subsystem.
 * Encapsulates Android System Policy (Focus, Mode) and the Rust Hardware Engine.
 *
 * Implements the "Hot Mic" architecture: Hardware streams are kept open
 * during the entire session, but the microphone gate is toggled via software.
 */
class VoiceManager(
    context: Context,
    packetTransport: PacketTransport // Callback to send data to BLE
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    // Rust Engine Configuration
    private val rustConfig = AudioConfig(
        sampleRate = Config.AUDIO_SAMPLE_RATE,
        frameSizeMs = Config.AUDIO_FRAME_SIZE_MS,
        jitterBufferMs = Config.AUDIO_JITTER_BUFFER_MS
    )

    // The Rust Engine is private. Only this class manages its lifecycle.
    private val engine = AudioEngine(rustConfig, packetTransport)

    /**
     * Call this when entering the "Radio" screen (Joining a group).
     * 1. Sets Android Audio Mode (Communication)
     * 2. Requests Audio Focus
     * 3. Starts the Rust Input/Output streams (Hot Mic)
     */
    fun start() {
        Log.i("VoiceManager", "Starting Voice System...")

        // 1. Android System Setup
        // Essential for Hardware Echo Cancellation (AEC) and routing to earpiece/speaker
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphone(true)
        requestAudioFocus()

        // 2. Hardware Setup (Rust)
        try {
            engine.startSession() // Starts Oboe streams. Latency hit happens here once.
            engine.setMicEnabled(false) // Start Muted
        } catch (e: Exception) {
            Log.e("VoiceManager", "Failed to start Rust Engine", e)
        }
    }

    /**
     * Call this when leaving the "Radio" screen.
     */
    fun stop() {
        Log.i("VoiceManager", "Stopping Voice System...")

        // 1. Teardown Hardware
        try {
            engine.stopSession()
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error stopping engine", e)
        }

        // 2. Teardown Android System
        abandonAudioFocus()
        setSpeakerphone(false)
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    /**
     * Push-to-Talk Logic.
     * @param enabled true = Broadcast audio, false = Mute (Hardware stays running)
     */
    fun setMicrophoneEnabled(enabled: Boolean) {
        engine.setMicEnabled(enabled)
    }

    /**
     * Feeds incoming BLE Audio Data into the Rust Jitter Buffer.
     */
    fun processIncomingPacket(data: ByteArray) {
        try {
            engine.pushIncomingPacket(data)
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error pushing packet to engine", e)
        }
    }

    // --- Private Android Audio Helpers ---

    private fun setSpeakerphone(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enable) {
                val devices = audioManager.availableCommunicationDevices
                val speaker = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    val result = audioManager.setCommunicationDevice(speaker)
                    if (!result) Log.w("VoiceManager", "Could not set speaker device")
                } else {
                    Log.w("VoiceManager", "No built-in speaker found")
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enable
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
                        Log.i("AudioSession", "Focus Lost: Stopping Audio")
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
}