package com.example.impulse.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.util.NameGenerator
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class TabItem(val title: String, val icon: ImageVector)

@Composable
fun MainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedItem by remember { mutableIntStateOf(0) }
    var selectedServer by remember { mutableStateOf(ServerConfig.defaultServer) }
    var clientName by remember { mutableStateOf("") }
    var encryptionKey by remember { mutableStateOf("") }
    var availableServers by remember { mutableStateOf(ServerConfig.builtInServers) }

    val items = listOf(
        TabItem("Главная", Icons.Default.Home),
        TabItem("Чат", Icons.Default.Email),
        TabItem("Настройки", Icons.Default.Settings)
    )

    val webSocketManager = WebSocketManager.getInstance()
    val connectionState by webSocketManager.currentState.collectAsState()

    // Initialize preferences and load saved data
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val serverPreferences = ServerPreferences(context)
        val customServers = serverPreferences.getCustomServers()
        val savedServer = serverPreferences.getSelectedServer()
        val savedClientName = serverPreferences.getClientName()
        val autoConnect = serverPreferences.getAutoConnect()

        availableServers = ServerConfig.builtInServers + customServers

        if (savedServer != null) {
            selectedServer = savedServer
        }

        if (savedClientName.isNotBlank()) {
            clientName = savedClientName
        } else {
            clientName = NameGenerator.generate()
        }

        // Load the encryption key bound to the (selected) server.
        encryptionKey = serverPreferences.getEncryptionKey(selectedServer.id)

        if (autoConnect && savedServer != null && connectionState == WebSocketState.DISCONNECTED) {
            CoroutineScope(Dispatchers.IO).launch {
                webSocketManager.connect(
                    savedServer.getWebSocketUrl(),
                    savedServer.password,
                    clientName,
                    encryptionKey,
                    savedServer.id
                )
            }
        }
    }

    // Reconnect when selected server changes (or its data is edited)
    androidx.compose.runtime.LaunchedEffect(selectedServer) {
        val serverPreferences = ServerPreferences(context)
        val autoConnect = serverPreferences.getAutoConnect()

        // Load the encryption key bound to THIS server (per-server, optional).
        encryptionKey = serverPreferences.getEncryptionKey(selectedServer.id)

        // Disconnect from current server if connected
        if (connectionState != WebSocketState.DISCONNECTED && connectionState != WebSocketState.ERROR) {
            webSocketManager.disconnect()
        }

        // Save the new server selection
        serverPreferences.saveSelectedServer(selectedServer)

        // Auto-connect to new server if enabled
        // Note: we always try to connect after disconnect since disconnect() now
        // immediately updates the state to DISCONNECTED
        if (autoConnect) {
            CoroutineScope(Dispatchers.IO).launch {
                webSocketManager.connect(
                    selectedServer.getWebSocketUrl(),
                    selectedServer.password,
                    clientName,
                    encryptionKey,
                    selectedServer.id
                )
            }
        }
    }

    // Remove the now-unused local generator to avoid dead code.

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                tonalElevation = 0.1.dp
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = {
                            Text(
                                text = item.title,
                                textAlign = TextAlign.Center,
                                fontSize = androidx.compose.material3.MaterialTheme.typography.labelMedium.fontSize
                            )
                        },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedItem) {
            0 -> HomeScreen(
                clientName = clientName,
                selectedServer = selectedServer,
                encryptionKey = encryptionKey,
                modifier = Modifier.padding(innerPadding)
            )
            1 -> ChatScreen(
                selectedServer = selectedServer,
                clientName = clientName,
                modifier = Modifier.padding(innerPadding)
            )
            2 -> SettingsScreen(
                selectedServer = selectedServer,
                onServerSelected = { newServer ->
                    selectedServer = newServer
                    val serverPreferences = ServerPreferences(context)
                    serverPreferences.saveSelectedServer(newServer)
                    val customServers = serverPreferences.getCustomServers()
                    availableServers = ServerConfig.builtInServers + customServers
                },
                onServerUpdated = { updatedServer ->
                    val serverPreferences = ServerPreferences(context)
                    val customServers = serverPreferences.getCustomServers()
                    availableServers = ServerConfig.builtInServers + customServers
                    if (selectedServer.id == updatedServer.id) {
                        selectedServer = updatedServer
                    }
                },
                clientName = clientName,
                onClientNameChange = { newName ->
                    clientName = newName
                    val serverPreferences = ServerPreferences(context)
                    serverPreferences.saveClientName(newName)
                },
                encryptionKey = encryptionKey,
                onEncryptionKeyChange = { newKey ->
                    encryptionKey = newKey
                    // Persist the key bound to the currently selected server
                    // (per-server, optional). Empty string = no encryption.
                    ServerPreferences(context).saveEncryptionKey(selectedServer.id, newKey)
                },
                availableServers = availableServers,
                onServerDeleted = { deletedServer ->
                    val serverPreferences = ServerPreferences(context)
                    val customServers = serverPreferences.getCustomServers()
                    availableServers = ServerConfig.builtInServers + customServers

                    if (selectedServer == deletedServer) {
                        selectedServer = availableServers.firstOrNull() ?: ServerConfig.defaultServer
                        serverPreferences.saveSelectedServer(selectedServer)
                    }
                },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
