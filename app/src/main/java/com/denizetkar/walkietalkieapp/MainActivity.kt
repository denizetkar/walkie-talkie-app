package com.denizetkar.walkietalkieapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
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

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(viewModel)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(viewModel)
        }
    }

    // --- Permission Logic ---
    // We build the list dynamically based on the Android Version.
    val permissionsToRequest = remember(Unit) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)

        // Android 12 (S) and above: Modern BLE permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 and below: Legacy Location-based BLE
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // Android 13 (Tiramisu) and above: Notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        perms.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // We check if the *essential* permissions are granted.
        // Note: POST_NOTIFICATIONS is technically optional for app function, but good for UX.
        // RECORD_AUDIO and BLE are critical.
        val essentialGranted = permissions.entries.all { (perm, granted) ->
            if (perm == Manifest.permission.POST_NOTIFICATIONS) true // Ignore notification denial for core logic
            else granted
        }

        if (essentialGranted) {
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

        state.serviceStartupFailed -> {
            ServiceErrorScreen(onRetry = { viewModel.retryConnection() })
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
                    onCreate = { name -> viewModel.createGroup(name) },
                    error = state.joinError,
                    onErrorAck = { viewModel.ackJoinError() }
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
                    peerCount = state.peerCount,

                    availableMics = state.availableMics,
                    availableSpeakers = state.availableSpeakers,
                    selectedMicId = state.selectedMicId,
                    selectedSpeakerId = state.selectedSpeakerId,
                    onMicSelect = { viewModel.setMicrophone(it) },
                    onSpeakerSelect = { viewModel.setSpeaker(it) },

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