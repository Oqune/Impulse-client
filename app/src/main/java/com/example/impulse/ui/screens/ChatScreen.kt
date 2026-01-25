package com.example.impulse.ui.screens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.impulse.data.ServerConfig
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Глобальное хранилище истории чата
object ChatHistory {
    private val history = mutableStateListOf<ChatMessage>()

    fun getMessages(): List<ChatMessage> = history.toList()
    fun addMessage(message: ChatMessage) = history.add(message)
    fun clear() = history.clear()
}

@Composable
fun ChatScreen(
    selectedServer: ServerConfig,
    modifier: Modifier = Modifier,
    clientName: String
) {
    var messageInput by remember { mutableStateOf("") }
    val messages = ChatHistory.getMessages()

    val webSocketManager = WebSocketManager.getInstance()
    val connectionState by webSocketManager.currentState.collectAsState()

    LaunchedEffect(webSocketManager) {
        webSocketManager.onMessageReceived = { message ->
            Log.d("ChatScreen", "Обработка сообщения: $message")

            // Парсим сообщение
            val chatMessage = parseMessage(message)
            ChatHistory.addMessage(chatMessage)
        }
    }

    // Автопрокрутка к новым сообщениям
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Обработка клавиатуры
    val view = LocalView.current
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            // Обработка клавиатуры
        }

        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
    ) {
        // Область сообщений
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            reverseLayout = false,
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { message ->
                ChatMessageItem(message)
            }

            // Показываем индикатор подключения
            item {
                ConnectionStatusIndicator(connectionState)
            }
        }

        // Поле ввода сообщения
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageInput,
                onValueChange = { messageInput = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                placeholder = { Text("Сообщение...", fontSize = 14.sp) },
                enabled = connectionState == WebSocketState.AUTHENTICATED,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            IconButton(
                onClick = {
                    if (messageInput.isNotEmpty()) {
                        Log.d("ChatScreen", "Отправка: '$messageInput'")
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = webSocketManager.sendMessage(messageInput)
                            if (success) {
                                // Добавляем свое сообщение в чат
                                val ownMessage = ChatMessage(
                                    sender = "Вы",
                                    content = messageInput,
                                    timestamp = getCurrentTime(),
                                    isOwn = true
                                )
                                ChatHistory.addMessage(ownMessage)

                                withContext(Dispatchers.Main) {
                                    messageInput = ""
                                }
                            }
                        }
                    }
                },
                enabled = connectionState == WebSocketState.AUTHENTICATED && messageInput.isNotEmpty(),
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (connectionState == WebSocketState.AUTHENTICATED && messageInput.isNotEmpty())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Отправить",
                    tint = if (connectionState == WebSocketState.AUTHENTICATED && messageInput.isNotEmpty())
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChatMessageItem(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 8.dp),
        horizontalArrangement = if (message.isOwn) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .background(
                    color = if (message.isOwn)
                        MaterialTheme.colorScheme.primaryContainer
                    else if (message.sender == "Система" || message.sender == "Сервер")
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (message.isOwn) 12.dp else 4.dp,
                        bottomEnd = if (message.isOwn) 4.dp else 12.dp
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                if (message.sender.isNotEmpty() && message.sender != "Вы") {
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (message.isOwn)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else if (message.sender == "Система" || message.sender == "Сервер")
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isOwn)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else if (message.sender == "Система" || message.sender == "Сервер")
                        MaterialTheme.colorScheme.onTertiaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )

                Text(
                    text = message.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isOwn)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else if (message.sender == "Система" || message.sender == "Сервер")
                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(if (message.isOwn) Alignment.End else Alignment.Start)
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusIndicator(connectionState: WebSocketState) {
    val statusText = when (connectionState) {
        WebSocketState.DISCONNECTED -> "Отключено"
        WebSocketState.CONNECTING -> "Подключение..."
        WebSocketState.CONNECTED -> "Подключено"
        WebSocketState.AUTHENTICATED -> "Готов к чату"
        WebSocketState.ERROR -> "Ошибка"
    }

    val statusColor = when (connectionState) {
        WebSocketState.AUTHENTICATED -> MaterialTheme.colorScheme.primary
        WebSocketState.CONNECTED -> MaterialTheme.colorScheme.secondary
        WebSocketState.CONNECTING -> MaterialTheme.colorScheme.secondary
        WebSocketState.DISCONNECTED -> MaterialTheme.colorScheme.error
        WebSocketState.ERROR -> MaterialTheme.colorScheme.error
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
            modifier = Modifier
                .background(
                    color = statusColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

data class ChatMessage(
    val sender: String,
    val content: String,
    val timestamp: String,
    val isOwn: Boolean = false
)

private fun parseMessage(rawMessage: String): ChatMessage {
    try {
        // Пытаемся распарсить как JSON
        val json = JSONObject(rawMessage)
        val senderName = json.optString("sender_name", "Система")
        val content = json.optString("content", rawMessage)

        return ChatMessage(
            sender = senderName,
            content = content,
            timestamp = getCurrentTime(),
            isOwn = false
        )
    } catch (e: Exception) {
        // Не JSON - обычное сообщение
        // Проверяем формат [имя] сообщение
        val regex = Regex("\\[(.*?)\\]\\s*(.*)")
        val match = regex.find(rawMessage)

        return if (match != null) {
            val sender = match.groupValues[1]
            val content = match.groupValues[2]
            ChatMessage(
                sender = sender,
                content = content,
                timestamp = getCurrentTime(),
                isOwn = false
            )
        } else {
            // Простое сообщение
            ChatMessage(
                sender = "Система",
                content = rawMessage,
                timestamp = getCurrentTime(),
                isOwn = false
            )
        }
    }
}

private fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(Date())
}