package com.example.impulse.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.impulse.data.ServerConfig
import com.example.impulse.util.LogStorage
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.compose.material3.FloatingActionButtonDefaults
import kotlin.math.abs


// ============================================================================
// DATA MODELS
// ============================================================================

enum class MessageType {
    INFO, CONTENT, SYSTEM, TECHNICAL, ERROR
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val content: String,
    val timestamp: String = getCurrentTime(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val isOwn: Boolean = false,
    val isFullWidth: Boolean = false,
    val messageType: MessageType = MessageType.CONTENT,
    val senderId: Int = 0
)

/**
 * Per-server chat history storage.
 * Each server has its own isolated message history.
 */
object ChatHistoryManager {
    private val histories = mutableMapOf<String, MutableList<ChatMessage>>()
    private val locks = mutableMapOf<String, Any>()
    private const val MAX_MESSAGES = 1000
    // Wider window: the server may echo our own message back a moment later,
    // or re-send recent history after a reconnect. 10s covers that safely.
    private const val DEDUP_TIME_WINDOW_MS = 10000L

    private fun getLock(serverId: String): Any =
        locks.getOrPut(serverId) { Any() }

    private fun getHistory(serverId: String): MutableList<ChatMessage> =
        histories.getOrPut(serverId) { mutableListOf() }

    fun getMessages(serverId: String): List<ChatMessage> =
        synchronized(getLock(serverId)) { getHistory(serverId).toList() }

    /**
     * Builds a STABLE id for a message from server-provided fields.
     * The default ChatMessage.id is a random UUID generated at parse time, so it
     * can't be used for dedup. We derive a deterministic key from sender + content
     * (+ a coarse 1s time bucket) so the same logical message always collides,
     * even across reconnects or when the server echoes our own message back
     * (where the `isOwn` flag differs between the local send and the echo).
     */
    private fun stableKey(message: ChatMessage): String {
        // Timestamp-independent key: the server echoes our own message back with a
        // DIFFERENT timestamp, so including time caused the echo to slip past the
        // dedup and appear as a duplicate. senderId + content is stable across the
        // local send and the server echo (same senderId, same content), so it
        // reliably collapses both into one entry.
        return "${message.senderId}::${message.content.hashCode()}"
    }

    /**
     * Adds a message to the per-server history.
     * @return true if the message was actually inserted (not a duplicate),
     *         false if it was dropped as a duplicate.
     */
    fun addMessage(serverId: String, message: ChatMessage): Boolean {
        synchronized(getLock(serverId)) {
            val history = getHistory(serverId)
            val key = stableKey(message)

            // 1. Deduplication by stable key (sender + content + 1s bucket).
            // This reliably catches the own-message echo and reconnect re-sends,
            // regardless of the random UUID and the flipped isOwn flag.
            if (history.any { stableKey(it) == key }) {
                return false
            }

            // 2. Fallback content-based dedup within the time window, ignoring
            // isOwn (the echo arrives with isOwn flipped) and using senderId
            // (more reliable than the display name).
            val isDuplicate = history.any { existing ->
                existing.senderId == message.senderId &&
                existing.content == message.content &&
                existing.messageType == message.messageType &&
                abs(existing.timestampMillis - message.timestampMillis) < DEDUP_TIME_WINDOW_MS
            }
            if (isDuplicate) {
                return false
            }

            // 3. Maintain max size
            if (history.size >= MAX_MESSAGES) {
                history.removeAt(0)
            }
            history.add(message)
            return true
        }
    }

    fun clear(serverId: String) = synchronized(getLock(serverId)) { getHistory(serverId).clear() }

    fun clearAll() = synchronized(locks) {
        histories.values.forEach { it.clear() }
    }
}

// ============================================================================
// MESSAGE PARSER
// ============================================================================

object MessageParser {
    fun parse(rawMessage: String, isFullWidth: Boolean = false): ChatMessage {
        return try {
            val json = JSONObject(rawMessage)
            val msgType = json.optString("type", "").lowercase()
            val payload = json.optJSONObject("payload")

            when (msgType) {
                "informational", "info" -> parseInformational(payload, rawMessage, isFullWidth)
                "content" -> parseContent(payload, rawMessage)
                "system" -> parseSystem(payload, rawMessage)
                "technical" -> parseTechnical(payload, rawMessage)
                "error" -> parseError(payload, rawMessage)
                else -> parseUnknown(rawMessage, isFullWidth)
            }
        } catch (e: Exception) {
            parseFallback(rawMessage, isFullWidth)
        }
    }

    private fun parseInformational(
        payload: JSONObject?,
        rawMessage: String,
        isFullWidth: Boolean
    ): ChatMessage {
        var content = ""
        if (payload != null) {
            content = payload.optString("content", "")
            if (content.isEmpty()) {
                val event = payload.optString("event", "")
                val userName =
                    payload.optString("user_name", payload.optString("username", "Пользователь"))
                content = when (event) {
                    "joined" -> "Пользователь $userName присоединился к чату"
                    "left" -> "Пользователь $userName покинул чат"
                    else -> "Пользователь $userName $event"
                }
            }
        } else {
            content = rawMessage
        }
        return ChatMessage(
            sender = "",
            content = content,
            isOwn = false,
            isFullWidth = true,
            messageType = MessageType.INFO
        )
    }

    private fun parseContent(payload: JSONObject?, rawMessage: String): ChatMessage {
        val senderName = payload?.optString("sender_name")
            ?: payload?.optString("user_name")
            ?: "Неизвестный"
        val senderId = payload?.optInt("sender_id", 0) ?: 0
        val content = payload?.optString("content")
            ?: payload?.optString("message")
            ?: rawMessage
        val isOwn = payload?.optBoolean("is_own", false) ?: false
        return ChatMessage(
            sender = senderName,
            content = content,
            isOwn = isOwn,
            isFullWidth = false,
            messageType = MessageType.CONTENT,
            senderId = senderId
        )
    }

    private fun parseSystem(payload: JSONObject?, rawMessage: String): ChatMessage {
        val content = payload?.optString("content", rawMessage) ?: rawMessage
        return ChatMessage(
            sender = "Система",
            content = content,
            isOwn = false,
            isFullWidth = false,
            messageType = MessageType.SYSTEM
        )
    }

    private fun parseTechnical(payload: JSONObject?, rawMessage: String): ChatMessage {
        val content = payload?.optString("content", rawMessage) ?: rawMessage
        return ChatMessage(
            sender = "Техническое",
            content = content,
            isOwn = false,
            isFullWidth = false,
            messageType = MessageType.TECHNICAL
        )
    }

    private fun parseError(payload: JSONObject?, rawMessage: String): ChatMessage {
        val code = payload?.optInt("code", 0) ?: 0
        val message = payload?.optString("message", "") ?: rawMessage
        val content = "Ошибка сервера ($code): $message"
        return ChatMessage(
            sender = "Ошибка",
            content = content,
            isOwn = false,
            isFullWidth = false,
            messageType = MessageType.ERROR
        )
    }

    private fun parseUnknown(rawMessage: String, isFullWidth: Boolean): ChatMessage {
        return ChatMessage(
            sender = if (isFullWidth) "" else "Система",
            content = rawMessage,
            isOwn = false,
            isFullWidth = isFullWidth,
            messageType = if (isFullWidth) MessageType.INFO else MessageType.SYSTEM
        )
    }

    private fun parseFallback(rawMessage: String, isFullWidth: Boolean): ChatMessage {
        val regex = "\\[(.*?)\\]\\s*(.*)".toRegex()
        val match = regex.find(rawMessage)
        return if (match != null) {
            val sender = match.groupValues[1]
            val content = match.groupValues[2]
            ChatMessage(
                sender = sender,
                content = content,
                isOwn = false,
                isFullWidth = isFullWidth,
                messageType = MessageType.CONTENT
            )
        } else {
            ChatMessage(
                sender = if (isFullWidth) "" else "Система",
                content = rawMessage,
                isOwn = false,
                isFullWidth = isFullWidth,
                messageType = if (isFullWidth) MessageType.INFO else MessageType.SYSTEM
            )
        }
    }
}

fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

// ============================================================================
// COMPOSABLE COMPONENTS
// ============================================================================

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val isOwn = message.isOwn
    val messageType = message.messageType

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = getMessageBackgroundColor(messageType, isOwn, message.sender),
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isOwn) 18.dp else 4.dp,
                        bottomEnd = if (isOwn) 4.dp else 18.dp
                    )
                )
        ) {
            Column(modifier = Modifier.padding(12.dp, 10.dp)) {
                // Sender name: shown for incoming messages only, with a strong
                // colored accent so different people are easy to tell apart.
                if (message.sender.isNotEmpty() && !isOwn) {
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = getSenderColor(messageType, isOwn, message.sender),
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }

                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = getContentColor(messageType, isOwn, message.sender)
                )

                Text(
                    text = message.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = getTimestampColor(messageType, isOwn, message.sender),
                    modifier = Modifier
                        .align(if (isOwn) Alignment.End else Alignment.Start)
                        .padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
fun FullWidthInfoMessage(message: ChatMessage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun ConnectionStatusIndicator(connectionState: WebSocketState) {
    val statusData = when (connectionState) {
        WebSocketState.DISCONNECTED ->
            Triple("Отключено", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceContainerHighest)
        WebSocketState.CONNECTING ->
            Triple("Подключение...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        WebSocketState.CONNECTED ->
            Triple("Аутентификация...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        WebSocketState.AUTHENTICATED ->
            Triple("Подключено", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        WebSocketState.ERROR ->
            Triple("Ошибка", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
    }
    val (statusText, statusColor, backgroundColor) = statusData

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
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(
                    color = backgroundColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun MessageInputArea(
    messageInput: String,
    onMessageChange: (String) -> Unit,
    onSendClick: () -> Unit,
    connectionState: WebSocketState,
    clientName: String,
    canSendMessages: Boolean
) {
    val canSend = canSendMessages && connectionState == WebSocketState.AUTHENTICATED && messageInput.isNotBlank()
    val isConnected = connectionState == WebSocketState.AUTHENTICATED

    // The Surface sits at the very bottom of the visible area. navigationBarsPadding()
    // keeps it clear of the system gesture / nav bar, and the extra bottom padding
    // leaves a clear gap between the bar and the top of the soft keyboard when it
    // is open (the whole column is lifted by imePadding() on the root).
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = messageInput,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                placeholder = {
                    Text(
                        text = if (isConnected) "Сообщение..." else "Нет подключения",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                enabled = isConnected,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )

            FloatingActionButton(
                onClick = onSendClick,
                modifier = Modifier.size(46.dp),
                containerColor = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Отправить",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun BoxScope.ScrollToBottomButton(
    showButton: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (showButton) {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier
                .padding(end = 16.dp, bottom = 88.dp)
                .size(48.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Прокрутить вниз",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ============================================================================
// COLOR HELPERS (composable - read from MaterialTheme)
// ============================================================================

@Composable
private fun getMessageBackgroundColor(messageType: MessageType, isOwn: Boolean, sender: String): Color {
    return when {
        isOwn -> MaterialTheme.colorScheme.primaryContainer
        messageType == MessageType.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
        messageType == MessageType.ERROR -> MaterialTheme.colorScheme.errorContainer
        messageType == MessageType.TECHNICAL -> MaterialTheme.colorScheme.tertiaryContainer
        messageType == MessageType.INFO -> MaterialTheme.colorScheme.surfaceContainerHigh
        sender == "Система" || sender == "Сервер" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
}

@Composable
private fun getSenderColor(messageType: MessageType, isOwn: Boolean, sender: String): Color {
    // Incoming sender names use the accent (primary) so each person's
    // name is clearly distinguishable from the message body.
    return when {
        isOwn -> MaterialTheme.colorScheme.onPrimaryContainer
        messageType == MessageType.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
        messageType == MessageType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        sender == "Система" || sender == "Сервер" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun getContentColor(messageType: MessageType, isOwn: Boolean, sender: String): Color {
    return when {
        isOwn -> MaterialTheme.colorScheme.onPrimaryContainer
        messageType == MessageType.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
        messageType == MessageType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        sender == "Система" || sender == "Сервер" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
}

@Composable
private fun getTimestampColor(messageType: MessageType, isOwn: Boolean, sender: String): Color {
    val baseColor = when {
        isOwn -> MaterialTheme.colorScheme.onPrimaryContainer
        messageType == MessageType.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
        messageType == MessageType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        sender == "Система" || sender == "Сервер" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    return baseColor.copy(alpha = 0.7f)
}

// ============================================================================
// MAIN CHAT SCREEN
// ============================================================================

@Composable
fun ChatScreen(
    selectedServer: ServerConfig,
    modifier: Modifier = Modifier,
    clientName: String
) {
    var messageInput by remember { mutableStateOf("") }
    val messages = remember(selectedServer.id) {
        mutableStateListOf<ChatMessage>().apply {
            // Only show real chat content. System/event/auth/technical/error
            // notifications must NOT appear in the conversation.
            addAll(
                ChatHistoryManager.getMessages(selectedServer.id)
                    .filter { it.messageType == MessageType.CONTENT }
            )
        }
    }
    val listState = rememberLazyListState()
    var showScrollButton by remember { mutableStateOf(false) }

    val webSocketManager = WebSocketManager.getInstance()
    val connectionState by webSocketManager.currentState.collectAsState()
    val canSendMessages = webSocketManager.canSendMessages()
    val scope = rememberCoroutineScope()

    // Track whether the user is at (or near) the bottom of the list so we only
    // auto-scroll when appropriate.
    val isAtBottom by remember {
        derivedStateOf {
            val lastIndex = messages.lastIndex
            lastIndex < 0 || listState.firstVisibleItemIndex >= lastIndex - 1
        }
    }

    // Set up message callback with proper cleanup using DisposableEffect.
    // Register as UI listener (last in list) to receive messages for UI display.
    DisposableEffect(selectedServer.id, webSocketManager) {
        val currentServerId = selectedServer.id
        
        // Message handler for UI.
        // NOTE: WebSocketManager now ALWAYS persists incoming messages into
        // ChatHistoryManager (for the current server) inside notifyMessageListeners,
        // even when this screen is not mounted. So we must NOT call
        // ChatHistoryManager.addMessage here (that would duplicate). We only
        // mirror the already-stored message into this screen's visible list.
        val messageHandler = { message: String, isFullWidth: Boolean ->
            Log.d("ChatScreen", "Обработка сообщения для сервера $currentServerId: $message")

            val chatMessage = MessageParser.parse(message, false)

            // Only real chat content is shown. System/event/auth/technical/error
            // notifications must NOT appear in the conversation.
            if (chatMessage.messageType == MessageType.CONTENT) {
                scope.launch(Dispatchers.Main) {
                    // WebSocketManager already persisted this into ChatHistoryManager
                    // (deduped by senderId + content). Here we only mirror it into
                    // the visible list. Use the SAME timestamp-independent key so the
                    // server's echo of our own message (different timestamp) is
                    // recognized as already shown.
                    val incomingKey = "${chatMessage.senderId}::${chatMessage.content.hashCode()}"
                    val alreadyShown = messages.any { m ->
                        "${m.senderId}::${m.content.hashCode()}" == incomingKey
                    }
                    if (!alreadyShown) {
                        messages.add(chatMessage)
                    }
                }
            }
        }

        // NOTE: We intentionally do NOT clear chat history on reconnect anymore.
        // Clearing on every transient drop was wiping the user's conversation and
        // made the connection feel unstable. History is preserved across reconnects;
        // the server re-sends only recent messages, which are de-duplicated by
        // ChatHistoryManager. If a true "session reset" is ever needed, the
        // server should send an explicit message type (e.g. "session_reset")
        // that the UI can act on deliberately.

        // Register as UI listener using public API
        webSocketManager.addMessageListener(messageHandler)

        // Cleanup on dispose or when server changes.
        onDispose {
            webSocketManager.removeMessageListener(messageHandler)
        }
    }

    // Auto-scroll to bottom when new messages arrive (only if user is at bottom).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    // Show/hide scroll-to-bottom button: only when the user has scrolled up
    // AND the list actually has content to scroll (nothing to scroll -> hidden).
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastIndex = messages.lastIndex
            val atBottom = lastIndex < 0 || listState.firstVisibleItemIndex >= lastIndex - 1
            !atBottom && listState.canScrollForward
        }.collect { visible ->
            showScrollButton = visible
        }
    }

    val onSendClick: () -> Unit = {
        if (messageInput.isNotBlank()) {
            val textToSend = messageInput
            messageInput = ""
            Log.d("ChatScreen", "Отправка: '$textToSend'")
            
            scope.launch(Dispatchers.IO) {
                val success = webSocketManager.sendMessage(textToSend)
                if (success) {
                    val sentMessage = ChatMessage(
                        sender = clientName,
                        content = textToSend,
                        isOwn = true,
                        isFullWidth = false,
                        messageType = MessageType.CONTENT,
                        senderId = webSocketManager.getClientId()
                    )
                    withContext(Dispatchers.Main) {
                        ChatHistoryManager.addMessage(selectedServer.id, sentMessage)
                        messages.add(sentMessage)
                    }
                } else {
                    // Send failures are logged, but not shown as chat messages.
                    LogStorage.addLog("Не удалось отправить сообщение: $textToSend")
                }
            }
        }
    }

    val onScrollToBottom: () -> Unit = {
        scope.launch {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    DecorativeBackground(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Lift the whole conversation + input together when the
                    // keyboard appears, so the input bar stays pinned to the
                    // bottom and never "flies" above the chat.
                    .imePadding()
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages) { message ->
                        if (message.isFullWidth) {
                            FullWidthInfoMessage(message)
                        } else {
                            ChatMessageItem(message)
                        }
                    }

                    item {
                        ConnectionStatusIndicator(connectionState)
                    }
                }

                MessageInputArea(
                    messageInput = messageInput,
                    onMessageChange = { messageInput = it },
                    onSendClick = onSendClick,
                    connectionState = connectionState,
                    clientName = clientName,
                    canSendMessages = canSendMessages
                )
            }
        }

        ScrollToBottomButton(
            showButton = showScrollButton,
            onClick = onScrollToBottom,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .imePadding()
        )
    }
}
