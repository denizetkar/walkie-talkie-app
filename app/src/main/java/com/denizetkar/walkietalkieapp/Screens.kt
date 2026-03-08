package com.denizetkar.walkietalkieapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denizetkar.walkietalkieapp.domain.DiscoveredGroup

@Composable
fun ServiceErrorScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Radio Service Failed to Start")
        Button(onClick = onRetry) { Text("Retry") }
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
        Text("Permissions Needed", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onGrantClick) { Text("Grant Permissions") }
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
    onCreate: (String) -> Unit,
    error: String?,
    onErrorAck: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    if (error != null) {
        AlertDialog(
            onDismissRequest = onErrorAck,
            title = { Text("Error") },
            text = { Text(error) },
            confirmButton = {
                Button(onClick = onErrorAck) { Text("OK") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Create Group", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Group Name") },
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onCreate(text) },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Go Live")
        }
    }
}

@Composable
fun JoinGroupScreen(
    discoveredGroups: List<DiscoveredGroup>,
    isJoining: Boolean,
    joinError: String?,
    isBluetoothEnabled: Boolean,
    onJoin: (DiscoveredGroup, String) -> Unit,
    onJoinErrorAck: () -> Unit
) {
    var selectedGroup by remember { mutableStateOf<DiscoveredGroup?>(null) }
    var codeInput by remember { mutableStateOf("") }

    if (joinError != null) {
        AlertDialog(
            onDismissRequest = onJoinErrorAck,
            title = { Text("Error") },
            text = { Text(joinError) },
            confirmButton = { Button(onClick = onJoinErrorAck) { Text("OK") } }
        )
    }

    if (isJoining) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Connecting...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Joining...")
                }
            },
            confirmButton = {}
        )
    }

    if (selectedGroup != null) {
        AlertDialog(
            onDismissRequest = { selectedGroup = null },
            title = { Text("Enter Access Code") },
            text = {
                Column {
                    Text("Enter the access code for ${selectedGroup?.name}:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { if (it.length <= 4) codeInput = it },
                        singleLine = true,
                        label = { Text("Code") }
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
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedGroup = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Nearby Groups", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (!isBluetoothEnabled) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BluetoothDisabled, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Bluetooth is disabled")
                }
            }
        } else if (discoveredGroups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Scanning...") }
        } else {
            LazyColumn {
                items(discoveredGroups.size) { i ->
                    val group = discoveredGroups[i]
                    ListItem(
                        headlineContent = { Text(group.name, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text("Signal: ${group.rssi} dBm") },
                        trailingContent = {
                            Button(onClick = { selectedGroup = group }) {
                                Text("Join")
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
    groupName: String?,
    accessCode: String?,
    peerCount: Int,
    availableMics: List<AudioDeviceUi>,
    availableSpeakers: List<AudioDeviceUi>,
    selectedMicId: Int,
    selectedSpeakerId: Int,
    onMicSelect: (Int) -> Unit,
    onSpeakerSelect: (Int) -> Unit,
    onLeave: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkStop: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isNetworkReady = peerCount > 0

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
                    "GROUP: ${groupName ?: "Unknown"}",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "CODE: $accessCode",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        AudioDeviceSelector("Microphone", availableMics, selectedMicId, onMicSelect)
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        AudioDeviceSelector("Speaker", availableSpeakers, selectedSpeakerId, onSpeakerSelect)
                    }
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
                        !isNetworkReady -> "SEARCHING..."
                        isPressed -> "TALKING"
                        else -> "HOLD TO TALK"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )

                if (isNetworkReady) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("$peerCount Peers Online", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onLeave,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Leave Group")
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
    val friendlyName = remember(selectedId, devices) {
        if (selectedId == 0) "Default"
        else devices.find { it.id == selectedId }?.displayName ?: "Unknown"
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
                text = { Text("Default") },
                onClick = { onSelect(0); expanded = false }
            )
            // Other Devices
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.displayName) },
                    onClick = {
                        onSelect(device.id)
                        expanded = false
                    }
                )
            }
        }
    }
}