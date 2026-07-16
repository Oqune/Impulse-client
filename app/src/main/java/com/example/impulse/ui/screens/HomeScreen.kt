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
import com.example.impulse.data.ServerConfig
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    clientName: String,
    selectedServer: ServerConfig,
    encryptionKey: String,
    modifier: Modifier = Modifier
) {
    val webSocketManager = WebSocketManager.getInstance()
    val connectionState by webSocketManager.currentState.collectAsState()

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
                    WebSocketState.AUTHENTICATED -> MaterialTheme.colorScheme.primaryContainer
                    WebSocketState.CONNECTED -> MaterialTheme.colorScheme.secondaryContainer
                    WebSocketState.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
                    WebSocketState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceContainerHigh
                    WebSocketState.ERROR -> MaterialTheme.colorScheme.errorContainer
                },
                contentColor = when (connectionState) {
                    WebSocketState.AUTHENTICATED -> MaterialTheme.colorScheme.onPrimaryContainer
                    WebSocketState.CONNECTED -> MaterialTheme.colorScheme.onSecondaryContainer
                    WebSocketState.CONNECTING -> MaterialTheme.colorScheme.onSecondaryContainer
                    WebSocketState.DISCONNECTED -> MaterialTheme.colorScheme.onSurface
                    WebSocketState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
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
                    WebSocketState.AUTHENTICATED -> MaterialTheme.colorScheme.primary
                    WebSocketState.CONNECTED -> MaterialTheme.colorScheme.secondary
                    WebSocketState.CONNECTING -> MaterialTheme.colorScheme.secondary
                    WebSocketState.DISCONNECTED -> MaterialTheme.colorScheme.outline
                    WebSocketState.ERROR -> MaterialTheme.colorScheme.error
                }

                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(statusColor, CircleShape)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = when (connectionState) {
                        WebSocketState.DISCONNECTED -> "Отключено"
                        WebSocketState.CONNECTING -> "Подключение..."
                        WebSocketState.CONNECTED -> "Аутентификация..."
                        WebSocketState.AUTHENTICATED -> "Подключено"
                        WebSocketState.ERROR -> "Ошибка"
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
                            WebSocketState.DISCONNECTED, WebSocketState.ERROR -> {
                                CoroutineScope(Dispatchers.IO).launch {
                                    webSocketManager.connect(
                                        selectedServer.getWebSocketUrl(),
                                        selectedServer.password,
                                        clientName,
                                        encryptionKey
                                    )
                                }
                            }
                            WebSocketState.CONNECTING, WebSocketState.CONNECTED, WebSocketState.AUTHENTICATED -> {
                                webSocketManager.disconnect()
                            }
                            else -> {}
                        }
                    },
                    enabled = connectionState != WebSocketState.CONNECTING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (connectionState) {
                            WebSocketState.DISCONNECTED, WebSocketState.ERROR -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondary
                        }
                    )
                ) {
                    when (connectionState) {
                        WebSocketState.DISCONNECTED, WebSocketState.ERROR -> {
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
                    color = if (connectionState == WebSocketState.AUTHENTICATED)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 1.dp
                ) {
                    Text(
                        text = if (connectionState == WebSocketState.AUTHENTICATED) "Подключено" else "Не подключено",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (connectionState == WebSocketState.AUTHENTICATED)
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
