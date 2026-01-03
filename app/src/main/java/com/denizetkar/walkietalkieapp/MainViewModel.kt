package com.denizetkar.walkietalkieapp

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.denizetkar.walkietalkieapp.logic.EngineState
import com.denizetkar.walkietalkieapp.logic.MeshController
import com.denizetkar.walkietalkieapp.network.DiscoveredGroup
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
    val hasPermissions: Boolean = false,
    val isServiceBound: Boolean = false,
    val serviceStartupFailed: Boolean = false,

    val groupName: String? = null,
    val accessCode: String? = null,
    val peerCount: Int = 0,
    val isScanning: Boolean = false,
    val discoveredGroups: List<DiscoveredGroup> = emptyList(),
    val isJoining: Boolean = false,
    val joinError: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {

    private var meshController: MeshController? = null
    private var stateCollectionJob: Job? = null

    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WalkieTalkieService.LocalBinder
            meshController = binder.getService().meshController
            _appState.update { it.copy(isServiceBound = true, serviceStartupFailed = false) }
            subscribeToController()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateCollectionJob?.cancel()
            meshController = null
            _appState.update { it.copy(isServiceBound = false) }
        }

        override fun onBindingDied(name: ComponentName?) {
            _appState.update { it.copy(isServiceBound = false) }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        if (_appState.value.hasPermissions && !_appState.value.isServiceBound) {
            bindService()
        }
    }

    fun onPermissionsGranted() {
        _appState.update { it.copy(hasPermissions = true) }
        bindService()
    }

    fun retryConnection() {
        bindService()
    }

    private fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, WalkieTalkieService::class.java)
        try {
            context.startForegroundService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            _appState.update { it.copy(serviceStartupFailed = false) }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to start service", e)
            _appState.update { it.copy(serviceStartupFailed = true) }
        }
    }

    private fun subscribeToController() {
        val controller = meshController ?: return
        stateCollectionJob?.cancel()

        stateCollectionJob = viewModelScope.launch {
            // 1. Observe Engine State
            launch {
                controller.state.collect { engineState ->
                    _appState.update { current ->
                        when (engineState) {
                            is EngineState.Idle -> current.copy(
                                groupName = null,
                                accessCode = null,
                                peerCount = 0,
                                isScanning = false,
                                isJoining = false
                            )
                            is EngineState.Discovering -> current.copy(
                                isScanning = true,
                                isJoining = false
                            )
                            is EngineState.Joining -> current.copy(
                                isJoining = true,
                                groupName = engineState.groupName
                            )
                            is EngineState.RadioActive -> current.copy(
                                groupName = engineState.groupName,
                                peerCount = engineState.peerCount,
                                isJoining = false,
                                isScanning = false // It scans internally, but UI doesn't need to show spinner
                            )
                        }
                    }
                }
            }

            // 2. Observe Discovered Groups
            launch {
                controller.discoveredGroups.collect { groups ->
                    _appState.update { it.copy(discoveredGroups = groups) }
                }
            }
        }
    }

    fun startScanning() {
        if (_appState.value.groupName != null) return
        meshController?.startGroupScan()
    }

    fun stopScanning() {
        meshController?.stopGroupScan()
    }

    fun createGroup(name: String) {
        if (!checkSystemRequirements()) return

        val code = Random.nextInt(1000, 9999).toString()
        _appState.update { it.copy(groupName = name, accessCode = code) }
        meshController?.createGroup(name, code)
    }

    fun joinGroup(name: String, code: String) {
        if (!checkSystemRequirements()) return
        val controller = meshController ?: return

        _appState.update { it.copy(isJoining = true, joinError = null, accessCode = code) }
        controller.joinGroup(name, code)
        viewModelScope.launch {
            try {
                withTimeout(Config.GROUP_JOIN_TIMEOUT) {
                    controller.state.first { it is EngineState.RadioActive }
                }
                _appState.update { it.copy(accessCode = code) }
            } catch (_: TimeoutCancellationException) {
                controller.leave()
                _appState.update { it.copy(isJoining = false, joinError = "Connection Timed Out", accessCode = null) }
            }
        }
    }

    fun ackJoinError() {
        _appState.update { it.copy(joinError = null) }
    }

    fun leaveGroup() {
        meshController?.leave()
    }

    fun startTalking() = meshController?.startTalking()
    fun stopTalking() = meshController?.stopTalking()

    override fun onCleared() {
        super.onCleared()
        if (!_appState.value.isServiceBound) return

        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w("MainViewModel", "Unbind failed", e)
        }
    }

    private fun checkSystemRequirements(): Boolean {
        val check = meshController?.checkSystemRequirements()
        if (check == null || check.isFailure) {
            val error = check?.exceptionOrNull()?.message ?: "Service not bound"
            Log.e("MainViewModel", "System Requirements Failed: $error")
            _appState.update { it.copy(joinError = error) }
            return false
        }

        return true
    }
}