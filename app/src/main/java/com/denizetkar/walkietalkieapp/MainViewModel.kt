package com.denizetkar.walkietalkieapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch

data class AppState(
    val myGroupName: String? = null,
    val joinedGroupName: String? = null,
    val discoveredGroups: List<DiscoveredGroup> = emptyList(),
    val isScanning: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val bluetoothManager = WalkieTalkieBluetoothManager(application)
    private var scanJob: Job? = null

    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    fun createGroup(groupName: String) {
        bluetoothManager.startHosting(groupName)
        _appState.update { it.copy(myGroupName = groupName) }
    }

    fun startScanning() {
        // 1. Cancel any existing scan to force a fresh start
        scanJob?.cancel()

        _appState.update { it.copy(isScanning = true) }

        scanJob = viewModelScope.launch {
            bluetoothManager.scanForGroups()
                .catch {
                    // Handle errors (like BT being turned off mid-scan)
                    _appState.update { it.copy(isScanning = false) }
                }
                .collect { groups ->
                    _appState.update { it.copy(discoveredGroups = groups) }
                }
        }
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        _appState.update { it.copy(isScanning = false) }
    }

    fun stopHostingOrScanning() {
        bluetoothManager.stopHosting()

        stopScanning()

        _appState.update { AppState() }
    }

    fun joinGroup(group: DiscoveredGroup) {
        // Mock connection for now
        _appState.update { it.copy(joinedGroupName = group.name) }
        // Stop scanning when joined
        // In real app, we would connectGatt here
    }

    fun leaveGroup() {
        _appState.update { it.copy(joinedGroupName = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopHostingOrScanning()
    }
}
