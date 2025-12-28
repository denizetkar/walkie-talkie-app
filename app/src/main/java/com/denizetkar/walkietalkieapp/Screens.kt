package com.denizetkar.walkietalkieapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


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
    discoveredNodes: List<DiscoveredNode>,
    onJoin: (DiscoveredNode, String) -> Unit
) {
    var selectedGroup by remember { mutableStateOf<DiscoveredNode?>(null) }
    var codeInput by remember { mutableStateOf("") }

    if (selectedGroup != null) {
        AlertDialog(
            onDismissRequest = { selectedGroup = null },
            title = { Text("Enter Access Code") },
            text = {
                Column {
                    Text("Enter the access code for ${selectedGroup?.groupName}:")
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

        if (discoveredNodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Scanning...", modifier = Modifier.padding(top = 64.dp))
            }
        } else {
            LazyColumn {
                items(discoveredNodes.size) {
                    val group = discoveredNodes[it]
                    ListItem(
                        headlineContent = { Text(group.groupName, fontWeight = FontWeight.Bold) },
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
    onLeave: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

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
