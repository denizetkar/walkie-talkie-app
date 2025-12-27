package com.denizetkar.walkietalkieapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


@Composable
fun CreateGroupScreen(
    existingGroup: String?,
    onCreate: (String) -> Unit,
    onGoToManage: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (existingGroup != null) {
            Text("You already have a group: $existingGroup")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGoToManage) {
                Text("Go to Manage Tab")
            }
        } else {
            Text("Create a New Group", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Group Name") }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { onCreate(text) }, enabled = text.isNotBlank()) {
                Text("Create Group")
            }
        }
    }
}

@Composable
fun JoinGroupScreen(
    currentJoinedGroup: String?,
    discoveredGroups: List<DiscoveredGroup>,
    onJoin: (DiscoveredGroup) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Available Groups", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        if (discoveredGroups.isEmpty()) {
            Text("Scanning...", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn {
                items(discoveredGroups.size) {
                    val group = discoveredGroups[it]

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(group.name, style = MaterialTheme.typography.titleMedium)
                            Text("Signal: ${group.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { onJoin(group) },
                            enabled = currentJoinedGroup != group.name
                        ) {
                            Text(if (currentJoinedGroup == group.name) "Joined" else "Join")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ManageGroupScreen(
    myGroupName: String?,
    onDissolve: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (myGroupName == null) {
            Text("You haven't created a group yet.")
        } else {
            Text("Managing: $myGroupName", style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Pending Requests: 0")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDissolve) {
                Text("Dissolve Group")
            }
        }
    }
}
