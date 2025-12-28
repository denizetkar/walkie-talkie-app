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

    val navController = rememberNavController()

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Permissions granted, you can show a snackbar or just proceed
        } else {
            // Handle denial (show dialog explaining why)
        }
    }

    fun checkAndRequestPermissions(onGranted: () -> Unit) {
        val hasPermissions = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (hasPermissions) {
            onGranted()
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

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
                    // If in a group, show a single "Radio" item or nothing (since they can't leave without clicking Leave)
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Radio, null) },
                        label = { Text("Radio") },
                        selected = true,
                        onClick = { /* Do nothing, already there */ }
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
                    onCreate = { name ->
                        checkAndRequestPermissions { viewModel.createGroup(name) }
                    }
                )
            }

            composable("join") {
                LaunchedEffect(state.groupName) {
                    if (state.groupName != null) navController.navigate("radio")
                }

                DisposableEffect(Unit) {
                    checkAndRequestPermissions { viewModel.startScanning() }
                    onDispose { viewModel.stopScanning() }
                }

                JoinGroupScreen(
                    discoveredGroups = state.discoveredGroups,
                    onJoin = { group, code ->
                        viewModel.joinGroup(group.name, code)
                    }
                )
            }

            composable("radio") {
                RadioScreen(
                    groupName = state.groupName,
                    accessCode = state.accessCode,
                    onLeave = {
                        viewModel.leaveGroup()
                        navController.navigate("create") {
                            popUpTo("create") { inclusive = true }
                        }
                    }
                )
            }

        }
    }
}