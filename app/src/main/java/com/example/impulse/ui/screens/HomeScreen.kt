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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.background
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Заголовок
        Icon(
            imageVector = Icons.Default.Home,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(64.dp)
                .padding(16.dp)
        )

        Text(
            text = "Добро пожаловать в Impulse Chat",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Быстрый и безопасный чат через WebSocket",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Статус подключения и кнопки управления
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
                    text = "Статус подключения",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Индикатор состояния
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusColor = when (connectionState) {
                        WebSocketState.AUTHENTICATED -> MaterialTheme.colorScheme.primary
                        WebSocketState.CONNECTED -> MaterialTheme.colorScheme.secondary
                        WebSocketState.CONNECTING -> MaterialTheme.colorScheme.secondary
                        WebSocketState.DISCONNECTED -> MaterialTheme.colorScheme.error
                        WebSocketState.ERROR -> MaterialTheme.colorScheme.error
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(statusColor, androidx.compose.foundation.shape.CircleShape)
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = when (connectionState) {
                            WebSocketState.DISCONNECTED -> "Отключено"
                            WebSocketState.CONNECTING -> "Подключение..."
                            WebSocketState.CONNECTED -> "Подключено"
                            WebSocketState.AUTHENTICATED -> "Аутентифицирован"
                            WebSocketState.ERROR -> "Ошибка"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Информация о сервере
                Text(
                    text = "Сервер: ${selectedServer.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Text(
                    text = "IP: ${selectedServer.ipAddress}:${selectedServer.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Кнопки подключения/отключения
                Button(
                    onClick = {
                        when (connectionState) {
                            WebSocketState.DISCONNECTED, WebSocketState.ERROR -> {
                                CoroutineScope(Dispatchers.IO).launch {
                                    webSocketManager.connect(
                                        selectedServer.getWebSocketUrl(),
                                        selectedServer.password,
                                        clientName,
                                        encryptionKey // используем переданный ключ шифрования
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (connectionState) {
                        WebSocketState.DISCONNECTED, WebSocketState.ERROR -> {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Подключиться")
                        }
                        else -> {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Отключиться")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Информация о пользователе
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
                    text = "Информация о пользователе",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Имя в чате: $clientName",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Text(
                    text = "Статус: ${if (connectionState == WebSocketState.AUTHENTICATED) "В сети" else "Не в сети"}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}