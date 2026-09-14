package com.example.impulse.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.impulse.ConnectionManager
import com.example.impulse.R
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.transport.ConnectionState
import com.example.impulse.ui.theme.*

@Composable
fun ChatListScreen(
    connectionManager: ConnectionManager,
    availableServers: List<ServerConfig>,
    clientName: String,
    onServerSelected: (ServerConfig) -> Unit,
    visibilityRefreshTrigger: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val serverPreferences = remember { ServerPreferences(context) }
    val serverStates by connectionManager.serverStates.collectAsState()
    val pubKeyHash by remember { mutableStateOf(connectionManager.getPublicKeyHash()) }

    @Suppress("UNUSED_EXPRESSION")
    visibilityRefreshTrigger
    val hiddenServers = serverPreferences.getHiddenServers()
    val visibleServers = availableServers.filter { it.id !in hiddenServers }

    DecorativeBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_list_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp)
            )

            Spacer(Modifier.height(16.dp))

            if (pubKeyHash.isNotEmpty()) {
                Text(
                    text = pubKeyHash,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (visibleServers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chat_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleServers, key = { it.id }) { server ->
                        val status = serverStates[server.id]
                        val isConnected = status?.state == ConnectionState.READY
                        val isConnecting = status?.state in listOf(
                            ConnectionState.CONNECTING,
                            ConnectionState.CONNECTED,
                            ConnectionState.AUTHENTICATING,
                            ConnectionState.AUTHENTICATED
                        )
                        val hasError = status?.state == ConnectionState.ERROR

                        Box(modifier = Modifier.animateItem()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        runCatching {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        }
                                        onServerSelected(server)
                                    },
                                shape = CardShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isConnected)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                                    else
                                        MaterialTheme.colorScheme.surfaceContainer
                                ),
                                border = if (isConnected) {
                                    androidx.compose.foundation.BorderStroke(
                                        0.5.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                    )
                                } else null,
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatusDot(
                                        color = when {
                                            isConnected -> MaterialTheme.colorScheme.primary
                                            isConnecting -> MaterialTheme.colorScheme.tertiary
                                            hasError -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.outline
                                        },
                                        size = 10.dp,
                                    )

                                    Spacer(Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = server.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = server.ipAddress,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    if (isConnected) {
                                        ShieldBadge(
                                            text = "ONLINE",
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }

                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(R.string.chat_list_open_chat),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
