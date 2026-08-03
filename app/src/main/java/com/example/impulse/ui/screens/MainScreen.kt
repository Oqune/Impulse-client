package com.example.impulse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.impulse.ConnectionManager
import com.example.impulse.R
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.ui.theme.*
import com.example.impulse.util.NameGenerator

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var selectedItem by rememberSaveable { mutableIntStateOf(0) }
    var selectedServer by remember { mutableStateOf(ServerConfig.defaultServer) }
    var clientName by remember { mutableStateOf("") }
    var availableServers by remember { mutableStateOf(ServerConfig.builtInServers) }
    var activeChatServer by remember { mutableStateOf<ServerConfig?>(null) }
    var qrScanServer by remember { mutableStateOf<ServerConfig?>(null) }
    var visibilityRefreshTrigger by remember { mutableIntStateOf(0) }
    var certRefreshTrigger by remember { mutableIntStateOf(0) }

    val connectionManager = remember { ConnectionManager.getInstance(context) }

    // System back closes overlays first, then behaves normally (Bug: "back
    // button exits the app from the chat/QR overlay").
    BackHandler(enabled = qrScanServer != null || activeChatServer != null) {
        when {
            qrScanServer != null -> qrScanServer = null
            activeChatServer != null -> activeChatServer = null
        }
    }

    LaunchedEffect(Unit) {
        val serverPreferences = ServerPreferences(context)
        val customServers = serverPreferences.getCustomServers()
        val savedServer = serverPreferences.getSelectedServer()
        val savedClientName = serverPreferences.getClientName()

        availableServers = ServerConfig.builtInServers + customServers
        if (savedServer != null) selectedServer = savedServer
        clientName = savedClientName.ifBlank { NameGenerator.generate() }

        for (server in availableServers) {
            val ar = serverPreferences.getServerAutoReconnect(server.id)
            if (ar) {
                val ctrl = connectionManager.getController(server)
                ctrl.setAutoReconnect(true)
            }
        }

        for (server in availableServers) {
            if (serverPreferences.getServerAutoConnect(server.id) && clientName.isNotBlank()) {
                connectionManager.connect(server, clientName)
            }
        }
    }

    val navItems = listOf(
        Triple(Icons.Default.Home, stringResource(R.string.nav_home), 0),
        Triple(Icons.AutoMirrored.Filled.List, stringResource(R.string.nav_chats), 1),
        Triple(Icons.Default.Settings, stringResource(R.string.nav_settings), 2),
    )

    val navSelectedColor = MaterialTheme.colorScheme.primary
    val navUnselectedColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Main content
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                        )
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .zIndex(1f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        navItems.forEachIndexed { index, (icon, label, _) ->
                            val isSelected = selectedItem == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else Color.Transparent
                                    )
                                    .clickable { selectedItem = index },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = label,
                                        tint = if (isSelected) navSelectedColor else navUnselectedColor,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    if (isSelected) {
                                        Spacer(Modifier.height(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Crossfade(
                targetState = selectedItem,
                animationSpec = tween(durationMillis = 250),
                label = "tab_crossfade"
            ) { tab ->
                when (tab) {
                    0 -> HomeScreen(
                        clientName = clientName,
                        availableServers = availableServers,
                        connectionManager = connectionManager,
                        modifier = Modifier.padding(innerPadding)
                    )
                    1 -> ChatListScreen(
                        connectionManager = connectionManager,
                        availableServers = availableServers,
                        clientName = clientName,
                        onServerSelected = { server ->
                            activeChatServer = server
                            selectedServer = server
                        },
                        visibilityRefreshTrigger = visibilityRefreshTrigger,
                        modifier = Modifier.padding(innerPadding)
                    )
                    2 -> SettingsScreen(
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
                        onServerAdded = { newServer ->
                            ServerPreferences(context).addCustomServer(newServer)
                            val customServers = ServerPreferences(context).getCustomServers()
                            availableServers = ServerConfig.builtInServers + customServers
                        },
                        onServerDeleted = { deletedServer ->
                            val customServers = ServerPreferences(context).getCustomServers()
                            availableServers = ServerConfig.builtInServers + customServers
                            if (selectedServer == deletedServer) {
                                selectedServer = availableServers.firstOrNull() ?: ServerConfig.defaultServer
                                ServerPreferences(context).saveSelectedServer(selectedServer)
                            }
                        },
                        onVisibilityChanged = { visibilityRefreshTrigger++ },
                        certRefreshTrigger = certRefreshTrigger,
                        connectionManager = connectionManager,
                        onScanQr = { server -> qrScanServer = server },
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
                    )
                }
            }
        }

        // Chat screen overlay (slides in from right)
        AnimatedVisibility(
            visible = activeChatServer != null,
            enter = slideInHorizontally(
                animationSpec = tween(300),
                initialOffsetX = { it }
            ) + fadeIn(tween(250)),
            exit = slideOutHorizontally(
                animationSpec = tween(250),
                targetOffsetX = { it }
            ) + fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize().zIndex(3f)
        ) {
            activeChatServer?.let { server ->
                ChatScreen(
                    selectedServer = server,
                    clientName = clientName,
                    connectionManager = connectionManager,
                    onBack = { activeChatServer = null },
                    modifier = Modifier
                )
            }
        }

        // QR scan overlay (slides in from bottom)
        AnimatedVisibility(
            visible = qrScanServer != null,
            enter = slideInVertically(
                animationSpec = tween(300),
                initialOffsetY = { it }
            ) + fadeIn(tween(250)),
            exit = slideOutVertically(
                animationSpec = tween(250),
                targetOffsetY = { it }
            ) + fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize().zIndex(4f)
        ) {
            qrScanServer?.let { server ->
                val certManager = remember { com.example.impulse.security.TrustedCertManager(context) }
                QrScanScreen(
                    serverId = server.id,
                    onCertScanned = { hash ->
                        certManager.trustHash(server.id, hash)
                        certRefreshTrigger++
                        qrScanServer = null
                        connectionManager.connect(server, clientName)
                    },
                    onBack = { qrScanServer = null }
                )
            }
        }
    }
}
