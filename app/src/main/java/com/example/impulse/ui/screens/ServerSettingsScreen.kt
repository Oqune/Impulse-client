package com.example.impulse.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.isValidIpAddress
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    selectedServer: ServerConfig,
    onServerSelected: (ServerConfig) -> Unit,
    onBack: () -> Unit,
    encryptionKey: String,
    onEncryptionKeyChange: (String) -> Unit
) {
    var showCustomServerDialog by remember { mutableStateOf(false) }
    var customIpAddress by remember { mutableStateOf("") }
    var customPort by remember { mutableStateOf("8080") }
    var customPassword by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf("") }
    var portError by remember { mutableStateOf("") }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 56.dp), // Компенсация высоты AppBar
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Заголовок с кнопкой назад
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                }

                Spacer(Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "Настройки сервера",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Выбор сервера
                    Text(
                        text = "Выберите сервер",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

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

                                Spacer(Modifier.width(8.dp))

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

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Ключ шифрования",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = encryptionKey,
                        onValueChange = onEncryptionKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Ключ шифрования") },
                        placeholder = { Text("Введите ключ для E2E шифрования") },
                        leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
                        supportingText = {
                            Text("Шифруются только текстовые сообщения")
                        },
                        enabled = connectionState != WebSocketState.CONNECTING &&
                             connectionState != WebSocketState.CONNECTED &&
                             connectionState != WebSocketState.AUTHENTICATED
                    )

                    Spacer(modifier = Modifier.height(16.dp))

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
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Добавить кастомный сервер")
                    }
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