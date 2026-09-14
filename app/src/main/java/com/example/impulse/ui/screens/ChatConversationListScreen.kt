package com.example.impulse.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.R
import com.example.impulse.data.ServerConfig
import com.example.impulse.transport.ConnectionState
import com.example.impulse.ui.theme.CardShape
import com.example.impulse.ui.theme.StatusDot

/**
 * One server's conversation list: the group chat, the Saved/Favorites (self)
 * chat, and every known private (DM) conversation.
 */
@Composable
fun ChatConversationListScreen(
    server: ServerConfig,
    conversations: List<String>,
    ownFingerprint: String,
    peerNames: Map<String, String>,
    state: ConnectionState?,
    onConversation: (String) -> Unit,
    onBack: () -> Unit,
) {
    DecorativeBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(top = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = server.ipAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state == ConnectionState.READY) {
                    StatusDot(
                        color = MaterialTheme.colorScheme.primary,
                        size = 10.dp,
                    )
                }
            }

            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(conversations, key = { it }) { conv ->
                    val peerFp = conv.removePrefix("dm:")
                    val isSaved = ownFingerprint.isNotEmpty() && peerFp == ownFingerprint
                    val isGroup = conv == "group"
                    val title = when {
                        isSaved -> stringResource(R.string.chat_saved_title)
                        isGroup -> stringResource(R.string.chat_recipient_group)
                        else -> peerNames[peerFp] ?: "…${peerFp.take(6)}"
                    }
                    val subtitle = when {
                        isSaved -> "Local private encrypted storage"
                        isGroup -> "LAN broadcast / per-recipient KEM"
                        else -> "PQ-E2EE direct message"
                    }
                    Box(modifier = Modifier.animateItem()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    runCatching {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    }
                                    onConversation(conv)
                                },
                            shape = CardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = when {
                                                isSaved -> Icons.Default.Bookmark
                                                isGroup -> Icons.Default.Group
                                                else -> Icons.Default.Person
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
