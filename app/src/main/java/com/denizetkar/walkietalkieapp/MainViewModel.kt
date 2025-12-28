package com.denizetkar.walkietalkieapp

import android.app.Application
import android.util.Log
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
    val discoveredNodes: List<DiscoveredNode> = emptyList(),
    val connectedPeers: List<DiscoveredNode> = emptyList(),
    val isScanning: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val bluetoothManager = WalkieTalkieBluetoothManager(application)
    private var discoveryJob: Job? = null
    private var radioJob: Job? = null

    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    fun startScanning() {
        if (_appState.value.groupName != null) return

        discoveryJob?.cancel()
        _appState.update { it.copy(isScanning = true) }

        discoveryJob = viewModelScope.launch {
            bluetoothManager.scanGlobal()
                .catch { e ->
                    Log.e("VM", "Scan Error", e)
                    _appState.update { it.copy(isScanning = false) }
                }
                .collect { nodes ->
                    _appState.update { it.copy(discoveredNodes = nodes) }
                }
        }
    }

    fun stopScanning() {
        discoveryJob?.cancel()
        discoveryJob = null
        _appState.update { it.copy(isScanning = false) }
    }

    fun createGroup(name: String) {
        val code = Random.nextInt(1000, 9999).toString()
        startRadioMode(name, code)
    }

    fun joinGroup(node: DiscoveredNode, code: String) {
        startRadioMode(node.groupName, code)
    }

    private fun startRadioMode(name: String, code: String) {
        stopScanning()

        _appState.update {
            it.copy(
                groupName = name,
                accessCode = code,
                connectedPeers = emptyList()
            )
        }

        radioJob?.cancel()
        radioJob = viewModelScope.launch {
            bluetoothManager.startNode(name)
                .catch { e -> Log.e("VM", "Node Error", e) }
                .collect { peers ->
                    _appState.update { it.copy(connectedPeers = peers) }
                }
        }
    }

    fun leaveGroup() {
        radioJob?.cancel()
        radioJob = null
        _appState.update { AppState() }
    }

    override fun onCleared() {
        super.onCleared()
        stopScanning()
        radioJob?.cancel()
    }
}
