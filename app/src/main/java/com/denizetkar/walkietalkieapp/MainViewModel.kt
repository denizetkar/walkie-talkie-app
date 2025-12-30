package com.denizetkar.walkietalkieapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denizetkar.walkietalkieapp.logic.DiscoveredGroup
import com.denizetkar.walkietalkieapp.logic.MeshNetworkManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

data class AppState(
    val groupName: String? = null,
    val accessCode: String? = null,
    val peerCount: Int = 0,
    val isScanning: Boolean = false,
    val discoveredGroups: List<DiscoveredGroup> = emptyList(),
    val isJoining: Boolean = false,
    val joinError: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val meshManager = MeshNetworkManager(application, viewModelScope)

    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    init {
        viewModelScope.launch {
            meshManager.peerCount.collect { count ->
                _appState.update { it.copy(peerCount = count) }
            }
        }
        viewModelScope.launch {
            meshManager.isScanning.collect { scanning ->
                _appState.update { it.copy(isScanning = scanning) }
            }
        }
        viewModelScope.launch {
            meshManager.discoveredGroups.collect { groups ->
                _appState.update { it.copy(discoveredGroups = groups) }
            }
        }
    }

    fun startScanning() {
        if (_appState.value.groupName != null) return
        meshManager.startGroupScan()
    }

    fun stopScanning() {
        meshManager.stopGroupScan()
    }

    fun createGroup(name: String) {
        val code = Random.nextInt(1000, 9999).toString()

        _appState.update { it.copy(groupName = name, accessCode = code) }
        meshManager.startMesh(name, code, true)
    }

    fun joinGroup(name: String, code: String) {
        _appState.update { it.copy(isJoining = true, joinError = null) }
        meshManager.startMesh(name, code, false)
        viewModelScope.launch {
            try {
                withTimeout(Config.GROUP_JOIN_TIMEOUT) {
                    meshManager.peerCount.first { it > 0 }
                }
                _appState.update { it.copy(groupName = name, accessCode = code, isJoining = false) }
            } catch (_: TimeoutCancellationException) {
                meshManager.stopMesh()
                _appState.update { it.copy(isJoining = false, joinError = "Invalid Code or Connection Failed") }
            }
        }
    }

    fun ackJoinError() {
        _appState.update { it.copy(joinError = null) }
    }

    fun leaveGroup() {
        meshManager.stopMesh()
        _appState.update { AppState() }
    }

    fun startTalking() = meshManager.startTalking()
    fun stopTalking() = meshManager.stopTalking()

    override fun onCleared() {
        super.onCleared()
        meshManager.stopMesh()
    }
}