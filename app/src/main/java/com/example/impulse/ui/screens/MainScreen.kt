package com.example.impulse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.scale
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
    var selectedServerId by rememberSaveable { mutableStateOf(ServerConfig.defaultServer.id) }
    var clientName by rememberSaveable { mutableStateOf("") }
    var availableServers by remember { mutableStateOf(ServerConfig.builtInServers) }
    // Chats tab: selecting a server opens its conversation list (Group + DMs).
    var chatsServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeChatServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeConversation by rememberSaveable { mutableStateOf("group") }
    var qrScanServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var visibilityRefreshTrigger by remember { mutableIntStateOf(0) }
    var certRefreshTrigger by remember { mutableIntStateOf(0) }

    val selectedServer = availableServers.find { it.id == selectedServerId } ?: ServerConfig.defaultServer
    val chatsServer = availableServers.find { it.id == chatsServerId }
    val activeChatServer = availableServers.find { it.id == activeChatServerId }
    val qrScanServer = availableServers.find { it.id == qrScanServerId }

    val connectionManager = remember { ConnectionManager.getInstance(context) }

    // System back walks the overlay hierarchy: chat → conversation list →
    // QR/server list → normal (Bug: "back exits the app from every overlay").
    BackHandler(enabled = qrScanServerId != null || activeChatServerId != null || chatsServerId != null) {
        when {
            qrScanServerId != null -> qrScanServerId = null
            activeChatServerId != null -> {
                // Return to the conversation list if we came from there.
                activeChatServerId = null
                activeConversation = "group"
            }
            chatsServerId != null -> chatsServerId = null
        }
    }

    LaunchedEffect(Unit) {
        val serverPreferences = ServerPreferences(context)
        val customServers = serverPreferences.getCustomServers()
        val savedServer = serverPreferences.getSelectedServer()
        val savedClientName = serverPreferences.getClientName()

        availableServers = ServerConfig.builtInServers + customServers
        if (savedServer != null && availableServers.any { it.id == savedServer.id }) {
            selectedServerId = savedServer.id
        } else {
            val fallback = availableServers.firstOrNull() ?: ServerConfig.defaultServer
            selectedServerId = fallback.id
            serverPreferences.saveSelectedServer(fallback)
        }
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

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val navItems = listOf(
        Triple(Icons.Default.Home, stringResource(R.string.nav_home), 0),
        Triple(Icons.AutoMirrored.Filled.List, stringResource(R.string.nav_chats), 1),
        Triple(Icons.Default.Settings, stringResource(R.string.nav_settings), 2),
    )

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Main content — extends full screen under floating dock
        val reduceMotion = com.example.impulse.util.isReduceMotionEnabled(context)
        AnimatedContent(
            targetState = selectedItem,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                } else {
                    directionalSlideTransition(targetState > initialState)
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "tab_directional_slide"
        ) { tab ->
            when (tab) {
                0 -> HomeScreen(
                    clientName = clientName,
                    availableServers = availableServers,
                    connectionManager = connectionManager,
                    modifier = Modifier.fillMaxSize()
                )
                1 -> ChatListScreen(
                    connectionManager = connectionManager,
                    availableServers = availableServers,
                    clientName = clientName,
                    onServerSelected = { server ->
                        chatsServerId = server.id
                        selectedServerId = server.id
                    },
                    visibilityRefreshTrigger = visibilityRefreshTrigger,
                    modifier = Modifier.fillMaxSize()
                )
                2 -> SettingsScreen(
                    selectedServer = selectedServer,
                    onServerSelected = { newServer ->
                        selectedServerId = newServer.id
                        ServerPreferences(context).saveSelectedServer(newServer)
                        val customServers = ServerPreferences(context).getCustomServers()
                        availableServers = ServerConfig.builtInServers + customServers
                    },
                    onServerUpdated = { updatedServer ->
                        val customServers = ServerPreferences(context).getCustomServers()
                        availableServers = ServerConfig.builtInServers + customServers
                        connectionManager.updateServerConfig(updatedServer)
                        if (selectedServerId == updatedServer.id) selectedServerId = updatedServer.id
                    },
                    clientName = clientName,
                    onClientNameChange = { newName ->
                        clientName = newName
                        ServerPreferences(context).saveClientName(newName)
                        connectionManager.updateClientName(newName)
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
                        if (selectedServerId == deletedServer.id) {
                            val fallback = availableServers.firstOrNull() ?: ServerConfig.defaultServer
                            selectedServerId = fallback.id
                            ServerPreferences(context).saveSelectedServer(fallback)
                        }
                    },
                    onVisibilityChanged = { visibilityRefreshTrigger++ },
                    certRefreshTrigger = certRefreshTrigger,
                    connectionManager = connectionManager,
                    onScanQr = { server -> qrScanServerId = server.id },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Floating pill dock bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                ),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val navSelectedColor = MaterialTheme.colorScheme.primary
                    val navUnselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    navItems.forEach { (icon, label, index) ->
                        val isSelected = selectedItem == index
                        val animWeight by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isSelected) 1.25f else 1f,
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                            ),
                            label = "nav_item_weight"
                        )
                        val animBgAlpha by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isSelected) 0.14f else 0f,
                            animationSpec = tween(220),
                            label = "nav_bg_alpha"
                        )
                        val animIconScale by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isSelected) 1.08f else 1f,
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                            ),
                            label = "nav_icon_scale"
                        )

                        Box(
                            modifier = Modifier
                                .weight(animWeight)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = animBgAlpha))
                                .clickable {
                                    if (selectedItem != index) {
                                        runCatching {
                                            haptic.performHapticFeedback(
                                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                                            )
                                        }
                                        selectedItem = index
                                    }
                                },
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
                                    modifier = Modifier
                                        .size(22.dp)
                                        .scale(animIconScale),
                                )
                                Spacer(Modifier.height(3.dp))
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isSelected,
                                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                                    exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(16.dp)
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(1.5.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Chat overlay (slides in from right)
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
                    conversationId = activeConversation,
                    onBack = { activeChatServerId = null },
                    modifier = Modifier
                )
            }
        }

        // Conversation list overlay (Group + DMs) for a chosen server.
        AnimatedVisibility(
            visible = chatsServer != null,
            enter = slideInHorizontally(
                animationSpec = tween(300),
                initialOffsetX = { it }
            ) + fadeIn(tween(250)),
            exit = slideOutHorizontally(
                animationSpec = tween(250),
                targetOffsetX = { it }
            ) + fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize().zIndex(2.5f)
        ) {
            chatsServer?.let { server ->
                val ctrl = remember(server.id) { connectionManager.getController(server) }
                val repo = remember(server.id) { com.example.impulse.data.MessageRepository(context) }
                var conversations by remember(server.id) {
                    mutableStateOf<List<String>>(listOf("group"))
                }
                val ctrlPeerNames by ctrl.peerNames.collectAsState()
                var peerNames by remember(server.id) {
                    mutableStateOf<Map<String, String>>(emptyMap())
                }
                val ownFp = remember(server.id) { ctrl.ownFingerprint() }
                LaunchedEffect(server.id) {
                    runCatching {
                        val known = ctrl.knownPeers(server.id)
                        peerNames = known.associate { (fp, name) -> fp to name }
                    }
                }
                // Live conversation list: group + known peers + every conversation
                // that has rows. Reactive to the DB so a DM received while on this
                // screen shows up immediately (Bug: "messages don't arrive when
                // not inside the chat" — the list was loaded exactly once).
                LaunchedEffect(server.id) {
                    repo.observeConversations(server.id).collect { dbConvs ->
                        val known = runCatching { ctrl.knownPeers(server.id) }
                            .getOrDefault(emptyList())
                        conversations = (listOf("group") + known.map { "dm:${it.first}" } + dbConvs).distinct()
                    }
                }
                // Resolve display names once known (from a received message).
                LaunchedEffect(conversations, ownFp, ctrlPeerNames) {
                    val resolved = mutableMapOf<String, String>()
                    for (conv in conversations) {
                        val fp = conv.removePrefix("dm:")
                        if (fp.isBlank() || fp == "group") continue
                        if (fp == ownFp) continue
                        resolved[fp] = ctrl.peerDisplayName(server.id, fp)
                    }
                    if (resolved.isNotEmpty()) peerNames = resolved
                }
                val status = connectionManager.serverStates.value[server.id]
                ChatConversationListScreen(
                    server = server,
                    conversations = conversations,
                    ownFingerprint = ownFp,
                    peerNames = peerNames,
                    state = status?.state,
                    onConversation = { conv ->
                        activeConversation = conv
                        activeChatServerId = server.id
                        // Keep chatsServer so system-back returns to the
                        // conversation list, then to the server list.
                    },
                    onBack = { chatsServerId = null },
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
                        qrScanServerId = null
                        connectionManager.connect(server, clientName)
                    },
                    onBack = { qrScanServerId = null }
                )
            }
        }
    }
}
