package com.example.impulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.impulse.ChatController
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.security.TrustedCertManager
import com.example.impulse.transport.ConnectionState
import com.example.impulse.util.NameGenerator
import androidx.compose.ui.platform.LocalContext

/** A navigation tab in the bottom bar: [title] label and [icon] for the tab button. */
data class TabItem(val title: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var selectedItem by remember { mutableIntStateOf(0) }
    var selectedServer by remember { mutableStateOf(ServerConfig.defaultServer) }
    var clientName by remember { mutableStateOf("") }
    var availableServers by remember { mutableStateOf(ServerConfig.builtInServers) }
    var showQrScan by remember { mutableStateOf(false) }

    val items = listOf(
        TabItem("Главная", Icons.Default.Home),
        TabItem("Чат", Icons.Default.Email),
        TabItem("QR", Icons.Default.QrCode),
        TabItem("Настройки", Icons.Default.Settings)
    )

    val chatController = remember { ChatController.getInstance(context) }
    val connectionState by chatController.state.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val serverPreferences = ServerPreferences(context)
        val customServers = serverPreferences.getCustomServers()
        val savedServer = serverPreferences.getSelectedServer()
        val savedClientName = serverPreferences.getClientName()

        availableServers = ServerConfig.builtInServers + customServers
        if (savedServer != null) selectedServer = savedServer
        clientName = savedClientName.ifBlank { NameGenerator.generate() }

        chatController.setAutoReconnect(serverPreferences.getAutoReconnect())

        if (serverPreferences.getAutoConnect() && savedServer != null && clientName.isNotBlank()) {
            chatController.connect(savedServer, clientName)
        }
    }

    if (showQrScan) {
        val currentServer = selectedServer
        QrScanScreen(
            serverId = currentServer.id,
            onCertScanned = { hash ->
                val certManager = TrustedCertManager(context)
                certManager.trustHash(currentServer.id, hash)
                com.example.impulse.util.LogManager.i("MainScreen", "QR scan stored cert for server=${currentServer.id}")
                showQrScan = false
                chatController.connect(currentServer, clientName)
            },
            onBack = { showQrScan = false }
        )
        return
    }

    val showTopBar = selectedItem != 3

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = { Text("Impulse") },
                    actions = {
                        val (label, color) = when (connectionState) {
                            ConnectionState.DISCONNECTED -> "Отключено" to MaterialTheme.colorScheme.outline
                            ConnectionState.CONNECTING -> "Подключение…" to MaterialTheme.colorScheme.tertiary
                            ConnectionState.CONNECTED -> "Аутентификация…" to MaterialTheme.colorScheme.tertiary
                            ConnectionState.AUTHENTICATING -> "Аутентификация…" to MaterialTheme.colorScheme.tertiary
                            ConnectionState.AUTHENTICATED -> "Канал…" to MaterialTheme.colorScheme.tertiary
                            ConnectionState.READY -> "PQ-E2EE" to MaterialTheme.colorScheme.primary
                            ConnectionState.ERROR -> "Ошибка" to MaterialTheme.colorScheme.error
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(color, CircleShape)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                        }
                    }
                )
            }
        },
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
                modifier = Modifier.padding(innerPadding)
            )
            1 -> ChatScreen(
                selectedServer = selectedServer,
                clientName = clientName,
                modifier = Modifier.padding(innerPadding)
            )
2 -> {
                val currentServer = selectedServer
                QrScanScreen(
                    serverId = currentServer.id,
                    onCertScanned = { hash ->
                        val certManager = TrustedCertManager(context)
                        certManager.trustHash(currentServer.id, hash)
                        chatController.connect(currentServer, clientName)
                        selectedItem = 0
                    },
                    onBack = { selectedItem = 0 }
                )
            }
            3 -> SettingsScreen(
                selectedServer = selectedServer,
                onServerSelected = { newServer ->
                    selectedServer = newServer
                    ServerPreferences(context).saveSelectedServer(newServer)
                    val customServers = ServerPreferences(context).getCustomServers()
                    availableServers = ServerConfig.builtInServers + customServers
                },
                onServerUpdated = { updatedServer ->
                    val customServers = ServerPreferences(context).getCustomServers()
                    availableServers = ServerConfig.builtInServers + customServers
                    if (selectedServer.id == updatedServer.id) selectedServer = updatedServer
                },
                clientName = clientName,
                onClientNameChange = { newName ->
                    clientName = newName
                    ServerPreferences(context).saveClientName(newName)
                },
                availableServers = availableServers,
                onServerDeleted = { deletedServer ->
                    val customServers = ServerPreferences(context).getCustomServers()
                    availableServers = ServerConfig.builtInServers + customServers
                    if (selectedServer == deletedServer) {
                        selectedServer = availableServers.firstOrNull() ?: ServerConfig.defaultServer
                        ServerPreferences(context).saveSelectedServer(selectedServer)
                    }
                },
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
            )
        }
    }
}
