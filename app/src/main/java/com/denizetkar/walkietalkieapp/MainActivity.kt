package com.denizetkar.walkietalkieapp

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.text.layoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.denizetkar.walkietalkieapp.domain.AppLanguage
import java.util.Locale

class MainActivity : AppCompatActivity() {
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
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val state by viewModel.appState.collectAsState()

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(viewModel)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(viewModel)
        }
    }

    val permissionsToRequest = remember {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.toTypedArray()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val essentialGranted = permissions.entries.all { (perm, granted) ->
            if (perm == Manifest.permission.POST_NOTIFICATIONS) true else granted
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

    val currentLanguage = state.currentLanguage
    val baseConfig = LocalConfiguration.current
    val (localizedContext, localizedConfig) = remember(currentLanguage, baseConfig) {
        val trueLocale = if (currentLanguage == AppLanguage.SYSTEM) {
            Resources.getSystem().configuration.locales.get(0)
        } else {
            Locale.forLanguageTag(currentLanguage.tag)
        }

        val config = Configuration(baseConfig).apply {
            setLocale(trueLocale)
        }

        Pair(context.createConfigurationContext(config), config)
    }
    val layoutDirection = remember(localizedConfig) {
        if (localizedConfig.locales.get(0).layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig,
        LocalLayoutDirection provides layoutDirection
    ) {
        when {
            !state.hasPermissions -> {
                PermissionRequiredScreen(
                    onGrantClick = { permissionLauncher.launch(permissionsToRequest) }
                )
            }
            state.serviceStartupFailed -> {
                ServiceErrorScreen(onRetry = { viewModel.retryConnection() })
            }
            !state.isServiceBound -> { LoadingScreen(stringResource(R.string.starting_audio_engine)) }
            else -> {
                WalkieTalkieNavHost(viewModel, state)
            }
        }
    }
}

@Composable
fun WalkieTalkieNavHost(viewModel: MainViewModel, state: AppUiState) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                if (state.groupName == null) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Add, null) },
                        label = { Text(stringResource(R.string.navigation_create)) },
                        selected = currentRoute == "create",
                        onClick = { navController.navigate("create") { popUpTo("create") { inclusive = true } } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Group, null) },
                        label = { Text(stringResource(R.string.navigation_join)) },
                        selected = currentRoute == "join",
                        onClick = { navController.navigate("join") { popUpTo("join") { inclusive = true } } }
                    )
                } else {
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Radio, null) },
                        label = { Text(stringResource(R.string.navigation_radio)) },
                        selected = true,
                        onClick = { }
                    )
                }

                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text(stringResource(R.string.settings_title)) },
                    selected = currentRoute == "settings",
                    onClick = { navController.navigate("settings") { popUpTo("settings") { inclusive = true } } }
                )
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
                    onCreate = { name, code -> viewModel.createGroup(name, code) },
                    error = state.joinError,
                    onErrorAck = { viewModel.ackJoinError() }
                )
            }

            composable("join") {
                LaunchedEffect(state.groupName, state.isJoining) {
                    if (state.groupName != null && !state.isJoining) navController.navigate("radio")
                }

                DisposableEffect(Unit) {
                    viewModel.startScanning()
                    onDispose { viewModel.stopScanning() }
                }

                JoinGroupScreen(
                    discoveredGroups = state.discoveredGroups,
                    isJoining = state.isJoining,
                    joinError = state.joinError,
                    isBluetoothEnabled = state.isBluetoothEnabled,
                    onJoin = { group, code -> viewModel.joinGroup(group.groupId, group.name, code) },
                    onJoinErrorAck = { viewModel.ackJoinError() }
                )
            }

            composable("radio") {
                LaunchedEffect(state.groupName) {
                    if (state.groupName == null) {
                        navController.navigate("create") { popUpTo("create") { inclusive = true } }
                    }
                }

                RadioScreen(
                    groupName = state.groupName,
                    accessCode = state.accessCode,
                    peerCount = state.peerCount,
                    isBluetoothEnabled = state.isBluetoothEnabled,
                    availableAudioDevices = state.availableAudioDevices,
                    selectedAudioDevice = state.selectedAudioDevice,
                    onDeviceSelect = { viewModel.setAudioDevice(it) },
                    onLeave = { viewModel.leaveGroup() },
                    onTalkStart = { viewModel.startTalking() },
                    onTalkStop = { viewModel.stopTalking() },
                )
            }

            composable("settings") {
                SettingsScreen(
                    currentLanguage = state.currentLanguage,
                    onLanguageSelected = { lang -> viewModel.setLanguage(lang) },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}