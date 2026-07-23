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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.ChatController
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.data.isValidIpAddress
import com.example.impulse.security.TrustedCertManager
import com.example.impulse.transport.ConnectionState
import com.example.impulse.transport.Protocol
import com.example.impulse.util.LogManager

@Composable
fun ServerSettingsContent(
    modifier: Modifier = Modifier,
    selectedServer: ServerConfig,
    onServerSelected: (ServerConfig) -> Unit,
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
    var showForgetCert by remember { mutableStateOf(false) }
    var showCertDetails by remember { mutableStateOf(false) }
    var certVersion by remember { mutableIntStateOf(0) }
    val certManager = remember { TrustedCertManager(context) }
    val isCertTrusted by remember(selectedServer, certVersion) { mutableStateOf(certManager.isTrusted(selectedServer.id)) }

    fun loadServerForEdit(server: ServerConfig) {
        customName = server.name
        customIpAddress = server.ipAddress
        customPort = server.port.toString()
        customPassword = server.password
    }

    val chatController = remember { ChatController.getInstance(context) }
    val connectionState by chatController.state.collectAsState()

    var previousServer by remember { mutableStateOf(selectedServer) }
    LaunchedEffect(selectedServer) {
        if (previousServer != selectedServer) {
            if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.AUTHENTICATING || connectionState == ConnectionState.AUTHENTICATED || connectionState == ConnectionState.READY) {
                chatController.disconnect()
                LogManager.i("ServerSettings", "disconnect from previous server on settings change")
            }
            previousServer = selectedServer
        }
    }

    val customServers = availableServers.filter {
        it !in ServerConfig.builtInServers
    }

    Column(
        modifier = modifier
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
                        val serverConnectionState = if (isSelectedServer) connectionState else ConnectionState.DISCONNECTED

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
                                        text = "IP: ${server.ipAddress}:${server.port} (WebTransport)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelectedServer)
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (server.password.isNotEmpty()) {
                                        val passwordHash = remember(server.password) { Protocol.sha256Hex(server.password) }
                                        Text(
                                            text = "Пароль установлен",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelectedServer)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "SHA-256: ${passwordHash.take(16)}…",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isSelectedServer)
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (isSelectedServer) {
                                        val err by chatController.lastError.collectAsState()
                                        ServerConnectionStatusIndicator(serverConnectionState, err)
                                    }

                                    if (isSelectedServer) {
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = { showCertDetails = !showCertDetails },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = if (isCertTrusted) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(
                                                if (isCertTrusted) Icons.Default.Lock else Icons.Default.LockOpen,
                                                contentDescription = null
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                if (isCertTrusted) "Сертификат: привязан"
                                                else "Сертификат: не привязан"
                                            )
                                            Spacer(Modifier.weight(1f))
                                            Icon(
                                                if (showCertDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
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

                if (isCertTrusted && showCertDetails) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Сертификат сервера",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.height(12.dp))

                            val certHashes = remember(selectedServer.id) { certManager.getHashes(selectedServer.id) }

                            certHashes.forEachIndexed { index, hash ->
                                val label = when {
                                    index == 0 && certHashes.size == 1 -> "Отпечаток SHA-256"
                                    index == 0 -> "Текущий (основной)"
                                    index == 1 -> "Следующий (ротация)"
                                    else -> "Хеш #${index + 1}"
                                }
                                val short = LogManager.shortHash(hash)

                                Text(
                                    text = "$label: $short…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Spacer(Modifier.height(4.dp))

                                Text(
                                    text = hash,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (index < certHashes.size - 1) {
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            OutlinedButton(
                                onClick = { showForgetCert = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Отвязать сертификат")
                            }
                        }
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

    if (showForgetCert) {
        AlertDialog(
            onDismissRequest = { showForgetCert = false },
            title = { Text("Удалить сертификат?") },
            text = {
                Text(
                    "Сервер «${selectedServer.name}» будет отвязан (TOFU-хеш удалён). " +
                        "Следующее подключение снова потребует сканирования QR-кода. " +
                        "Активное соединение будет разорвано."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetCert = false
                        showCertDetails = false
                        try {
                            chatController.disconnect()
                        } catch (_: Exception) {
                        }
                        certManager.forget(selectedServer.id)
                        certVersion++
                        LogManager.i("ServerSettings", "certificate forgotten for ${selectedServer.id}")
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetCert = false }) {
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

                    if (customPassword.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val passwordHash = remember(customPassword) { Protocol.sha256Hex(customPassword) }
                        Text(
                            text = "SHA-256: ${passwordHash.take(16)}…",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

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
private fun ServerConnectionStatusIndicator(
    connectionState: ConnectionState,
    lastError: String? = null
) {
    val statusData = when (connectionState) {
        ConnectionState.DISCONNECTED ->
            Triple("Отключено", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceContainerHighest)
        ConnectionState.CONNECTING ->
            Triple("Подключение...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        ConnectionState.CONNECTED ->
            Triple("Аутентификация...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        ConnectionState.AUTHENTICATING ->
            Triple("Аутентификация...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        ConnectionState.AUTHENTICATED ->
            Triple("Подключено (PQ-E2EE)", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        ConnectionState.READY ->
            Triple("Подключено (PQ-E2EE)", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        ConnectionState.ERROR ->
            Triple("Ошибка подключения", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
    }
    val (statusText, statusColor, backgroundColor) = statusData

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
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
        if (connectionState == ConnectionState.ERROR && !lastError.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
