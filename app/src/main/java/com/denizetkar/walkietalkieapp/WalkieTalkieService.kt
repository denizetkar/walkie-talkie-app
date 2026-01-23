package com.denizetkar.walkietalkieapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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

        val driverInstance = BleDriver(this, serviceScope, dispatch)
        driver = driverInstance

        val voiceManagerInstance = VoiceManager(this, serviceScope, dispatch)
        voiceManager = voiceManagerInstance

        _meshController.value = controller

        // --- 1. Event Aggregation (Inputs -> Core) ---
        // We merge UI events and Timers. Drivers dispatch directly.
        serviceScope.launch {
            // Tickers
            val heartbeats = flow {
                while (currentCoroutineContext().isActive) {
                    delay(Config.HEARTBEAT_INTERVAL)
                    emit(Action.HeartbeatTick)
                }
            }
            val cleanups = flow {
                while (currentCoroutineContext().isActive) {
                    delay(Config.CLEANUP_PERIOD)
                    emit(Action.CleanupTick)
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

        // Combine inputs: Device selection AND Node ID (Rotation)
        val audioConfigFlow = combine(
            controller.state.map { it.selectedInputId }.distinctUntilChanged(),
            controller.state.map { it.selectedOutputId }.distinctUntilChanged(),
            controller.state.map { it.myself }.distinctUntilChanged()
        ) { inId, outId, nodeId ->
            Triple(inId, outId, nodeId)
        }

        voiceManagerInstance.bind(gateFlow, audioConfigFlow)

        // C. Audio Render & UI Effects
        serviceScope.launch {
            controller.effects.collect { effect ->
                when (effect) {
                    is Effect.RenderAudio -> voiceManagerInstance.renderAudio(effect.data)
                    is Effect.ShowToast -> {
                        mainScope.launch {
                            Toast.makeText(this@WalkieTalkieService, effect.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {} // Handled by bound drivers
                }
            }
        }

        // --- 3. Lifecycle & Foreground Management ---
        serviceScope.launch {
            controller.state.collectLatest { state ->
                if (state.session != null) {
                    promoteToForeground(state)
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

    private fun promoteToForeground(state: AppState) {
        val channelId = "WalkieTalkieChannel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Walkie Talkie Service", NotificationManager.IMPORTANCE_LOW)
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

        val peerCount = state.connectedPeers.size
        val groupName = state.session?.groupName ?: "Unknown"

        val contentText = "Live: $groupName ($peerCount Peers)"

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Walkie Talkie Active")
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