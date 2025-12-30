package com.denizetkar.walkietalkieapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    companion object {
        init {
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("walkie_talkie_engine")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("MainActivity", "Failed to load native libraries", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WalkieTalkieApp()
        }
    }
}

@Composable
fun WalkieTalkieApp() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel()
    val state by viewModel.appState.collectAsState()

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.RECORD_AUDIO
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.onPermissionsGranted()
        }
    }

    LaunchedEffect(Unit) {
        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            viewModel.onPermissionsGranted()
        }
    }

    when {
        !state.hasPermissions -> {
            PermissionRequiredScreen(
                onGrantClick = { permissionLauncher.launch(permissionsToRequest) }
            )
        }
        !state.isServiceBound -> { LoadingScreen("Starting Audio Engine...") }
        else -> {
            WalkieTalkieNavHost(viewModel, state)
        }
    }
}

@Composable
fun WalkieTalkieNavHost(viewModel: MainViewModel, state: AppState) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                if (state.groupName == null) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Add, null) },
                        label = { Text("Create") },
                        selected = currentRoute == "create",
                        onClick = { navController.navigate("create") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Group, null) },
                        label = { Text("Join") },
                        selected = currentRoute == "join",
                        onClick = { navController.navigate("join") }
                    )
                } else {
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Radio, null) },
                        label = { Text("Radio") },
                        selected = true,
                        onClick = { }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "create",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("create") {
                LaunchedEffect(state.groupName) {
                    if (state.groupName != null) navController.navigate("radio")
                }
                CreateGroupScreen(
                    onCreate = { name -> viewModel.createGroup(name) }
                )
            }

            composable("join") {
                LaunchedEffect(state.groupName) {
                    if (state.groupName != null) navController.navigate("radio")
                }

                DisposableEffect(Unit) {
                    viewModel.startScanning()
                    onDispose { viewModel.stopScanning() }
                }

                JoinGroupScreen(
                    discoveredGroups = state.discoveredGroups,
                    isJoining = state.isJoining,
                    joinError = state.joinError,
                    onJoin = { group, code -> viewModel.joinGroup(group.name, code) },
                    onJoinErrorAck = { viewModel.ackJoinError() }
                )
            }

            composable("radio") {
                RadioScreen(
                    groupName = state.groupName,
                    accessCode = state.accessCode,
                    onLeave = {
                        viewModel.leaveGroup()
                        navController.navigate("create") { popUpTo("create") { inclusive = true } }
                    },
                    onTalkStart = { viewModel.startTalking() },
                    onTalkStop = { viewModel.stopTalking() }
                )
            }
        }
    }
}