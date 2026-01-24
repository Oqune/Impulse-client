package com.example.impulse.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import com.example.impulse.websocket.WebSocketState

object WebSocketComponents {

    @Composable
    fun ServerInfoCard(server: ServerConfig) {
        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Сервер подключения",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    if (server.password.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Требуется пароль",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = server.getWebSocketUrl(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "${server.ipAddress}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (server.password.isNotEmpty()) {
                    Text(
                        text = "Пароль: ********",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    @Composable
    fun ConnectionStatus(state: WebSocketState, status: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            when (state) {
                WebSocketState.DISCONNECTED -> {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Отключено",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                WebSocketState.CONNECTING -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
                WebSocketState.CONNECTED -> {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Подключено",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                WebSocketState.AUTHENTICATED -> {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Аутентифицирован",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                WebSocketState.ERROR -> {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Ошибка",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = when (state) {
                        WebSocketState.DISCONNECTED -> "Отключено"
                        WebSocketState.CONNECTING -> "Подключение..."
                        WebSocketState.CONNECTED -> "Подключено"
                        WebSocketState.AUTHENTICATED -> "Аутентифицирован"
                        WebSocketState.ERROR -> "Ошибка"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = when (state) {
                        WebSocketState.AUTHENTICATED, WebSocketState.CONNECTED -> MaterialTheme.colorScheme.primary
                        WebSocketState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    fun ConnectionControls(
        connectionState: WebSocketState,
        onConnect: () -> Unit,
        onDisconnect: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = onConnect,
                enabled = connectionState == WebSocketState.DISCONNECTED ||
                         connectionState == WebSocketState.ERROR,
                modifier = Modifier.weight(1f)
            ) {
                Text("Подключиться")
            }

            Spacer(modifier = Modifier.width(16.dp))

            Button(
                onClick = onDisconnect,
                enabled = connectionState == WebSocketState.CONNECTED ||
                        connectionState == WebSocketState.AUTHENTICATED,
                modifier = Modifier.weight(1f)
            ) {
                Text("Отключиться")
            }
        }
    }

    @Composable
    fun MessageInput(
        message: String,
        onMessageChange: (String) -> Unit,
        onSendMessage: () -> Unit,
        enabled: Boolean
    ) {
        Column {
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Сообщение") },
                placeholder = { Text("Введите сообщение для отправки...") },
                enabled = enabled,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onSendMessage,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && message.isNotBlank()
            ) {
                Text("Отправить сообщение")
            }
        }
    }

    @Composable
    fun MessageLog(messages: List<String>) {
        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                Text(
                    text = "Лог сообщений",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )

                // Исправлено: заменено Divider() на горизонтальный Divider из material3
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (messages.isEmpty()) {
                        Text(
                            text = "Сообщения появятся здесь после подключения",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        messages.forEach { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}