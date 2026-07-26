package com.example.impulse.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.impulse.ConnectionManager
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.transport.ConnectionState
import com.example.impulse.ui.theme.*

@Composable
fun HomeScreen(
    clientName: String,
    availableServers: List<ServerConfig>,
    connectionManager: ConnectionManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val serverPreferences = remember { ServerPreferences(context) }
    val serverStates by connectionManager.serverStates.collectAsState()
    val pubKeyHash by remember { mutableStateOf(connectionManager.getPublicKeyHash()) }

    val visibleServers = remember(availableServers) {
        val hidden = serverPreferences.getHiddenServers()
        availableServers.filter { it.id !in hidden }
    }

    val onlineCount = visibleServers.count { serverStates[it.id]?.state == ConnectionState.READY }
    val errorCount = visibleServers.count { serverStates[it.id]?.state == ConnectionState.ERROR }
    val totalCount = visibleServers.size

    // Title gradient animation
    val infiniteTransition = rememberInfiniteTransition(label = "title_grad")

    // Background gradient: super slow, ~14s
    val bgDuration = remember { 14000 + (-500..500).random() }
    val bgShift by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(bgDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "bg_shift"
    )

    // Shimmer sweep: rare, ~8s, with long pauses via RepeatMode.Restart
    val shimmerDuration = remember { 8000 + (-300..300).random() }
    val shimmerSweep by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(shimmerDuration, easing = LinearEasing, delayMillis = 4000),
            repeatMode = RepeatMode.Restart
        ), label = "shimmer_sweep"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // Slow background gradient: two shades of primary, full opacity
    val bgBrush = remember(bgShift, primaryColor) {
        val phase = bgShift * 360f
        val sin = kotlin.math.sin(Math.toRadians(phase.toDouble())).toFloat()
        val shifted = primaryColor.copy(
            red = (primaryColor.red * (1f + sin * 0.15f)).coerceIn(0f, 1f),
            green = (primaryColor.green * (1f + sin * 0.10f)).coerceIn(0f, 1f),
            blue = (primaryColor.blue * (1f - sin * 0.10f)).coerceIn(0f, 1f),
        )
        Brush.linearGradient(
            colors = listOf(primaryColor, shifted),
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f)
        )
    }

    // Fast bright shimmer sweep
    val shimmerBrush = remember(shimmerSweep, primaryColor) {
        val x = shimmerSweep * 1400f
        Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.08f),
                Color.Transparent,
            ),
            start = androidx.compose.ui.geometry.Offset(x - 400f, 0f),
            end = androidx.compose.ui.geometry.Offset(x, 0f)
        )
    }

    DecorativeBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // ── Header ──
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    // Background gradient layer
                    Text(
                        text = "Impulse",
                        style = androidx.compose.ui.text.TextStyle(
                            brush = bgBrush,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            fontFamily = MaterialTheme.typography.headlineLarge.fontFamily,
                            letterSpacing = MaterialTheme.typography.headlineLarge.letterSpacing,
                        ),
                        textAlign = TextAlign.Center,
                    )
                    // Shimmer sweep on top
                    Text(
                        text = "Impulse",
                        style = androidx.compose.ui.text.TextStyle(
                            brush = shimmerBrush,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            fontFamily = MaterialTheme.typography.headlineLarge.fontFamily,
                            letterSpacing = MaterialTheme.typography.headlineLarge.letterSpacing,
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Post-Quantum Encrypted Relay",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // ── User card ──
            ImpulseCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = clientName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = clientName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (pubKeyHash.isNotEmpty()) {
                            Text(
                                text = pubKeyHash,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── Server stats — minimal inline row ──
            ImpulseCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MiniStat(value = "$totalCount", label = "Всего", color = MaterialTheme.colorScheme.onSurface)
                    MiniStat(value = "$onlineCount", label = "Онлайн", color = MaterialTheme.colorScheme.primary)
                    if (errorCount > 0) {
                        MiniStat(value = "$errorCount", label = "Ошибки", color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(12.dp))

                visibleServers.forEach { server ->
                    val state = serverStates[server.id]
                    val isConnected = state?.state == ConnectionState.READY
                    val isConnecting = state?.state in listOf(
                        ConnectionState.CONNECTING, ConnectionState.CONNECTED,
                        ConnectionState.AUTHENTICATING, ConnectionState.AUTHENTICATED,
                    )
                    val hasError = state?.state == ConnectionState.ERROR

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(
                            color = when {
                                isConnected -> MaterialTheme.colorScheme.primary
                                isConnecting -> MaterialTheme.colorScheme.tertiary
                                hasError -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            },
                            size = 7.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = when {
                                isConnected -> "ONLINE"
                                isConnecting -> "SYNC"
                                hasError -> "ERR"
                                else -> "OFF"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = when {
                                isConnected -> MaterialTheme.colorScheme.primary
                                isConnecting -> MaterialTheme.colorScheme.tertiary
                                hasError -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                }
            }

            // ── Quick actions — compact row ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        visibleServers.forEach { server ->
                            val st = serverStates[server.id]?.state
                            if (st == null || st == ConnectionState.DISCONNECTED || st == ConnectionState.ERROR) {
                                connectionManager.connect(server, clientName)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = ButtonShape,
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Подключить все", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        visibleServers.forEach { server ->
                            connectionManager.disconnect(server.id)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = ButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Отключить все", style = MaterialTheme.typography.labelMedium)
                }
            }

            // ── Tech showcase ──
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Powered by",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                val techs = listOf(
                    "ML-KEM-768" to "Post-Quantum KEM",
                    "ML-DSA-65" to "Post-Quantum Sign",
                    "WebTransport" to "HTTP/3 + QUIC",
                    "AES-256-GCM" to "AEAD Cipher",
                    "Ed25519" to "Identity Key",
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    techs.forEach { (name, desc) ->
                        Surface(
                            modifier = Modifier.padding(horizontal = 3.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }

            } // inner Column

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(
    value: String,
    label: String,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
