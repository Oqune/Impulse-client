package com.example.impulse.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.isValidIpAddress
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState
import com.example.impulse.ui.components.WebSocketComponents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedServer: ServerConfig,
    onServerSelected: (ServerConfig) -> Unit,
    modifier: Modifier = Modifier,
    clientName: String,
    onClientNameChange: (String) -> Unit // Добавляем обработчик изменения имени
) {
    var showCustomServerDialog by remember { mutableStateOf(false) }
    var customIpAddress by remember { mutableStateOf("") }
    var customPort by remember { mutableStateOf("8080") }
    var customPassword by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf("") }
    var portError by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    // Используем singleton WebSocketManager
    val webSocketManager = WebSocketManager.getInstance()
    val connectionState by webSocketManager.currentState.collectAsState()

    // Отслеживаем изменение выбранного сервера
    var previousServer by remember { mutableStateOf(selectedServer) }
    LaunchedEffect(selectedServer) {
        // Если сервер изменился, отключаемся от старого
        if (previousServer != selectedServer) {
            if (connectionState == WebSocketState.CONNECTED || connectionState == WebSocketState.AUTHENTICATED) {
                webSocketManager.disconnect()
                LogStorage.addLog("Отключение от предыдущего сервера при смене настройки")
            }
            previousServer = selectedServer
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Настройки сервера",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Добавляем статус подключения и кнопки управления
        WebSocketComponents.ConnectionStatus(connectionState, getConnectionStatusMessage(connectionState))

        Spacer(modifier = Modifier.height(16.dp))

        WebSocketComponents.ConnectionControls(
            connectionState = connectionState,
            onConnect = {
                CoroutineScope(Dispatchers.IO).launch {
                    LogStorage.addLog("Попытка подключения к ${selectedServer.getWebSocketUrl()}")
                    webSocketManager.connect(selectedServer.getWebSocketUrl(), selectedServer.password, clientName)
                }
            },
            onDisconnect = {
                LogStorage.addLog("Запрос на отключение")
                webSocketManager.disconnect()
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Кнопка для просмотра логов
        Button(
            onClick = { showLogs = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Info, contentDescription = "Логи")
            Spacer(Modifier.width(8.dp))
            Text("Просмотр логов")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Выберите сервер",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ServerConfig.availableServers.forEach { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (server == selectedServer),
                            onClick = { onServerSelected(server) },
                            role = Role.RadioButton
                        )
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (server == selectedServer),
                        onClick = null
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = "IP: ${server.ipAddress}:${server.port}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = "WS: ${server.getWebSocketUrl()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = server.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (server.password.isNotEmpty()) {
                            Text(
                                text = "Пароль установлен",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                customIpAddress = ""
                customPort = "8080"
                customPassword = ""
                ipError = ""
                portError = ""
                showCustomServerDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Добавить кастомный сервер")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Текущий сервер:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = selectedServer.name,
                    style = MaterialTheme.typography.bodyLarge
                )

                Text(
                    text = "IP: ${selectedServer.ipAddress}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "Порт: ${selectedServer.port}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "WebSocket: ${selectedServer.getWebSocketUrl()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (selectedServer.password.isNotEmpty()) {
                    Text(
                        text = "Пароль: ********",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Отображение текущего имени клиента
                Text(
                    text = "Имя клиента: $clientName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                // Кнопка для изменения имени
                Button(
                    onClick = { /* Здесь можно добавить диалог для изменения имени */ },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false // Временно отключено, так как имя изменяется в HomeScreen
                ) {
                    Text("Изменить имя")
                }
            }
        }
    }

    if (showCustomServerDialog) {
        CustomServerDialog(
            showCustomServerDialog = showCustomServerDialog,
            customIpAddress = customIpAddress,
            customPort = customPort,
            customPassword = customPassword,
            ipError = ipError,
            portError = portError,
            onIpAddressChange = { newIp ->
                customIpAddress = newIp
                ipError = if (newIp.isNotBlank() && !isValidIpAddress(newIp)) "Неверный формат IP-адреса" else ""
            },
            onPortChange = { newPort ->
                customPort = newPort
                portError = try {
                    if (newPort.isNotBlank()) {
                        val port = newPort.toInt()
                        if (port < 1 || port > 65535) "Порт должен быть от 1 до 65535" else ""
                    } else ""
                } catch (_: NumberFormatException) {
                    "Порт должен быть числом"
                }
            },
            onPasswordChange = { customPassword = it },
            onDismiss = { showCustomServerDialog = false },
            onConfirm = {
                val isIpValid = customIpAddress.isNotBlank() && isValidIpAddress(customIpAddress)
                val isPortValid = try {
                    val port = customPort.toInt()
                    port in 1..65535
                } catch (_: NumberFormatException) {
                    false
                }

                if (isIpValid && isPortValid) {
                    val customServer = ServerConfig(
                        name = "Custom",
                        ipAddress = customIpAddress,
                        port = customPort.toInt(),
                        description = "Пользовательский сервер",
                        password = customPassword
                    )
                    onServerSelected(customServer)
                    showCustomServerDialog = false
                } else {
                    if (!isIpValid) {
                        ipError = if (customIpAddress.isBlank()) "Введите IP-адрес" else "Неверный формат IP-адреса"
                    }
                    if (!isPortValid) {
                        portError = if (customPort.isBlank()) "Введите порт" else "Порт должен быть числом от 1 до 65535"
                    }
                }
            }
        )
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
private fun CustomServerDialog(
    showCustomServerDialog: Boolean,
    customIpAddress: String,
    customPort: String,
    customPassword: String,
    ipError: String,
    portError: String,
    onIpAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (showCustomServerDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Кастомный сервер") },
            text = {
                Column {
                    Text("Введите IP-адрес, порт и пароль сервера:")
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = customIpAddress,
                        onValueChange = onIpAddressChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("IP-адрес") },
                        placeholder = { Text("192.168.1.50") },
                        isError = ipError.isNotBlank(),
                        supportingText = {
                            if (ipError.isNotBlank()) {
                                Text(ipError)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customPort,
                        onValueChange = onPortChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Порт") },
                        placeholder = { Text("8080") },
                        isError = portError.isNotBlank(),
                        supportingText = {
                            if (portError.isNotBlank()) {
                                Text(portError)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customPassword,
                        onValueChange = onPasswordChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Пароль (необязательно)") },
                        placeholder = { Text("Введите пароль, если требуется") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
            }
        )
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

private fun getConnectionStatusMessage(state: WebSocketState): String {
    return when (state) {
        WebSocketState.DISCONNECTED -> "Не подключено"
        WebSocketState.CONNECTING -> "Подключение..."
        WebSocketState.CONNECTED -> "Подключено"
        WebSocketState.AUTHENTICATED -> "Аутентифицирован"
        WebSocketState.ERROR -> "Ошибка подключения"
    }
}