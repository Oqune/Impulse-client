package com.example.impulse.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.websocket.WebSocketManager

// Глобальное хранилище логов
object LogStorage {
    private val logs = mutableStateListOf<String>()

    fun addLog(message: String) {
        // Добавляем временную метку
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logs.add("[$timestamp] $message")
    }

    fun getLogs(): List<String> = logs.toList()
    fun clearLogs() = logs.clear()
}

enum class SettingsSection {
    MAIN, SERVER, USER, APP
}

@Composable
fun SettingsScreen(
    selectedServer: ServerConfig,
    onServerSelected: (ServerConfig) -> Unit,
    modifier: Modifier = Modifier,
    clientName: String,
    onClientNameChange: (String) -> Unit,
    encryptionKey: String,
    onEncryptionKeyChange: (String) -> Unit
) {
    var currentSection by remember { mutableStateOf(SettingsSection.MAIN) }
    var showLogs by remember { mutableStateOf(false) }

    // Получаем состояние WebSocket
    val webSocketManager = WebSocketManager.getInstance()
    val connectionState by webSocketManager.currentState.collectAsState()

    when (currentSection) {
        SettingsSection.MAIN -> {
            SettingsMainScreen(
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

    // Диалог для просмотра логов
    if (showLogs) {
        LogDialog(
            onDismiss = { showLogs = false },
            onClearLogs = { LogStorage.clearLogs() }
        )
    }
}

@Composable
private fun SettingsMainScreen(
    onNavigateToServer: () -> Unit,
    onNavigateToUser: () -> Unit,
    onNavigateToApp: () -> Unit,
    onShowLogs: () -> Unit
) {
    Box(
        modifier = Modifier
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
            // Заголовок
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.headlineMedium
            )

            // Карточки настроек
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

            // Кнопка для просмотра логов
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
        elevation = CardDefaults.cardElevation(defaultElevation = 7.dp), // Более выраженная тень
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp) // Более закругленные углы
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(28.dp)
                    .padding(end = 16.dp)
            )

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.Default.ArrowForward,
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