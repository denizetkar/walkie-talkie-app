package com.denizetkar.walkietalkieapp

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppState(
    val myGroupName: String? = null,
    val joinedGroupName: String? = null
)

class MainViewModel : ViewModel() {
    private val _appState = MutableStateFlow(AppState())
    val appState = _appState.asStateFlow()

    fun createGroup(groupName: String) {
        _appState.update { it.copy(myGroupName = groupName) }
    }

    fun joinGroup(groupName: String) {
        _appState.update { it.copy(joinedGroupName = groupName) }
    }

    fun leaveGroup() {
        _appState.update { it.copy(joinedGroupName = null) }
    }
}
