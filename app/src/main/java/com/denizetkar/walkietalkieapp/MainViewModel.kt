package com.denizetkar.walkietalkieapp

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denizetkar.walkietalkieapp.logic.DiscoveredGroup
import com.denizetkar.walkietalkieapp.logic.MeshNetworkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

data class AppState(
    // Gatekeeper States
    val hasPermissions: Boolean = false,
    val isServiceBound: Boolean = false,

    // App States
    val groupName: String? = null,
    val accessCode: String? = null,
    val peerCount: Int = 0,
    val isScanning: Boolean = false,
    val discoveredGroups: List<DiscoveredGroup> = emptyList(),
    val isJoining: Boolean = false,
    val joinError: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var meshManager: MeshNetworkManager? = null
    private var managerCollectionJob: Job? = null

    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WalkieTalkieService.LocalBinder
            meshManager = binder.getService().meshManager
            _appState.update { it.copy(isServiceBound = true) }
            subscribeToManager()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            managerCollectionJob?.cancel()
            meshManager = null
            _appState.update { it.copy(isServiceBound = false) }
        }
    }

    fun onPermissionsGranted() {
        _appState.update { it.copy(hasPermissions = true) }
        bindService()
    }

    private fun bindService() {
        if (_appState.value.isServiceBound) return

        val context = getApplication<Application>()
        try {
            val intent = Intent(context, WalkieTalkieService::class.java)
            context.startForegroundService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to start service", e)
        }
    }

    private fun subscribeToManager() {
        val manager = meshManager ?: return
        managerCollectionJob?.cancel()

        managerCollectionJob = viewModelScope.launch {
            launch { manager.peerCount.collect { count -> _appState.update { it.copy(peerCount = count) } } }
            launch { manager.isScanning.collect { scanning -> _appState.update { it.copy(isScanning = scanning) } } }
            launch { manager.discoveredGroups.collect { groups -> _appState.update { it.copy(discoveredGroups = groups) } } }
        }
    }

    fun startScanning() {
        if (_appState.value.groupName != null) return

        meshManager?.startGroupScan()
    }

    fun stopScanning() {
        meshManager?.stopGroupScan()
    }

    fun createGroup(name: String) {
        val code = Random.nextInt(1000, 9999).toString()
        _appState.update { it.copy(groupName = name, accessCode = code) }
        meshManager?.startMesh(name, code, true)
    }

    fun joinGroup(name: String, code: String) {
        val manager = meshManager ?: return

        _appState.update { it.copy(isJoining = true, joinError = null) }
        manager.startMesh(name, code, false)
        viewModelScope.launch {
            try {
                withTimeout(Config.GROUP_JOIN_TIMEOUT) {
                    manager.peerCount.first { it > 0 }
                }
                _appState.update { it.copy(groupName = name, accessCode = code, isJoining = false) }
            } catch (_: TimeoutCancellationException) {
                manager.stopMesh()
                _appState.update { it.copy(isJoining = false, joinError = "Invalid Code or Connection Failed") }
            }
        }
    }

    fun ackJoinError() {
        _appState.update { it.copy(joinError = null) }
    }

    fun leaveGroup() {
        meshManager?.stopMesh()
        _appState.update { it.copy(groupName = null, accessCode = null, peerCount = 0) }
    }

    fun startTalking() = meshManager?.startTalking()
    fun stopTalking() = meshManager?.stopTalking()

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unbindService(serviceConnection)
    }
}