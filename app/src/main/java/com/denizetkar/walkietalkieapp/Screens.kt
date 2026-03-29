package com.denizetkar.walkietalkieapp

import android.bluetooth.le.ScanCallback
import android.media.AudioDeviceInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.denizetkar.walkietalkieapp.domain.AppError
import com.denizetkar.walkietalkieapp.domain.AppLanguage
import com.denizetkar.walkietalkieapp.domain.AudioDeviceUi
import com.denizetkar.walkietalkieapp.domain.DiscoveredGroup

// --- Resolvers (Recomposed cleanly on language change) ---

@Composable
fun AppError.resolveMessage(): String {
    return when (this) {
        is AppError.ConnectionTimeout -> stringResource(R.string.error_connection_timeout)
        is AppError.AccessCodeRejected -> stringResource(R.string.error_access_code_rejected)
        is AppError.BluetoothRadioUnavailable -> stringResource(R.string.error_bt_radio)
        is AppError.BluetoothScannerUnavailable -> stringResource(R.string.error_bt_scanner)
        is AppError.BluetoothScannerFailed -> if (this.errorCode == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
            stringResource(R.string.error_scan_too_frequent)
        } else {
            stringResource(R.string.error_bt_scanner_failed, this.errorCode)
        }
        is AppError.Unknown -> this.message
    }
}

@Composable
fun AudioDeviceUi.toFriendlyName(): String {
    val name = address.ifBlank { productName }
    val friendly = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> stringResource(R.string.audio_device_earpiece)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> stringResource(R.string.audio_device_speaker)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> stringResource(R.string.audio_device_wired_headset)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> stringResource(R.string.audio_device_wired_headphones)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> stringResource(R.string.audio_device_bt_sco, name)
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> stringResource(R.string.audio_device_bt_a2dp, name)
        AudioDeviceInfo.TYPE_USB_DEVICE -> stringResource(R.string.audio_device_usb_device, name)
        AudioDeviceInfo.TYPE_USB_HEADSET -> stringResource(R.string.audio_device_usb_headset, name)
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> stringResource(R.string.audio_device_mic_generic)
        else -> name
    }
    return friendly.ifBlank { stringResource(R.string.audio_device_unknown) }
}

// --- Screens ---

@Composable
fun SettingsScreen(
    currentLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text(
                text = stringResource(R.string.language),
                style = MaterialTheme.typography.titleMedium
            )

            AppLanguage.entries.forEach { lang ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLanguageSelected(lang) }
                        .padding(vertical = 12.dp)
                ) {
                    RadioButton(
                        selected = currentLanguage == lang,
                        onClick = { onLanguageSelected(lang) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = lang.displayNameRes))
                }
            }
        }
    }
}

@Composable
fun ServiceErrorScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.service_error_screen_title))
        Button(onClick = onRetry) { Text(stringResource(R.string.service_error_screen_button)) }
    }
}

@Composable
fun PermissionRequiredScreen(onGrantClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Bluetooth,
            contentDescription = null,
            Modifier.size(64.dp),
            MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.permission_required_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onGrantClick) { Text(stringResource(R.string.permission_required_button)) }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(message)
    }
}

@Composable
fun CreateGroupScreen(
    onCreate: (String, String) -> Unit,
    error: AppError?,
    onErrorAck: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }

    val displayError = error?.resolveMessage()
    if (displayError != null) {
        AlertDialog(
            onDismissRequest = onErrorAck,
            title = { Text(stringResource(R.string.create_group_alert_title)) },
            text = { Text(displayError) },
            confirmButton = {
                Button(onClick = onErrorAck) { Text(stringResource(R.string.create_group_alert_button)) }
            }
        )
    }

    val currentBytes = text.toByteArray(Charsets.UTF_8).size
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.create_group_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                if (newText.toByteArray(Charsets.UTF_8).size <= Config.MAX_ADVERTISING_NAME_BYTES) {
                    text = newText
                }
            },
            label = { Text(stringResource(R.string.create_group_group_name)) },
            singleLine = true,
            supportingText = {
                Text(
                    text = stringResource(R.string.create_group_bytes_used, currentBytes, Config.MAX_ADVERTISING_NAME_BYTES),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = codeInput,
            onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) codeInput = it },
            label = { Text(stringResource(R.string.create_group_code_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onCreate(text, codeInput) },
            enabled = text.isNotBlank() && (codeInput.isEmpty() || codeInput.length == 4),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.create_group_go_live))
        }
    }
}

@Composable
fun JoinGroupScreen(
    discoveredGroups: List<DiscoveredGroup>,
    isJoining: Boolean,
    joinError: AppError?,
    isBluetoothEnabled: Boolean,
    onJoin: (DiscoveredGroup, String) -> Unit,
    onJoinErrorAck: () -> Unit
) {
    var selectedGroup by remember { mutableStateOf<DiscoveredGroup?>(null) }
    var codeInput by remember { mutableStateOf("") }

    val displayJoinError = joinError?.resolveMessage()
    if (displayJoinError != null) {
        AlertDialog(
            onDismissRequest = onJoinErrorAck,
            title = { Text(stringResource(R.string.join_group_alert_title)) },
            text = { Text(displayJoinError) },
            confirmButton = { Button(onClick = onJoinErrorAck) { Text(stringResource(R.string.join_group_alert_button)) } }
        )
    }

    if (isJoining) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AlertDialogDefaults.TonalElevation
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.join_group_connecting), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (selectedGroup != null) {
        val dialogName = selectedGroup!!.name.ifBlank { stringResource(R.string.radio_unknown_group) }
        AlertDialog(
            onDismissRequest = { selectedGroup = null },
            title = { Text(stringResource(R.string.join_group_enter_code_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.join_group_enter_code_body, dialogName))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) codeInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.join_group_enter_code_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onJoin(selectedGroup!!, codeInput)
                        selectedGroup = null
                        codeInput = ""
                    },
                    enabled = codeInput.length == 4
                ) {
                    Text(stringResource(R.string.join_group_enter_code_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedGroup = null }) {
                    Text(stringResource(R.string.join_group_enter_code_cancel))
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.join_group_nearby_groups_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (!isBluetoothEnabled) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BluetoothDisabled, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.join_group_nearby_groups_no_bluetooth))
                }
            }
        } else if (discoveredGroups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.join_group_nearby_groups_scanning)) }
        } else {
            LazyColumn {
                items(discoveredGroups.size) { i ->
                    val group = discoveredGroups[i]
                    val displayName = group.name.ifBlank { stringResource(R.string.radio_unknown_group) }
                    ListItem(
                        headlineContent = { Text(displayName, fontWeight = FontWeight.Bold) },
                        supportingContent = {
                            Column {
                                val hexId = group.groupId.toString(16).padStart(8, '0').uppercase()
                                Text(
                                    text = stringResource(R.string.join_group_nearby_groups_id, hexId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(stringResource(R.string.join_group_nearby_groups_signal_level, group.rssi))
                            }
                        },
                        trailingContent = {
                            Button(onClick = { selectedGroup = group }) {
                                Text(stringResource(R.string.join_group_nearby_groups_join))
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun RadioScreen(
    groupId: UInt?,
    groupName: String?,
    accessCode: String?,
    peerCount: Int,
    isBluetoothEnabled: Boolean,
    availableAudioDevices: List<AudioDeviceUi>,
    selectedAudioDevice: Int,
    onDeviceSelect: (Int) -> Unit,
    onLeave: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkStop: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isNetworkReady = peerCount > 0 && isBluetoothEnabled

    LaunchedEffect(isPressed) {
        if (isPressed) onTalkStart() else onTalkStop()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.radio_title, groupName ?: stringResource(R.string.radio_unknown_group)),
                    style = MaterialTheme.typography.titleLarge
                )
                if (groupId != null) {
                    val hexId = groupId.toString(16).padStart(8, '0').uppercase()
                    Text(
                        stringResource(R.string.radio_group_id, hexId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.radio_access_code, accessCode ?: ""),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    AudioDeviceSelector(
                        label = stringResource(R.string.radio_audio_route),
                        devices = availableAudioDevices,
                        selectedId = selectedAudioDevice,
                        onSelect = onDeviceSelect
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !isBluetoothEnabled -> Color.DarkGray
                        !isNetworkReady -> Color.Gray
                        isPressed -> Color.Red
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = isNetworkReady,
                    onClick = {}
                )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = when {
                        !isBluetoothEnabled -> stringResource(R.string.radio_ptt_ble_off)
                        !isNetworkReady -> stringResource(R.string.radio_ptt_ble_searching)
                        isPressed -> stringResource(R.string.radio_ptt_ble_talking)
                        else -> stringResource(R.string.radio_ptt_ble_hold_to_talk)
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )

                if (isNetworkReady) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(pluralStringResource(R.plurals.radio_ptt_ble_peers, count = peerCount, peerCount), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onLeave,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text(stringResource(R.string.radio_leave_group))
        }
    }
}

@Composable
fun AudioDeviceSelector(
    label: String,
    devices: List<AudioDeviceUi>,
    selectedId: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val friendlyName = if (selectedId == 0) {
        stringResource(R.string.audio_device_selector_default)
    } else {
        devices.find { it.id == selectedId }?.toFriendlyName() ?: stringResource(R.string.audio_device_selector_unknown)
    }

    Box {
        OutlinedTextField(
            value = friendlyName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 12.sp) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )

        // Transparent overlay to make the entire text field area clickable
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = true }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Option 0: Default
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_device_selector_default)) },
                onClick = { onSelect(0); expanded = false }
            )
            // Other Devices
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.toFriendlyName()) },
                    onClick = {
                        onSelect(device.id)
                        expanded = false
                    }
                )
            }
        }
    }
}