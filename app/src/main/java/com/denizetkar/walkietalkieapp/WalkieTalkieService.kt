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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.denizetkar.walkietalkieapp.logic.MeshController
import com.denizetkar.walkietalkieapp.network.BleDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.random.nextUInt

class WalkieTalkieService : Service() {

    // Scope for parallel I/O (Drivers, Connections)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Scope for Logic (Mesh State Machine)
    // REASON: MeshController is now Mutex-guarded (Thread Safe).
    // We allow parallel execution for better throughput.
    private val logicScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _meshController = MutableStateFlow<MeshController?>(null)
    val meshControllerState = _meshController.asStateFlow()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()

        serviceScope.launch {
            try {
                Log.i("WalkieTalkieService", "Initializing Engine in Background...")

                // Driver runs on the Service Scope
                val driver = BleDriver(this@WalkieTalkieService, serviceScope)
                val myNodeId = Random.nextUInt()
                Log.i("WalkieTalkieService", "Generated Node ID: $myNodeId")

                // Controller runs on the Logic Scope
                val controller = MeshController(this@WalkieTalkieService, driver, logicScope, myNodeId)
                _meshController.value = controller
                Log.i("WalkieTalkieService", "Engine Initialized.")
            } catch (e: Exception) {
                Log.e("WalkieTalkieService", "Fatal Init Error", e)
                // In a real app, you might emit a specific Error state here
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
        _meshController.value?.let {
            it.leave()
            it.destroy()
        }

        // Cancel both scopes
        logicScope.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startForegroundService() {
        val channelId = "WalkieTalkieChannel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Walkie Talkie Service", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Walkie Talkie Active")
            .setContentText("Radio is ON.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.w("WalkieTalkieService", "Could not promote to Foreground.", e)
        }
    }
}