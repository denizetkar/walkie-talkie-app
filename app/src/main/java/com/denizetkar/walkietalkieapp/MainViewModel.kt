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
import kotlin.random.Random

data class AppState(
    val groupName: String? = null,
    val accessCode: String? = null,
    val isHosting: Boolean = false,
    val discoveredGroups: List<DiscoveredGroup> = emptyList(),
    val isScanning: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val bluetoothManager = WalkieTalkieBluetoothManager(application)
    private var scanJob: Job? = null

    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    fun createGroup(name: String) {
        val code = Random.nextInt(1000, 9999).toString()

        bluetoothManager.startHosting(name)
        _appState.update {
            it.copy(
                groupName = name,
                accessCode = code,
                isHosting = true
            )
        }
    }

    fun startScanning() {
        scanJob?.cancel()

        _appState.update { it.copy(isScanning = true) }

        scanJob = viewModelScope.launch {
            bluetoothManager.scanForGroups()
                .catch { _appState.update { it.copy(isScanning = false) } }
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

    fun joinGroup(group: DiscoveredGroup, code: String) {
        stopScanning()

        _appState.update {
            it.copy(
                groupName = group.name,
                accessCode = code,
                isHosting = false
            )
        }

        // TODO: In the future, this is where we initiate the GATT connection
        // and send the 'code' to the host for verification.
    }

    fun leaveGroup() = stopHostingOrScanning()

    override fun onCleared() {
        super.onCleared()
        stopHostingOrScanning()
    }
}
