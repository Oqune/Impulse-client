package com.example.impulse.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.ui.theme.*
import com.example.impulse.util.LogManager
import kotlinx.coroutines.delay

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
    onServerDeleted: (ServerConfig) -> Unit = {}
) {
    val context = LocalContext.current
    var currentSection by remember { mutableStateOf(SettingsSection.MAIN) }
    var showLogs by remember { mutableStateOf(false) }

    fun goBack() {
        when {
            showLogs -> showLogs = false
            currentSection != SettingsSection.MAIN -> currentSection = SettingsSection.MAIN
        }
    }

    val title = when {
        showLogs -> "Логи"
        currentSection == SettingsSection.SERVER -> "Сервер"
        currentSection == SettingsSection.USER -> "Профиль"
        currentSection == SettingsSection.APP -> "Приложение"
        else -> "Настройки"
    }

    val showBack = currentSection != SettingsSection.MAIN || showLogs

    Box(modifier = modifier) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (showBack) {
                            IconButton(onClick = { goBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            if (showLogs) {
                LogsContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            } else {
                when (currentSection) {
                    SettingsSection.MAIN -> {
                        SettingsMainContent(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            onNavigateToServer = { currentSection = SettingsSection.SERVER },
                            onNavigateToUser = { currentSection = SettingsSection.USER },
                            onNavigateToApp = { currentSection = SettingsSection.APP },
                            onShowLogs = { showLogs = true },
                        )
                    }
                    SettingsSection.SERVER -> {
                        ServerSettingsContent(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            selectedServer = selectedServer,
                            onServerSelected = onServerSelected,
                            availableServers = availableServers,
                            onServerDeleted = onServerDeleted,
                            onServerUpdated = onServerUpdated
                        )
                    }
                    SettingsSection.USER -> {
                        UserSettingsContent(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            clientName = clientName,
                            onClientNameChange = onClientNameChange
                        )
                    }
                    SettingsSection.APP -> {
                        AppSettingsContent(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsMainContent(
    modifier: Modifier = Modifier,
    onNavigateToServer: () -> Unit,
    onNavigateToUser: () -> Unit,
    onNavigateToApp: () -> Unit,
    onShowLogs: () -> Unit,
) {
    DecorativeBackground(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = "Настройки",
                style = MaterialTheme.typography.headlineMedium
            )

            ImpulseMenuCard(
                title = "Сервер",
                icon = Icons.Default.Build,
                onClick = onNavigateToServer
            )

            ImpulseMenuCard(
                title = "Пользователь",
                icon = Icons.Default.Person,
                onClick = onNavigateToUser
            )

            ImpulseMenuCard(
                title = "Приложение",
                icon = Icons.Default.Settings,
                onClick = onNavigateToApp
            )

            OutlinedButton(
                onClick = onShowLogs,
                modifier = Modifier.fillMaxWidth(),
                shape = ButtonShape,
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Логи")
            }
        }
    }
}

@Composable
private fun LogsContent(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("ALL") }
    var allLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastRefreshVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            allLogs = LogManager.readLast(1000)
            lastRefreshVersion++
            delay(1000L)
        }
    }
    val filtered = remember(filter, allLogs) {
        if (filter == "ALL") allLogs else allLogs.filter { it.contains("[$filter]") }
    }

    fun minimalLine(raw: String): String {
        val timeMatch = Regex("""\[(\d{2}:\d{2}:\d{2})""").find(raw)
        val time = timeMatch?.groupValues?.get(1) ?: ""
        val level = when {
            raw.contains("[ERROR]") -> "ERR"
            raw.contains("[WARN]")  -> "WRN"
            raw.contains("[INFO]")  -> "INF"
            raw.contains("[DEBUG]") -> "DBG"
            else -> ""
        }
        val msgStart = raw.indexOf("]", raw.indexOf("]", raw.indexOf("]") + 1) + 1)
        val msg = if (msgStart > 0) raw.substring(msgStart + 1).trim() else raw
        return if (time.isNotEmpty()) "$time $level  $msg" else msg
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        // Header row: filters + actions
        ImpulseCard(modifier = Modifier.padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("ALL", "ERROR", "WARN", "INFO").forEach { level ->
                    FilterChip(
                        selected = filter == level,
                        onClick = { filter = level },
                        label = { Text(level, style = MaterialTheme.typography.labelSmall) },
                        shape = ChipShape,
                    )
                }
                Spacer(Modifier.weight(1f))
                ImpulseIconButton(
                    icon = Icons.Default.Share,
                    contentDescription = "Экспорт",
                    onClick = {
                        val fileName = LogManager.exportToDownloads(context)
                        Toast.makeText(
                            context,
                            if (fileName != null) "Экспорт: $fileName" else "Нет записей",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    tint = MaterialTheme.colorScheme.primary,
                )
                ImpulseIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = "Очистить",
                    onClick = {
                        LogManager.clear()
                        Toast.makeText(context, "Логи очищены", Toast.LENGTH_SHORT).show()
                    },
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Log list
        ImpulseCard(modifier = Modifier.weight(1f)) {
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Нет записей",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(filtered) { line ->
                        val color = when {
                            line.contains("[ERROR]") -> MaterialTheme.colorScheme.error
                            line.contains("[WARN]")  -> MaterialTheme.colorScheme.tertiary
                            line.contains("[INFO]")  -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        ImpulseLogLine(text = minimalLine(line), color = color)
                    }
                }
            }
        }
    }
}
