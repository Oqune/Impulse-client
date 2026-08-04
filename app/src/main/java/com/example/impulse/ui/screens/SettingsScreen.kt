package com.example.impulse.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.Color

import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.impulse.ConnectionManager
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.transport.ConnectionState
import androidx.compose.ui.res.stringResource
import com.example.impulse.R
import com.example.impulse.ui.theme.*

enum class SettingsSection {
    MAIN, SERVER, USER, APP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedServer: ServerConfig,
    onServerSelected: (ServerConfig) -> Unit,
    onServerUpdated: (ServerConfig) -> Unit = {},
    modifier: Modifier = Modifier,
    clientName: String,
    onClientNameChange: (String) -> Unit,
    availableServers: List<ServerConfig> = ServerConfig.builtInServers,
    onServerAdded: (ServerConfig) -> Unit = {},
    onServerDeleted: (ServerConfig) -> Unit = {},
    onVisibilityChanged: () -> Unit = {},
    certRefreshTrigger: Int = 0,
    connectionManager: ConnectionManager? = null,
    onScanQr: (ServerConfig) -> Unit = {},
) {
    val context = LocalContext.current
    var currentSection by remember { mutableStateOf(SettingsSection.MAIN) }
    var showAddServerDialog by remember { mutableStateOf(false) }

    fun goBack() {
        if (currentSection != SettingsSection.MAIN) currentSection = SettingsSection.MAIN
    }

    val title = when (currentSection) {
        SettingsSection.SERVER -> stringResource(R.string.settings_servers)
        SettingsSection.USER -> stringResource(R.string.settings_user)
        SettingsSection.APP -> stringResource(R.string.settings_app)
        else -> stringResource(R.string.settings_title)
    }

    val showBack = currentSection != SettingsSection.MAIN

    Box(modifier = modifier) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        if (showBack) {
                            IconButton(onClick = { goBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                            }
                        }
                    },
                    actions = {
                        if (currentSection == SettingsSection.SERVER) {
                            IconButton(onClick = { showAddServerDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dialog_add))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            when (currentSection) {
                SettingsSection.MAIN -> {
                    SettingsMainContent(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        onNavigateToServer = { currentSection = SettingsSection.SERVER },
                        onNavigateToUser = { currentSection = SettingsSection.USER },
                        onNavigateToApp = { currentSection = SettingsSection.APP },
                    )
                }
                SettingsSection.SERVER -> {
                    DecorativeBackground(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                    ServerListContent(
                        modifier = Modifier.fillMaxSize(),
                        availableServers = availableServers,
                        connectionManager = connectionManager,
                        onVisibilityChanged = onVisibilityChanged,
                        certRefreshTrigger = certRefreshTrigger,
                        onServerDeleted = onServerDeleted,
                        onScanQr = onScanQr,
                        onServerUpdated = onServerUpdated,
                    )
                    }
                }
                SettingsSection.USER -> {
                    DecorativeBackground(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                    UserSettingsContent(
                        modifier = Modifier.fillMaxSize(),
                        clientName = clientName,
                        onClientNameChange = onClientNameChange
                    )
                    }
                }
                SettingsSection.APP -> {
                    DecorativeBackground(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                    AppSettingsContent(
                        modifier = Modifier.fillMaxSize(),
                    )
                    }
                }
            }
        }

        if (showAddServerDialog) {
            AddServerDialog(
                onDismiss = { showAddServerDialog = false },
                onAdd = { server ->
                    onServerAdded(server)
                    showAddServerDialog = false
                }
            )
        }
    }
}

// ============================================================================
// SERVER LIST — expandable per-server settings
// ============================================================================

@Composable
private fun ServerListContent(
    modifier: Modifier = Modifier,
    availableServers: List<ServerConfig>,
    connectionManager: ConnectionManager?,
    onVisibilityChanged: () -> Unit,
    certRefreshTrigger: Int = 0,
    onServerDeleted: (ServerConfig) -> Unit,
    onScanQr: (ServerConfig) -> Unit = {},
    onServerUpdated: (ServerConfig) -> Unit = {},
) {
    val context = LocalContext.current
    val serverPreferences = remember { ServerPreferences(context) }
    val serverStates by connectionManager?.serverStates?.collectAsState() ?: remember { mutableStateOf(emptyMap<String, ConnectionManager.ServerStatus>()) }
    var expandedServerId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        items(availableServers, key = { it.id }) { server ->
            val status = serverStates[server.id]
            val isConnected = status?.state == ConnectionState.READY
            val isConnecting = status?.state in listOf(
                ConnectionState.CONNECTING, ConnectionState.CONNECTED,
                ConnectionState.AUTHENTICATING, ConnectionState.AUTHENTICATED
            )
            val hasError = status?.state == ConnectionState.ERROR
            val isExpanded = expandedServerId == server.id

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column {
                    // Server header — tap to expand
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedServerId = if (isExpanded) null else server.id
                            }
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

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = server.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = server.ipAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Icon(
                            if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Expanded settings
                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        ServerExpandableSettings(
                            server = server,
                            connectionManager = connectionManager,
                            serverPreferences = serverPreferences,
                            onVisibilityChanged = onVisibilityChanged,
                            certRefreshTrigger = certRefreshTrigger,
                            onServerDeleted = onServerDeleted,
                            onScanQr = onScanQr,
                            onServerUpdated = onServerUpdated,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerExpandableSettings(
    server: ServerConfig,
    connectionManager: ConnectionManager?,
    serverPreferences: ServerPreferences,
    onVisibilityChanged: () -> Unit,
    certRefreshTrigger: Int = 0,
    onServerDeleted: (ServerConfig) -> Unit,
    onScanQr: (ServerConfig) -> Unit = {},
    onServerUpdated: ((ServerConfig) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val certManager = remember { com.example.impulse.security.TrustedCertManager(context) }
    var certVersion by remember { mutableIntStateOf(0) }

    var autoConnect by remember(server.id) {
        mutableStateOf(serverPreferences.getServerAutoConnect(server.id))
    }
    var autoReconnect by remember(server.id) {
        mutableStateOf(serverPreferences.getServerAutoReconnect(server.id))
    }
    var isServerVisible by remember(server.id) {
        mutableStateOf(serverPreferences.isServerVisible(server.id))
    }

    val isCustom = !server.id.startsWith("prod_") && !server.id.startsWith("local_")
    var editName by remember(server.id) { mutableStateOf(server.name) }
    var editAddress by remember(server.id) { mutableStateOf(server.ipAddress) }
    var editPort by remember(server.id) { mutableStateOf(server.port.toString()) }
    var editPassword by remember(server.id) { mutableStateOf(server.password) }
    var addressError by remember(server.id) { mutableStateOf(false) }
    var portError by remember(server.id) { mutableStateOf(false) }

    val connectionState = if (connectionManager != null) {
        val ctrl = connectionManager.getControllerOrNull(server.id)
        ctrl?.state?.collectAsState()?.value ?: ConnectionState.DISCONNECTED
    } else {
        ConnectionState.DISCONNECTED
    }

    val certInfos = remember(server.id, certVersion, certRefreshTrigger) { certManager.getCertInfos(server.id) }
    val isCertTrusted = remember(server.id, certVersion, certRefreshTrigger) { certManager.isTrusted(server.id) }

    var certSectionOpen by remember { mutableStateOf(false) }
    var connSectionOpen by remember { mutableStateOf(false) }
    var addrSectionOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        // ── Section: Сертификаты ──
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { certSectionOpen = !certSectionOpen }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isCertTrusted) Icons.Default.Shield else Icons.Default.Security,
                    contentDescription = null,
                    tint = if (isCertTrusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_certificates), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (!isCertTrusted) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    ) {
                        Text(
                            stringResource(R.string.settings_cert_none),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (certSectionOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = certSectionOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // QR Scan button
                    OutlinedButton(
                        onClick = { onScanQr(server) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_scan_qr), style = MaterialTheme.typography.labelMedium)
                    }

                    // Cert info
                    if (certInfos.isNotEmpty()) {
                        certInfos.forEach { info ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            info.sha256Hex.take(20) + "...",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            stringResource(R.string.settings_cert_added, java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(info.issuedAt))),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                        )
                                    }
                                    ShieldBadge(
                                        text = stringResource(R.string.settings_cert_ok),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                            }
                        }
                        // Forget button
                        Text(
                            stringResource(R.string.settings_cert_delete_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    certManager.forget(server.id)
                                    certVersion++
                                }
                                .padding(vertical = 4.dp)
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_cert_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
        }
        }
        }

        // ── Section: Подключение и отображение ──
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { connSectionOpen = !connSectionOpen }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_conn_and_display), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            connectionState == ConnectionState.READY -> stringResource(R.string.settings_conn_status_connected)
                            connectionState == ConnectionState.CONNECTING ||
                            connectionState == ConnectionState.AUTHENTICATING ||
                            connectionState == ConnectionState.AUTHENTICATED -> stringResource(R.string.settings_conn_status_connecting)
                            connectionState == ConnectionState.ERROR -> stringResource(R.string.settings_conn_status_error)
                            else -> stringResource(R.string.settings_conn_status_disconnected)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            connectionState == ConnectionState.READY -> MaterialTheme.colorScheme.primary
                            connectionState == ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Icon(
                    if (connSectionOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = connSectionOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Connect/disconnect
                    if (connectionManager != null) {
                        val isConnected = connectionState == ConnectionState.READY
                        OutlinedButton(
                            onClick = {
                                if (isConnected) {
                                    connectionManager.disconnect(server.id)
                                } else {
                                    connectionManager.connect(server, connectionManager.getController(server).clientName)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                if (isConnected) Icons.Default.LinkOff else Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (isConnected) stringResource(R.string.chat_disconnect) else stringResource(R.string.chat_connect), style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    ImpulseToggle(
                        title = stringResource(R.string.settings_auto_connect),
                        description = stringResource(R.string.settings_auto_connect_desc),
                        checked = autoConnect,
                        onCheckedChange = { enabled ->
                            autoConnect = enabled
                            serverPreferences.setServerAutoConnect(server.id, enabled)
                        }
                    )

                    ImpulseToggle(
                        title = stringResource(R.string.settings_auto_reconnect),
                        description = stringResource(R.string.settings_auto_reconnect_desc),
                        checked = autoReconnect,
                        onCheckedChange = { enabled ->
                            autoReconnect = enabled
                            serverPreferences.setServerAutoReconnect(server.id, enabled)
                            connectionManager?.setServerAutoReconnect(server.id, enabled)
                        }
                    )

                    ImpulseToggle(
                        title = stringResource(R.string.settings_show_in_chats),
                        description = stringResource(R.string.settings_show_in_chats_desc),
                        checked = isServerVisible,
                        onCheckedChange = { enabled ->
                            isServerVisible = enabled
                            serverPreferences.setServerVisible(server.id, enabled)
                            onVisibilityChanged()
                        }
                    )
                }
            }
        }

        // ── Section: Адрес и конфиг ──
        if (isCustom && onServerUpdated != null) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { addrSectionOpen = !addrSectionOpen }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.settings_address_and_config), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(
                        if (addrSectionOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(visible = addrSectionOpen, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text(stringResource(R.string.settings_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = editAddress,
                            onValueChange = { editAddress = it; addressError = false },
                            label = { Text(stringResource(R.string.settings_ip_domain)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            isError = addressError,
                            supportingText = if (addressError) {{ Text(stringResource(R.string.settings_invalid_format)) }} else null,
                        )
                        OutlinedTextField(
                            value = editPort,
                            onValueChange = { editPort = it.filter { c -> c.isDigit() }; portError = false },
                            label = { Text(stringResource(R.string.settings_port)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            isError = portError,
                            supportingText = if (portError) {{ Text(stringResource(R.string.settings_port_range)) }} else null,
                        )
                        OutlinedTextField(
                            value = editPassword,
                            onValueChange = { editPassword = it },
                            label = { Text(stringResource(R.string.settings_password_optional)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            // Never show server passwords in plaintext (Bug: "password field is plaintext").
                            visualTransformation = if (editPassword.isEmpty()) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        OutlinedButton(
                            onClick = {
                                val portInt = editPort.toIntOrNull()
                                addressError = editAddress.isBlank()
                                portError = portInt == null || portInt !in 1..65535
                                if (!addressError && !portError && editName.isNotBlank()) {
                                    val updated = server.copy(
                                        name = editName.trim(),
                                        ipAddress = editAddress.trim(),
                                        port = portInt!!,
                                        password = editPassword.trim()
                                    )
                                    serverPreferences.updateCustomServer(updated)
                                    onServerUpdated(updated)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.common_save))
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        Text(
                            stringResource(R.string.settings_delete_server),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onServerDeleted(server)
                                    onVisibilityChanged()
                                }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// MAIN SETTINGS
// ============================================================================

@Composable
private fun SettingsMainContent(
    modifier: Modifier = Modifier,
    onNavigateToServer: () -> Unit,
    onNavigateToUser: () -> Unit,
    onNavigateToApp: () -> Unit,
) {
    DecorativeBackground(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            ImpulseMenuCard(
                title = stringResource(R.string.settings_servers),
                icon = Icons.Default.Build,
                onClick = onNavigateToServer
            )

            ImpulseMenuCard(
                title = stringResource(R.string.settings_user),
                icon = Icons.Default.Person,
                onClick = onNavigateToUser
            )

            ImpulseMenuCard(
                title = stringResource(R.string.settings_app),
                icon = Icons.Default.Settings,
                onClick = onNavigateToApp
            )
        }
    }
}

// ============================================================================
// ADD SERVER DIALOG
// ============================================================================

@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (ServerConfig) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("4433") }
    var password by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf(false) }
    var portError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; addressError = false },
                    label = { Text(stringResource(R.string.settings_ip_domain)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    isError = addressError,
                    supportingText = if (addressError) {{ Text(stringResource(R.string.settings_invalid_format)) }} else null,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }; portError = false },
                    label = { Text(stringResource(R.string.settings_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    isError = portError,
                    supportingText = if (portError) {{ Text(stringResource(R.string.settings_port_range)) }} else null,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_password_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (password.isEmpty()) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val portInt = port.toIntOrNull()
                addressError = address.isBlank()
                portError = portInt == null || portInt !in 1..65535
                if (!addressError && !portError && name.isNotBlank()) {
                    onAdd(ServerConfig(
                        name = name.trim(),
                        ipAddress = address.trim(),
                        port = portInt!!,
                        description = "",
                        password = password.trim(),
                    ))
                }
            }) {
                Text(stringResource(R.string.dialog_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        shape = RoundedCornerShape(16.dp),
    )
}
