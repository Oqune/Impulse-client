package com.example.impulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.impulse.ChatController
import com.example.impulse.data.ServerConfig
import com.example.impulse.transport.ConnectionState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    clientName: String,
    selectedServer: ServerConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val chatController = remember { ChatController.getInstance(context) }
    val connectionState by chatController.state.collectAsState()
    val scope = rememberCoroutineScope()

    DecorativeBackground(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Impulse Chat",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        ConnectionState.AUTHENTICATED -> MaterialTheme.colorScheme.primaryContainer
                        ConnectionState.READY -> MaterialTheme.colorScheme.primaryContainer
                        ConnectionState.AUTHENTICATING -> MaterialTheme.colorScheme.secondaryContainer
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.secondaryContainer
                        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceContainerHigh
                        ConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = when (connectionState) {
                        ConnectionState.AUTHENTICATED -> MaterialTheme.colorScheme.onPrimaryContainer
                        ConnectionState.READY -> MaterialTheme.colorScheme.onPrimaryContainer
                        ConnectionState.AUTHENTICATING -> MaterialTheme.colorScheme.onSecondaryContainer
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.onSecondaryContainer
                        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.onSecondaryContainer
                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurface
                        ConnectionState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                    }
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusColor = when (connectionState) {
                        ConnectionState.READY -> MaterialTheme.colorScheme.primary
                        ConnectionState.AUTHENTICATED -> MaterialTheme.colorScheme.primary
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.secondary
                        ConnectionState.AUTHENTICATING -> MaterialTheme.colorScheme.secondary
                        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
                        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                    }

                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(statusColor, CircleShape)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = when (connectionState) {
                            ConnectionState.DISCONNECTED -> "Отключено"
                            ConnectionState.CONNECTING -> "Подключение..."
                            ConnectionState.CONNECTED -> "Аутентификация..."
                            ConnectionState.AUTHENTICATING -> "Аутентификация..."
                            ConnectionState.AUTHENTICATED -> "Установка канала..."
                            ConnectionState.READY -> "Подключено (PQ-E2EE)"
                            ConnectionState.ERROR -> "Ошибка / нужен QR"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${selectedServer.name} · ${selectedServer.ipAddress}:${selectedServer.port}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            when (connectionState) {
                                ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                                    scope.launch {
                                        chatController.connect(selectedServer, clientName)
                                    }
                                }
                                else -> chatController.disconnect()
                            }
                        },
                        enabled = connectionState != ConnectionState.CONNECTING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (connectionState) {
                                ConnectionState.DISCONNECTED, ConnectionState.ERROR -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.secondary
                            }
                        )
                    ) {
                        when (connectionState) {
                            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Подключиться", style = MaterialTheme.typography.titleSmall)
                            }
                            else -> {
                                Icon(Icons.Default.Close, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Отключиться", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Пользователь",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = clientName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Surface(
                        modifier = Modifier,
                        shape = RoundedCornerShape(12.dp),
                        color = if (connectionState == ConnectionState.READY)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 1.dp
                    ) {
                        Text(
                            text = if (connectionState == ConnectionState.READY) "PQ-E2EE" else "Не подключено",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (connectionState == ConnectionState.READY)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
