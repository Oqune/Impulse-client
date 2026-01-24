package com.example.impulse.ui.screens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.impulse.data.ServerConfig
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState
import com.example.impulse.ui.components.WebSocketComponents

@Composable
fun TestWebSocketScreen(
    selectedServer: ServerConfig,
    modifier: Modifier = Modifier
) {
    var messageInput by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<String>>(emptyList()) }

    // Используем remember для сохранения WebSocketManager между recompositions
    val webSocketManager = remember(selectedServer) {
        WebSocketManager()
    }

    val connectionState by webSocketManager.currentState.collectAsState()

    LaunchedEffect(webSocketManager) {
        webSocketManager.onMessageReceived = { message ->
            messages = messages + "[${getCurrentTime()}] $message"
        }
    }

    // Логирование изменений состояния для отладки
    LaunchedEffect(connectionState) {
        Log.d("TestWebSocket", "Состояние подключения изменилось: $connectionState")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Тестирование WebSocket",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        WebSocketComponents.ServerInfoCard(selectedServer)

        Spacer(modifier = Modifier.height(16.dp))

        WebSocketComponents.ConnectionStatus(connectionState, getStatusMessage(connectionState))

        Spacer(modifier = Modifier.height(16.dp))

        WebSocketComponents.ConnectionControls(
            connectionState = connectionState,
            onConnect = {
                CoroutineScope(Dispatchers.IO).launch {
                    // Pass the password when connecting
                    webSocketManager.connect(selectedServer.getWebSocketUrl(), selectedServer.password)
                }
            },
            onDisconnect = {
                webSocketManager.disconnect()
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        WebSocketComponents.MessageInput(
            message = messageInput,
            onMessageChange = { messageInput = it },
            onSendMessage = {
                if (messageInput.isNotEmpty()) {
                    Log.d("TestWebSocket", "Отправка сообщения: '$messageInput'")
                    CoroutineScope(Dispatchers.IO).launch {
                        val success = webSocketManager.sendMessage(messageInput)
                        Log.d("TestWebSocket", "Результат отправки: $success")
                        if (success) {
                            withContext(Dispatchers.Main) {
                                messageInput = ""
                            }
                        }
                    }
                } else {
                    Log.w("TestWebSocket", "Попытка отправить пустое сообщение")
                }
            },
            // Enable message input when authenticated
            enabled = connectionState == WebSocketState.AUTHENTICATED
        )

        Spacer(modifier = Modifier.height(16.dp))

        WebSocketComponents.MessageLog(messages)
    }
}

private fun getStatusMessage(state: WebSocketState): String {
    return when (state) {
        WebSocketState.DISCONNECTED -> "Не подключено"
        WebSocketState.CONNECTING -> "Подключение..."
        WebSocketState.CONNECTED -> "Подключено"
        WebSocketState.AUTHENTICATED -> "Аутентифицирован"
        WebSocketState.ERROR -> "Ошибка подключения"
    }
}

private fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        .format(Date())
}