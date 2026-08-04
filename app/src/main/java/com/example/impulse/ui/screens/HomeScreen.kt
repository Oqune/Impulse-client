package com.example.impulse.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.impulse.ConnectionManager
import com.example.impulse.R
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
    // Respect reduced-motion: keep a static gradient (no shimmer sweep) when
    // the system animator scale is 0 (Bug: "infinite animations ignore
    // reduced-motion setting").
    val reduceMotion = com.example.impulse.util.isReduceMotionEnabled(context)

    val (bgShift, shimmerSweep) = if (!reduceMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "title_grad")

        // Background gradient: super slow, ~14s
        val bgDuration = remember { 14000 + (-500..500).random() }
        val bg by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(bgDuration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ), label = "bg_shift"
        )

        // Shimmer sweep: fast (~2.5s pass) but rare (~14s pause), so it flashes
        // quickly yet doesn't draw the eye constantly.
        val shimmerDuration = remember { 2500 + (-300..300).random() }
        val shimmer by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(shimmerDuration, easing = LinearEasing, delayMillis = 14000),
                repeatMode = RepeatMode.Restart
            ), label = "shimmer_sweep"
        )
        bg to shimmer
    } else {
        // Reduced motion: static gradient, no shimmer.
        0.5f to -1f
    }

    val primaryColor = MaterialTheme.colorScheme.primary

    // Logo gradient: primary → slightly shifted primary. On light theme this
    // stays vivid (no dark/black tint — the old sin-based shift could darken).
    val bgBrush = remember(bgShift, primaryColor) {
        val phase = bgShift * 360f
        val sin = kotlin.math.sin(Math.toRadians(phase.toDouble())).toFloat()
        val shifted = androidx.compose.ui.graphics.Color(
            red = (primaryColor.red * (1f + sin * 0.10f)).coerceIn(0f, 1f),
            green = (primaryColor.green * (1f + sin * 0.10f)).coerceIn(0f, 1f),
            blue = (primaryColor.blue * (1f + sin * 0.10f)).coerceIn(0f, 1f),
            alpha = 1f
        )
        Brush.linearGradient(
            colors = listOf(primaryColor, shifted),
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(700f, 0f)
        )
    }

    // Fast, bright sweep that passes quickly but appears rarely. Uses a light
    // highlight derived from primary (never black in light theme).
    val shimmerBrush = remember(shimmerSweep, primaryColor) {
        val x = shimmerSweep * 1200f
        Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                primaryColor.copy(alpha = 0.15f),
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f),
                primaryColor.copy(alpha = 0.15f),
                Color.Transparent,
            ),
            start = androidx.compose.ui.geometry.Offset(x - 300f, 0f),
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
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.graphicsLayer {
                        rotationZ = -4f
                    }
                ) {
                    // Background gradient layer
                    Text(
                        text = stringResource(R.string.app_name),
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
                        text = stringResource(R.string.app_name),
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
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    MiniStat(value = "$totalCount", label = stringResource(R.string.home_stat_total), color = MaterialTheme.colorScheme.onSurface)
                    MiniStat(value = "$onlineCount", label = stringResource(R.string.home_stat_online), color = MaterialTheme.colorScheme.primary)
                    if (errorCount > 0) {
                        MiniStat(value = "$errorCount", label = stringResource(R.string.home_stat_errors), color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(12.dp))

                visibleServers.forEach { server ->
                    ServerStatusRow(
                        name = server.name,
                        state = serverStates[server.id]?.state
                    )
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
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_connect_all), style = MaterialTheme.typography.labelMedium)
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
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_disconnect_all), style = MaterialTheme.typography.labelMedium)
                }
            }

            // ── Tech showcase ──
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_powered_by),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
                val techs = listOf(
                    "ML-KEM-768" to "Post-Quantum KEM",
                    "ML-DSA-65" to "Post-Quantum Sign",
                    "WebTransport" to "HTTP/3 + QUIC",
                    "AES-256-GCM" to "AEAD Cipher",
                    "Argon2id" to "Key Derivation",
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
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
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
