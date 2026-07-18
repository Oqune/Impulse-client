package com.example.impulse.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.impulse.ChatController
import com.example.impulse.data.MessageRepository
import com.example.impulse.data.ServerConfig
import com.example.impulse.transport.ConnectionState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// DATA MODELS
// ============================================================================

enum class MessageType {
    INFO, CONTENT, SYSTEM, TECHNICAL, ERROR
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String,
    val content: String,
    val timestamp: String = getCurrentTime(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val isOwn: Boolean = false,
    val isFullWidth: Boolean = false,
    val messageType: MessageType = MessageType.CONTENT,
    val senderId: Int = 0
)

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
fun ConnectionStatusIndicator(connectionState: ConnectionState) {
    val statusData = when (connectionState) {
        ConnectionState.DISCONNECTED ->
            Triple("Отключено", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceContainerHighest)
        ConnectionState.CONNECTING ->
            Triple("Подключение...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        ConnectionState.CONNECTED ->
            Triple("Аутентификация...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        ConnectionState.AUTHENTICATING ->
            Triple("Аутентификация...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        ConnectionState.AUTHENTICATED ->
            Triple("Установка защищённого канала...", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        ConnectionState.READY ->
            Triple("Подключено (PQ-E2EE)", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        ConnectionState.ERROR ->
            Triple("Ошибка / нужен QR", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
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
    connectionState: ConnectionState,
    canSendMessages: Boolean
) {
    val canSend = canSendMessages && connectionState == ConnectionState.READY && messageInput.isNotBlank()
    val isConnected = connectionState == ConnectionState.READY

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
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )

            FloatingActionButton(
                onClick = onSendClick,
                modifier = Modifier.size(46.dp),
                containerColor = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
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
// COLOR HELPERS
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
    clientName: String,
    modifier: Modifier = Modifier
) {
    var messageInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showScrollButton by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val chatController = remember { ChatController.getInstance(context) }
    val repo = remember { MessageRepository(context) }
    val viewModel: com.example.impulse.ui.ChatViewModel = viewModel(
        factory = com.example.impulse.ui.ChatViewModelFactory(chatController, repo, selectedServer)
    )
    val connectionState by viewModel.connectionState.collectAsState()
    val decrypted by viewModel.messages.collectAsState()
    val scope = rememberCoroutineScope()

    // Map decrypted messages to the display model.
    val messages = remember(decrypted) {
        decrypted.map { dm ->
            ChatMessage(
                sender = dm.sender,
                content = dm.plaintext,
                isOwn = dm.isOwn,
                timestampMillis = dm.timestamp,
                messageType = MessageType.CONTENT
            )
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val lastIndex = messages.lastIndex
            lastIndex < 0 || listState.firstVisibleItemIndex >= lastIndex - 1
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val lastIndex = messages.lastIndex
            val atBottom = lastIndex < 0 || listState.firstVisibleItemIndex >= lastIndex - 1
            !atBottom && listState.canScrollForward
        }.collect { visible -> showScrollButton = visible }
    }

    val onSendClick: () -> Unit = {
        if (messageInput.isNotBlank()) {
            val textToSend = messageInput
            messageInput = ""
            // Optimistic local echo; the authoritative row arrives via the
            // reactive store (upsert keyed by server id) and replaces this copy.
            viewModel.send(textToSend)
        }
    }

    val onScrollToBottom: () -> Unit = {
        scope.launch { listState.animateScrollToItem(messages.lastIndex) }
    }

    DecorativeBackground(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                    items(messages, key = { it.id }) { message ->
                        androidx.compose.animation.AnimatedVisibility(
                            visible = true,
                            enter = androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(200)
                            ) + androidx.compose.animation.slideInVertically(
                                initialOffsetY = { it / 4 }
                            ),
                            exit = androidx.compose.animation.fadeOut()
                        ) {
                            if (message.isFullWidth) FullWidthInfoMessage(message)
                            else ChatMessageItem(message)
                        }
                    }
                    item { ConnectionStatusIndicator(connectionState) }
                }

                MessageInputArea(
                    messageInput = messageInput,
                    onMessageChange = { messageInput = it },
                    onSendClick = onSendClick,
                    connectionState = connectionState,
                    canSendMessages = connectionState == ConnectionState.READY
                )
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
}
