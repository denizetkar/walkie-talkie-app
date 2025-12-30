package com.denizetkar.walkietalkieapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denizetkar.walkietalkieapp.logic.DiscoveredGroup


@Composable
fun CreateGroupScreen(
    onCreate: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Create a New Group", style = MaterialTheme.typography.headlineMedium)
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
                    Text("Verifying Access Code...")
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

        if (discoveredGroups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Scanning...", modifier = Modifier.padding(top = 64.dp))
            }
        } else {
            LazyColumn {
                items(discoveredGroups.size) {
                    val group = discoveredGroups[it]
                    ListItem(
                        headlineContent = { Text(group.name, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text("Signal: ${group.highestRssi} dBm") },
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
    onLeave: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkStop: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

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
                Text("GROUP: ${groupName ?: "Unknown"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("ACCESS CODE: $accessCode", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // PUSH TO TALK BUTTON
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(if (isPressed) Color.Red else MaterialTheme.colorScheme.primary)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null, // Disable ripple, we handle color manually
                    onClick = {}
                )
        ) {
            Text(
                text = if (isPressed) "TALKING" else "HOLD TO TALK",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
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
