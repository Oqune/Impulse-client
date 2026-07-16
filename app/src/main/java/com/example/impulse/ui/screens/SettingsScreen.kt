package com.example.impulse.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.util.LogStorage
import com.example.impulse.websocket.WebSocketManager

enum class SettingsSection {
    MAIN, SERVER, USER, APP
}

@Composable
fun SettingsScreen(
    selectedServer: ServerConfig,
    onServerSelected: (ServerConfig) -> Unit,
    onServerUpdated: (ServerConfig) -> Unit = {},
    modifier: Modifier = Modifier,
    clientName: String,
    onClientNameChange: (String) -> Unit,
    encryptionKey: String,
    onEncryptionKeyChange: (String) -> Unit,
    availableServers: List<ServerConfig> = ServerConfig.builtInServers,
    onServerDeleted: (ServerConfig) -> Unit = {}
) {
    var currentSection by remember { mutableStateOf(SettingsSection.MAIN) }
    var showLogs by remember { mutableStateOf(false) }

    val webSocketManager = WebSocketManager.getInstance()
    val connectionState by webSocketManager.currentState.collectAsState()

    when (currentSection) {
        SettingsSection.MAIN -> {
            SettingsMainScreen(
                modifier = modifier,
                onNavigateToServer = { currentSection = SettingsSection.SERVER },
                onNavigateToUser = { currentSection = SettingsSection.USER },
                onNavigateToApp = { currentSection = SettingsSection.APP },
                onShowLogs = { showLogs = true }
            )
        }
        SettingsSection.SERVER -> {
            ServerSettingsScreen(
                selectedServer = selectedServer,
                onServerSelected = onServerSelected,
                onBack = { currentSection = SettingsSection.MAIN },
                encryptionKey = encryptionKey,
                onEncryptionKeyChange = onEncryptionKeyChange,
                availableServers = availableServers,
                onServerDeleted = onServerDeleted,
                onServerUpdated = onServerUpdated
            )
        }
        SettingsSection.USER -> {
            UserSettingsScreen(
                clientName = clientName,
                onClientNameChange = onClientNameChange,
                onBack = { currentSection = SettingsSection.MAIN },

            )
        }
        SettingsSection.APP -> {
            AppSettingsScreen(
                onBack = { currentSection = SettingsSection.MAIN }
            )
        }
    }

    if (showLogs) {
        LogDialog(
            onDismiss = { showLogs = false },
            onClearLogs = { LogStorage.clearLogs() }
        )
    }
}

@Composable
private fun SettingsMainScreen(
    modifier: Modifier = Modifier,
    onNavigateToServer: () -> Unit,
    onNavigateToUser: () -> Unit,
    onNavigateToApp: () -> Unit,
    onShowLogs: () -> Unit
) {
    DecorativeBackground(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.headlineMedium
            )

            SettingCard(
                title = "Сервер",
                icon = Icons.Default.Build,
                onClick = onNavigateToServer
            )

            SettingCard(
                title = "Пользователь",
                icon = Icons.Default.Person,
                onClick = onNavigateToUser
            )

            SettingCard(
                title = "Приложение",
                icon = Icons.Default.Settings,
                onClick = onNavigateToApp
            )

            OutlinedButton(
                onClick = onShowLogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Логи приложения")
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Перейти",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun LogDialog(
    onDismiss: () -> Unit,
    onClearLogs: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Системные логи")
                IconButton(onClick = onClearLogs) {
                    Icon(Icons.Default.Clear, contentDescription = "Очистить логи")
                }
            }
        },
        text = {
            val logs = LogStorage.getLogs()
            if (logs.isEmpty()) {
                Text("Логи пусты")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    logs.forEach { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
