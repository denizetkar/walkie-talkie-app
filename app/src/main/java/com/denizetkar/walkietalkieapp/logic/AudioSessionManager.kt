package com.denizetkar.walkietalkieapp.logic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class AudioSessionManager(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    fun startSession() {
        Log.d("AudioSession", "Starting Voice Session...")

        // 1. Set Audio Mode to COMMUNICATION
        // This tells Android to optimize the mic/speaker for Voice (enables HW Echo Cancellation)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // 2. Request Audio Focus (Stop Spotify/YouTube if playing)
        requestAudioFocus()

        // 3. Force Speakerphone
        setSpeakerphone(true)
    }

    fun stopSession() {
        Log.d("AudioSession", "Stopping Voice Session...")
        abandonAudioFocus()
        setSpeakerphone(false)
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun setSpeakerphone(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ (Android 12): Use setCommunicationDevice
            if (enable) {
                val devices = audioManager.availableCommunicationDevices
                val speaker = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    val result = audioManager.setCommunicationDevice(speaker)
                    if (!result) Log.w("AudioSession", "Could not set speaker device")
                } else {
                    Log.w("AudioSession", "No built-in speaker found")
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            // API 28 - 30 (Android 9 - 11): Use legacy isSpeakerphoneOn
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enable
        }
    }

    private fun requestAudioFocus() {
        // Since minSdk = 28, we can use AudioAttributes and AudioFocusRequest directly (Added in API 26)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.i("AudioSession", "Focus Lost: Stopping Audio")
                        // In a production app, you would notify the UI/Manager to stop recording here
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