package com.denizetkar.walkietalkieapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.denizetkar.walkietalkieapp.domain.*
import com.denizetkar.walkietalkieapp.logic.MeshController
import com.denizetkar.walkietalkieapp.logic.VoiceManager
import com.denizetkar.walkietalkieapp.network.BleDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uniffi.walkie_talkie_engine.initLogger
import java.util.Locale

private data class ForegroundChanges(
    val hasSession: Boolean,
    val groupName: String?,
    val peerCount: Int,
    val language: AppLanguage,
)

class WalkieTalkieService : Service() {

    // Scope for parallel I/O (Drivers, Connections)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // The Core
    private val _meshController = MutableStateFlow<MeshController?>(null)
    val meshControllerState = _meshController.asStateFlow()
    private var driver: BleDriver? = null
    private var voiceManager: VoiceManager? = null

    // Inputs from UI (via Binder)
    private val uiActions = MutableSharedFlow<Action>(extraBufferCapacity = 64)

    private val binder = LocalBinder()

    // Pocket Mode: CPU WakeLock (Lazy initialization makes this a val)
    private val wakeLock by lazy {
        try {
            val pm = getSystemService(PowerManager::class.java)
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WalkieTalkie::CpuLock")
        } catch (e: Exception) {
            Log.w("WalkieTalkieService", "Failed to create WakeLock", e)
            null
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
        fun dispatchAction(action: Action) {
            uiActions.tryEmit(action)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("WalkieTalkieService", "Initializing WalkieTalkieService")

        // Initialize Rust Logger to pipe Rust logs to Android Logcat
        try {
            initLogger()
        } catch (e: Exception) {
            Log.w("WalkieTalkieService", "Could not init Rust logger (might be already init)", e)
        }

        // 1. Acquire WakeLock (Pocket Mode)
        // Accessing the lazy property here triggers creation.
        wakeLock?.let { lock ->
            try {
                lock.acquire(Config.WAKE_LOCK_TIMEOUT)
                Log.d("WalkieTalkieService", "WakeLock Acquired")
            } catch (e: Exception) {
                Log.w("WalkieTalkieService", "Failed to acquire WakeLock", e)
            }
        }

        val controller = MeshController(serviceScope)

        // STANDARD DIRECT LANE: Both drivers get the same dispatch reference
        val dispatch = controller::dispatch

        val driverInstance = BleDriver(this, serviceScope, dispatch=dispatch)
        driver = driverInstance

        val voiceManagerInstance = VoiceManager(this, serviceScope, dispatch=dispatch)
        voiceManager = voiceManagerInstance

        _meshController.value = controller

        // --- 1. Event Aggregation (Inputs -> Core) ---
        // We merge UI events and Timers. Drivers dispatch directly.
        serviceScope.launch {
            // Tickers
            val heartbeats = flow {
                while (currentCoroutineContext().isActive) {
                    delay(Config.HEARTBEAT_INTERVAL)
                    emit(Action.HeartbeatTick(System.currentTimeMillis()))
                }
            }
            val cleanups = flow {
                while (currentCoroutineContext().isActive) {
                    delay(Config.CLEANUP_PERIOD)
                    emit(Action.CleanupTick(System.currentTimeMillis()))
                }
            }

            // Merge UI and Timers
            merge(
                uiActions,
                heartbeats,
                cleanups
            ).collect { action ->
                controller.dispatch(action)
            }
        }

        // --- 2. Effect Handling (Core -> Outputs) ---

        // A. Network Effects -> Driver
        // Driver should react to configuration changes (Scanning/Advertising)
        driverInstance.bind(controller.state, controller.effects)

        // B. Audio Configuration -> Voice Manager
        val gateFlow = controller.state
            .map { it.isMicEnabled }
            .distinctUntilChanged()

        // Combine inputs: Device selection AND Node ID (Rotation) AND Session Existence
        val audioConfigFlow = combine(
            controller.state.map { it.session != null }.distinctUntilChanged(),
            controller.state.map { it.selectedAudioDevice }.distinctUntilChanged(),
            controller.state.map { it.myself }.distinctUntilChanged()
        ) { hasSession, deviceId, nodeId ->
            if (hasSession) Pair(deviceId, nodeId) else null
        }

        voiceManagerInstance.bind(gateFlow, audioConfigFlow)

        // C. Audio Render & UI Effects
        serviceScope.launch {
            controller.effects.collect { effect ->
                when (effect) {
                    is Effect.RenderAudio -> voiceManagerInstance.renderAudio(effect.data)
                    is Effect.ShowToast -> {
                        val ctx = getLocalizedContext(effect.language)
                        mainScope.launch {
                            Toast.makeText(this@WalkieTalkieService, ctx.getString(effect.messageRes), Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {} // Handled by bound drivers
                }
            }
        }

        // --- 3. Lifecycle & Foreground Management ---
        serviceScope.launch {
            controller.state
                .map { state ->
                    ForegroundChanges(
                        hasSession = state.session != null,
                        groupName = state.session?.groupName,
                        peerCount = state.connectedPeers.size,
                        language = state.language,
                    )
                }
                .distinctUntilChanged()
                .collectLatest { changes ->
                    if (changes.hasSession) {
                        promoteToForeground(changes)
                    } else {
                        demoteToBackground()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        // Release WakeLock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("WalkieTalkieService", "WakeLock Released")
            }
        } catch (_: Exception) {}

        voiceManager?.close()
        driver?.close()
        serviceScope.cancel()
        mainScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun getLocalizedContext(language: AppLanguage): Context {
        val locale = if (language == AppLanguage.SYSTEM) {
            Resources.getSystem().configuration.locales.get(0)
        } else {
            Locale.forLanguageTag(language.tag)
        }
        val config = Configuration(resources.configuration).apply {
            setLocale(locale)
        }
        return createConfigurationContext(config)
    }

    private fun promoteToForeground(changes: ForegroundChanges) {
        val localizedContext = getLocalizedContext(changes.language)
        val channelId = "WalkieTalkieChannel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            localizedContext.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)

        // Ensure we resume the existing Activity instead of creating a new one.
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val peerCount = changes.peerCount
        val groupName = changes.groupName ?: localizedContext.getString(R.string.radio_unknown_group)

        val contentText = localizedContext.getString(R.string.notification_content_live, groupName, peerCount)

        val notification: Notification = NotificationCompat.Builder(localizedContext, channelId)
            .setContentTitle(localizedContext.getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.w("WalkieTalkieService", "Could not promote to Foreground.", e)
        }
    }

    private fun demoteToBackground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}