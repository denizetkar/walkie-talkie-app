package com.denizetkar.walkietalkieapp

import androidx.compose.foundation.layout.*
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
    onJoin: (String) -> Unit
) {
    // Mock list of local groups
    val mockGroups = listOf("Alpha Squad", "Hiking Team", "Dev Chat")

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Available Groups", style = MaterialTheme.typography.headlineMedium)

        if (currentJoinedGroup != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    text = "Currently joined: $currentJoinedGroup",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        mockGroups.forEach { groupName ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(groupName)
                Button(onClick = { onJoin(groupName) }) {
                    Text(if (currentJoinedGroup == groupName) "Joined" else "Join")
                }
            }
        }
    }
}

@Composable
fun ManageGroupScreen(
    myGroupName: String?
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
            Button(onClick = { /* TODO */ }) {
                Text("Dissolve Group")
            }
        }
    }
}
