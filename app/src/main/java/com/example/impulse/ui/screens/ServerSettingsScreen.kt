package com.example.impulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.data.isValidIpAddress
import com.example.impulse.util.LogStorage
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    selectedServer: ServerConfig,
    onServerSelected: (ServerConfig) -> Unit,
    onBack: () -> Unit,
    encryptionKey: String,
    onEncryptionKeyChange: (String) -> Unit,
    availableServers: List<ServerConfig> = ServerConfig.builtInServers,
    onServerDeleted: (ServerConfig) -> Unit = {},
    onServerUpdated: (ServerConfig) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showCustomServerDialog by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customIpAddress by remember { mutableStateOf("") }
    var customPort by remember { mutableStateOf("8080") }
    var customPassword by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf("") }
    var portError by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf("") }
    var serverToDelete by remember { mutableStateOf<ServerConfig?>(null) }
    var serverToEdit by remember { mutableStateOf<ServerConfig?>(null) }
    var isEditMode by remember { mutableStateOf(false) }

    // Helper function to load server data for editing
    fun loadServerForEdit(server: ServerConfig) {
        customName = server.name
        customIpAddress = server.ipAddress
        customPort = server.port.toString()
        customPassword = server.password
    }

    val webSocketManager = WebSocketManager.getInstance()
    val connectionState by webSocketManager.currentState.collectAsState()

    var previousServer by remember { mutableStateOf(selectedServer) }
    LaunchedEffect(selectedServer) {
        if (previousServer != selectedServer) {
            if (connectionState == WebSocketState.CONNECTED || connectionState == WebSocketState.AUTHENTICATED) {
                webSocketManager.disconnect()
                LogStorage.addLog("Отключение от предыдущего сервера при смене настройки")
            }
            previousServer = selectedServer
        }
    }

    val customServers = availableServers.filter {
        it !in ServerConfig.builtInServers
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки сервера") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding() // keep content clear of the outer bottom nav bar
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Выберите сервер",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        availableServers.forEach { server ->
                            val isCustom = server !in ServerConfig.builtInServers
                            val isSelectedServer = server == selectedServer
                            val serverConnectionState = if (isSelectedServer) connectionState else WebSocketState.DISCONNECTED

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = isSelectedServer,
                                        onClick = { onServerSelected(server) },
                                        role = Role.RadioButton
                                    ),
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelectedServer)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = if (isSelectedServer) 2.dp else 0.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelectedServer,
                                        onClick = null,
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MaterialTheme.colorScheme.primary
                                        )
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = server.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelectedServer)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )

                                        Text(
                                            text = "IP: ${server.ipAddress}:${server.port} (WSS)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelectedServer)
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        if (server.password.isNotEmpty()) {
                                            Text(
                                                text = "Пароль установлен",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isSelectedServer)
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                else
                                                    MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        // Connection status for selected server
                                        if (isSelectedServer) {
                                            ServerConnectionStatusIndicator(serverConnectionState)
                                        }
                                    }

                                    if (isCustom) {
                                        Row(
                                            modifier = Modifier,
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    serverToEdit = server
                                                    isEditMode = true
                                                    loadServerForEdit(server)
                                                    showCustomServerDialog = true
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = "Редактировать сервер",
                                                    tint = if (isSelectedServer)
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    else
                                                        MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(Modifier.width(4.dp))
                                            IconButton(
                                                onClick = { serverToDelete = server }
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Удалить сервер",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
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
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            customName = ""
                            customIpAddress = ""
                            customPort = "8080"
                            customPassword = ""
                            ipError = ""
                            portError = ""
                            nameError = ""
                            isEditMode = false
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
            customName = customName,
            customIpAddress = customIpAddress,
            customPort = customPort,
            customPassword = customPassword,
            nameError = nameError,
            ipError = ipError,
            portError = portError,
            isEditMode = isEditMode,
            onNameChange = { newName ->
                customName = newName
                nameError = if (newName.isBlank()) "Введите название сервера" else ""
            },
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
            onDismiss = { showCustomServerDialog = false; isEditMode = false },
            onConfirm = {
                val isNameValid = customName.isNotBlank()
                val isIpValid = customIpAddress.isNotBlank() && isValidIpAddress(customIpAddress)
                val isPortValid = try {
                    val port = customPort.toInt()
                    port in 1..65535
                } catch (_: NumberFormatException) {
                    false
                }

                if (isNameValid && isIpValid && isPortValid) {
                    val customServer = ServerConfig(
                        id = if (isEditMode) serverToEdit!!.id else java.util.UUID.randomUUID().toString(),
                        name = customName,
                        ipAddress = customIpAddress,
                        port = customPort.toInt(),
                        description = "Пользовательский сервер",
                        password = customPassword
                    )
                    val serverPreferences = ServerPreferences(context)
                    if (isEditMode) {
                        serverPreferences.updateCustomServer(customServer)
                        onServerUpdated(customServer)
                    } else {
                        serverPreferences.addCustomServer(customServer)
                        onServerSelected(customServer)
                    }
                    showCustomServerDialog = false
                    isEditMode = false
                } else {
                    if (!isNameValid) {
                        nameError = if (customName.isBlank()) "Введите название сервера" else ""
                    }
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

    serverToDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            title = { Text("Удалить сервер?") },
            text = { Text("Вы уверены, что хотите удалить сервер \"${server.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val serverPreferences = ServerPreferences(context)
                        serverPreferences.removeCustomServer(server)
                        onServerDeleted(server)
                        serverToDelete = null
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}


@Composable
private fun CustomServerDialog(
    showCustomServerDialog: Boolean,
    customName: String,
    customIpAddress: String,
    customPort: String,
    customPassword: String,
    nameError: String,
    ipError: String,
    portError: String,
    isEditMode: Boolean,
    onNameChange: (String) -> Unit,
    onIpAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (showCustomServerDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            title = { Text(if (isEditMode) "Редактировать сервер" else "Кастомный сервер") },
            text = {
                Column {
                    Text(
                        "Введите данные для подключения к серверу:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = customName,
                        onValueChange = onNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Название сервера") },
                        placeholder = { Text("Мой сервер") },
                        isError = nameError.isNotBlank(),
                        supportingText = {
                            if (nameError.isNotBlank()) {
                                Text(nameError)
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

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
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

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
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = customPassword,
                        onValueChange = onPasswordChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Пароль (необязательно)") },
                        placeholder = { Text("Введите пароль, если требуется") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(if (isEditMode) "Сохранить" else "Сохранить")
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
private fun ServerConnectionStatusIndicator(connectionState: WebSocketState) {
    val statusData = when (connectionState) {
        WebSocketState.DISCONNECTED ->
            Triple("Отключено", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceContainerHighest)
        WebSocketState.CONNECTING ->
            Triple("Подключение...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        WebSocketState.CONNECTED ->
            Triple("Аутентификация...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        WebSocketState.AUTHENTICATED ->
            Triple("Подключено", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        WebSocketState.ERROR ->
            Triple("Ошибка", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
    }
    val (statusText, statusColor, backgroundColor) = statusData

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(
                    color = backgroundColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}
