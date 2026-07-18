package com.example.impulse.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.util.LogManager

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
    availableServers: List<ServerConfig> = ServerConfig.builtInServers,
    onServerDeleted: (ServerConfig) -> Unit = {}
) {
    var currentSection by remember { mutableStateOf(SettingsSection.MAIN) }
    var showLogs by remember { mutableStateOf(false) }

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
        LogsScreen(onBack = { showLogs = false })
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

/**
 * Full-screen logs viewer backed by [LogManager].
 * Features: level filter chips (ALL / ERROR / WARN / INFO), export to a file in
 * the app-specific Documents directory, and clear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("ALL") }
    val allLogs = remember { LogManager.readLast(1000) }
    val filtered = remember(filter) {
        if (filter == "ALL") allLogs else allLogs.filter { it.contains("[$filter]") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Системные логи") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val target = LogManager.exportRecent(context.getExternalFilesDir(null) ?: context.filesDir)
                        Toast.makeText(
                            context,
                            if (target != null) "Экспортировано: ${target.name}" else "Нет записей для экспорта",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Экспорт")
                    }
                    IconButton(onClick = {
                        LogManager.clear()
                        Toast.makeText(context, "Логи очищены", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Очистить")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Level filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALL", "ERROR", "WARN", "INFO").forEach { level ->
                    FilterChip(
                        selected = filter == level,
                        onClick = { filter = level },
                        label = { Text(level) }
                    )
                }
            }

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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filtered) { line ->
                        val color = when {
                            line.contains("[ERROR]") -> MaterialTheme.colorScheme.error
                            line.contains("[WARN]") -> MaterialTheme.colorScheme.tertiary
                            line.contains("[INFO]") -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
