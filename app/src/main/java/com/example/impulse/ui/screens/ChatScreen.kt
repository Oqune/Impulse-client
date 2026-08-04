package com.example.impulse.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
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
import com.example.impulse.data.MessageRepository
import com.example.impulse.data.ServerConfig
import com.example.impulse.transport.ConnectionState
import com.example.impulse.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.impulse.R
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
    val senderFingerprint: String = "",
    val content: String,
    val timestamp: String = formatTimestamp(System.currentTimeMillis()),
    val timestampMillis: Long = System.currentTimeMillis(),
    val isOwn: Boolean = false,
    val isFullWidth: Boolean = false,
    val messageType: MessageType = MessageType.CONTENT,
    val senderId: Int = 0
)

fun formatTimestamp(millis: Long): String {
    return if (millis > 0) {
        "[${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))}]"
    } else {
        "[--:--]"
    }
}

// ============================================================================
// COMPOSABLE COMPONENTS
// ============================================================================

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val isOwn = message.isOwn
    val messageType = message.messageType

    // Constructive scheme: the bubble hugs its content (adaptive width up to
    // 78% of the list) instead of stretching full-width, and the timestamp
    // sits in the bubble's outer bottom corner — bottom-right for own,
    // bottom-left for others.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        val bubbleMaxWidth = maxWidth * 0.78f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth)
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
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    if (message.sender.isNotEmpty() && !isOwn) {
                        Text(
                            text = message.sender,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = getSenderColor(messageType, isOwn, message.sender).copy(alpha = 0.85f),
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = getContentColor(messageType, isOwn, message.sender)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = message.timestamp.removeSurrounding("[", "]"),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = getTimestampColor(messageType, isOwn, message.sender),
                        modifier = Modifier.align(if (isOwn) Alignment.End else Alignment.Start)
                    )
                }
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
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun ConnectionStatusIndicator(connectionState: ConnectionState) {
    val statusText = when (connectionState) {
        ConnectionState.DISCONNECTED -> stringResource(R.string.status_offline)
        ConnectionState.CONNECTING -> stringResource(R.string.status_connecting)
        ConnectionState.CONNECTED -> stringResource(R.string.status_auth)
        ConnectionState.AUTHENTICATING -> stringResource(R.string.status_auth)
        ConnectionState.AUTHENTICATED -> stringResource(R.string.status_establishing)
        ConnectionState.READY -> stringResource(R.string.status_online)
        ConnectionState.ERROR -> stringResource(R.string.status_error)
    }
    val statusColor = when (connectionState) {
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val backgroundColor = when (connectionState) {
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.errorContainer
        ConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
        ConnectionState.READY -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val statusData = Triple(statusText, statusColor, backgroundColor)

    val animatedBgColor by animateColorAsState(
        targetValue = backgroundColor.copy(alpha = 0.2f),
        animationSpec = tween(300),
        label = "status_bg"
    )
    val animatedTextColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(300),
        label = "status_text"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = statusText,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)))
                    .togetherWith(
                    fadeOut(tween(150)) + scaleOut(targetScale = 0.9f, animationSpec = tween(150))
                    )
            },
            label = "status_text_anim"
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = animatedTextColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(
                        color = animatedBgColor,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
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

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .navigationBarsPadding()
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(26.dp),
        alpha = 0.85f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Borderless field: no inner container box, no underline — it
            // melts into the glass pill (Bug: "layered surfaceContainer box
            // inside the glass input looked clunky").
            TextField(
                value = messageInput,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp),
                placeholder = {
                    Text(
                        text = if (isConnected) stringResource(R.string.chat_placeholder_message) else stringResource(R.string.chat_placeholder_no_connection),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                enabled = isConnected,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )

            // Circular send button: primary when sendable, muted otherwise,
            // no shadow.
            val sendBg by animateColorAsState(
                targetValue = if (canSend) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                label = "send_bg"
            )
            val sendFg by animateColorAsState(
                targetValue = if (canSend) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                label = "send_fg"
            )
            FloatingActionButton(
                onClick = onSendClick,
                modifier = Modifier.size(40.dp),
                containerColor = sendBg,
                contentColor = sendFg,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
                    modifier = Modifier.size(20.dp)
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
    AnimatedVisibility(
        visible = showButton,
        enter = scaleIn(tween(200)) + fadeIn(tween(200)),
        exit = scaleOut(tween(150)) + fadeOut(tween(150)),
        modifier = modifier
            .padding(end = 16.dp, bottom = 88.dp)
            .size(48.dp)
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_scroll_down),
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
        isOwn -> MaterialTheme.colorScheme.primary
        messageType == MessageType.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
        messageType == MessageType.ERROR -> MaterialTheme.colorScheme.errorContainer
        messageType == MessageType.TECHNICAL -> MaterialTheme.colorScheme.tertiaryContainer
        messageType == MessageType.INFO -> MaterialTheme.colorScheme.surfaceContainerHigh
        sender == "Система" || sender == "Сервер" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
}

@Composable
private fun getSenderColor(messageType: MessageType, isOwn: Boolean, sender: String): Color {
    return when {
        isOwn -> MaterialTheme.colorScheme.onPrimary
        messageType == MessageType.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
        messageType == MessageType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        messageType == MessageType.TECHNICAL -> MaterialTheme.colorScheme.onTertiaryContainer
        messageType == MessageType.INFO -> MaterialTheme.colorScheme.primary
        sender == "Система" || sender == "Сервер" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun getContentColor(messageType: MessageType, isOwn: Boolean, sender: String): Color {
    return when {
        isOwn -> MaterialTheme.colorScheme.onPrimary
        messageType == MessageType.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
        messageType == MessageType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        messageType == MessageType.TECHNICAL -> MaterialTheme.colorScheme.onTertiaryContainer
        messageType == MessageType.INFO -> MaterialTheme.colorScheme.onSurface
        sender == "Система" || sender == "Сервер" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun getTimestampColor(messageType: MessageType, isOwn: Boolean, sender: String): Color {
    val baseColor = when {
        isOwn -> MaterialTheme.colorScheme.onPrimary
        messageType == MessageType.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
        messageType == MessageType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        messageType == MessageType.TECHNICAL -> MaterialTheme.colorScheme.onTertiaryContainer
        messageType == MessageType.INFO -> MaterialTheme.colorScheme.onSurface
        sender == "Система" || sender == "Сервер" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
    connectionManager: com.example.impulse.ConnectionManager,
    conversationId: String = "group",
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var messageInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showScrollButton by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val chatController = remember(selectedServer.id) {
        connectionManager.getController(selectedServer)
    }
    val repo = remember { MessageRepository(context) }
    // Display title for a DM: the peer's name once known, else a short id.
    var conversationTitle by remember { mutableStateOf<String?>(null) }
    val savedTitle = stringResource(R.string.chat_saved_title)
    LaunchedEffect(conversationId) {
        if (conversationId != "group") {
            val fp = conversationId.removePrefix("dm:")
            val own = chatController.ownFingerprint()
            if (own.isNotEmpty() && fp == own) {
                conversationTitle = savedTitle
            } else {
                conversationTitle = chatController.peerDisplayName(selectedServer.id, fp)
            }
        }
    }
    // Key the ViewModel by server + conversation so DMs are isolated from the
    // group feed (Bug: "incoming DMs showed in the group chat").
    val viewModel: com.example.impulse.ui.ChatViewModel = viewModel(
        key = "${selectedServer.id}:$conversationId",
        factory = com.example.impulse.ui.ChatViewModelFactory(chatController, repo, selectedServer, conversationId)
    )
    val connectionState by viewModel.connectionState.collectAsState()
    val decrypted by viewModel.messages.collectAsState()
    val scope = rememberCoroutineScope()

    val messages = remember(decrypted) {
        decrypted.map { dm ->
            ChatMessage(
                id = dm.serverMsgId.toString(),
                sender = dm.sender,
                senderFingerprint = dm.senderFingerprint,
                content = dm.plaintext,
                isOwn = dm.isOwn,
                timestamp = formatTimestamp(dm.timestamp),
                timestampMillis = dm.timestamp,
                messageType = MessageType.CONTENT
            )
        }
    }

    var initialScrollDone by remember { mutableStateOf(false) }

    // Rebuild the derived state whenever `messages` changes; a plain
    // `remember {}` without keys captured the FIRST (empty) list and kept
    // isAtBottom permanently true — force-scrolling the user down and hiding
    // the scroll-to-bottom button (Bug: "stale derivedStateOf/snapshotFlow").
    val isAtBottom by remember(messages) {
        derivedStateOf {
            val lastIndex = messages.lastIndex
            lastIndex < 0 || listState.firstVisibleItemIndex >= lastIndex - 1
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && (!initialScrollDone || isAtBottom)) {
            listState.scrollToItem(messages.lastIndex)
            initialScrollDone = true
        }
    }

    LaunchedEffect(listState, messages.size) {
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
            scope.launch {
                viewModel.send(textToSend)
            }
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
                // Top bar with server name, back, and connect button
                if (onBack != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding(),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { onBack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_back)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (conversationId != "group") {
                                        conversationTitle ?: "…${conversationId.removePrefix("dm:").take(6)}"
                                    } else {
                                        selectedServer.name
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (conversationId != "group") {
                                        selectedServer.name
                                    } else {
                                        selectedServer.ipAddress
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // Conversation type badge: group or private.
                                if (conversationId != "group") {
                                    val fp = conversationId.removePrefix("dm:")
                                    val isSaved = conversationTitle == savedTitle
                                    Row(
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (isSaved) savedTitle else "DM: …${fp.take(6)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            val isDisconnected = connectionState == ConnectionState.DISCONNECTED
                            val isError = connectionState == ConnectionState.ERROR
                            if (isDisconnected || isError) {
                                IconButton(
                                    onClick = { connectionManager.connect(selectedServer, clientName) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Link,
                                        contentDescription = stringResource(R.string.chat_connect),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else if (connectionState == ConnectionState.READY) {
                                IconButton(
                                    onClick = { connectionManager.disconnect(selectedServer.id) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LinkOff,
                                        contentDescription = stringResource(R.string.chat_disconnect),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                                IconButton(
                                    onClick = { showClearHistoryDialog = true },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = stringResource(R.string.chat_clear_history),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (showClearHistoryDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearHistoryDialog = false },
                        title = { Text(stringResource(R.string.chat_clear_history_title)) },
                        text = { Text(stringResource(R.string.chat_clear_history_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearHistoryDialog = false
                                scope.launch { viewModel.clearHistory() }
                            }) {
                                Text(stringResource(R.string.chat_clear_history_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearHistoryDialog = false }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    )
                }

                ConnectionStatusIndicator(connectionState)

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
                        Box(modifier = Modifier.animateItem()) {
                            if (message.isFullWidth) {
                                FullWidthInfoMessage(message)
                            } else {
                                ChatMessageItem(message)
                            }
                        }
                    }
                }

                MessageInputArea(
                    messageInput = messageInput,
                    onMessageChange = { messageInput = it },
                    onSendClick = onSendClick,
                    connectionState = connectionState,
                    canSendMessages = true,
                )
            }

            ScrollToBottomButton(
                showButton = showScrollButton,
                onClick = onScrollToBottom,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}
