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
import com.denizetkar.walkietalkieapp.domain.Action
import com.denizetkar.walkietalkieapp.domain.AppState
import com.denizetkar.walkietalkieapp.domain.DiscoveredGroup
import com.denizetkar.walkietalkieapp.logic.MeshController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

// Shared Data Class for UI
data class AudioDeviceUi(val id: Int, val displayName: String)

/**
 * UI State Model.
 * This is a subset/mapping of the domain [AppState].
 */
data class AppUiState(
    val hasPermissions: Boolean = false,
    val isServiceBound: Boolean = false,
    val serviceStartupFailed: Boolean = false,

    // Session State
    val groupName: String? = null,
    val accessCode: String? = null,
    val peerCount: Int = 0,

    // UI Logic
    val isScanning: Boolean = false,
    val isJoining: Boolean = false,
    val joinError: String? = null,

    val discoveredGroups: List<DiscoveredGroup> = emptyList(),

    // Audio State
    val availableMics: List<AudioDeviceUi> = emptyList(),
    val availableSpeakers: List<AudioDeviceUi> = emptyList(),
    val selectedMicId: Int = 0,
    val selectedSpeakerId: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {

    // Reactive Binder: Allows suspending wait instead of polling
    private val _binder = MutableStateFlow<WalkieTalkieService.LocalBinder?>(null)
    private var stateCollectionJob: Job? = null

    private val _appState = MutableStateFlow(AppUiState())
    val appState = _appState.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as WalkieTalkieService.LocalBinder
            _binder.value = localBinder

            val walkieService = localBinder.getService()

            viewModelScope.launch {
                try {
                    // Wait for the controller to be initialized in the service
                    val controller = walkieService.meshControllerState.filterNotNull().first()
                    _appState.update { it.copy(isServiceBound = true, serviceStartupFailed = false) }
                    subscribeToController(controller)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Service Init Failed", e)
                    _appState.update { it.copy(isServiceBound = false, serviceStartupFailed = true) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateCollectionJob?.cancel()
            _binder.value = null
            _appState.update { it.copy(isServiceBound = false) }
        }

        override fun onBindingDied(name: ComponentName?) {
            _binder.value = null
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
            context.startService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            _appState.update { it.copy(serviceStartupFailed = false) }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to start service", e)
            _appState.update { it.copy(serviceStartupFailed = true) }
        }
    }

    private fun subscribeToController(controller: MeshController) {
        stateCollectionJob?.cancel()
        stateCollectionJob = viewModelScope.launch(Dispatchers.Default) {
            controller.state.collect { coreState ->
                _appState.update { ui ->
                    ui.copy(
                        groupName = coreState.session?.groupName,
                        accessCode = coreState.session?.accessCode,
                        peerCount = coreState.connectedPeers.size,
                        discoveredGroups = coreState.discoveredGroups,
                        // We are joining if there is a session but no peers yet
                        isJoining = (coreState.session != null && coreState.connectedPeers.isEmpty()),
                        // We are scanning if NO session is active
                        isScanning = (coreState.session == null),
                        // Map Error from Core
                        joinError = coreState.joinError,

                        // Map Audio State
                        availableMics = coreState.availableMics,
                        availableSpeakers = coreState.availableSpeakers,
                        selectedMicId = coreState.selectedInputId,
                        selectedSpeakerId = coreState.selectedOutputId
                    )
                }
            }
        }
    }

    // --- Safe Dispatcher ---
    // Suspends until the service is bound, then dispatches.
    private fun dispatch(action: Action) {
        viewModelScope.launch {
            val binder = _binder.filterNotNull().first()
            binder.dispatchAction(action)
        }
    }

    // --- User Actions ---

    fun startScanning() {
        dispatch(Action.StartScanning)
    }

    fun stopScanning() {
        dispatch(Action.StopScanning)
    }

    fun createGroup(name: String) {
        val code = Random.nextInt(1000, 9999).toString()
        dispatch(Action.CreateGroup(name, code))
    }

    fun joinGroup(name: String, code: String) {
        _appState.update { it.copy(isJoining = true, joinError = null) }
        dispatch(Action.JoinGroup(name, code))
    }

    fun ackJoinError() {
        _appState.update { it.copy(joinError = null) }
    }

    fun leaveGroup() {
        dispatch(Action.LeaveGroup())
        _appState.update { it.copy(isJoining = false, groupName = null) }
    }

    fun startTalking() = dispatch(Action.SetMic(true))
    fun stopTalking() = dispatch(Action.SetMic(false))
    fun setMicrophone(id: Int) = dispatch(Action.SetAudioInput(id))
    fun setSpeaker(id: Int) = dispatch(Action.SetAudioOutput(id))

    override fun onCleared() {
        super.onCleared()
        if (!_appState.value.isServiceBound) return
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w("MainViewModel", "Unbind failed", e)
        }
    }
}