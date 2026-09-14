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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

    val reduceMotion = com.example.impulse.util.isReduceMotionEnabled(context)

    // Title kinetic animations: individual letter electric micro-jitter & metallic chrome shimmer
    val (shimmerSweep, jitterPhase) = if (!reduceMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "title_kinetic")

        // Shimmer sweep: fast (1300ms) with a 2400ms pause, appearing actively every 3.7s
        val shimmer by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1300, easing = FastOutSlowInEasing, delayMillis = 2400),
                repeatMode = RepeatMode.Restart
            ), label = "shimmer_sweep"
        )

        // Kinetic electric micro-jitter phase for individual letters
        val jPhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "letter_jitter_phase"
        )

        TitleAnimations(shimmer, jPhase)
    } else {
        TitleAnimations(-1f, 0f)
    }

    val primaryColor = MaterialTheme.colorScheme.primary

    val scrollState = rememberScrollState()
    DecorativeBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Header (Kinetic Title with per-letter electric jitter & chrome shimmer sweep) ──
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            ) {
                val titleText = stringResource(R.string.app_name)
                val density = LocalDensity.current.density

                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            if (shimmerSweep in 0f..1f) {
                                val sweepPos = -size.width * 0.3f + shimmerSweep * (size.width * 1.6f)
                                drawRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            primaryColor.copy(alpha = 0.20f),
                                            Color.White.copy(alpha = 0.95f),
                                            primaryColor.copy(alpha = 0.30f),
                                            Color.Transparent,
                                        ),
                                        start = Offset(sweepPos - 70f, 0f),
                                        end = Offset(sweepPos + 70f, 0f)
                                    ),
                                    blendMode = BlendMode.SrcAtop
                                )
                            }
                        }
                ) {
                    titleText.forEachIndexed { index, char ->
                        val letterY = if (!reduceMotion) {
                            val p = jitterPhase + index * 1.05f
                            (kotlin.math.sin(p.toDouble()) * 0.85 + kotlin.math.sin(p * 2.3) * 0.45).toFloat()
                        } else 0f

                        Text(
                            text = char.toString(),
                            style = androidx.compose.ui.text.TextStyle(
                                color = primaryColor,
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = JetBrainsMono,
                            ),
                            modifier = Modifier.graphicsLayer {
                                translationY = letterY * density
                            }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.5.sp,
                )
            }

            // ── User card (Crypto-Passport with one-tap copy) ──
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            var copiedPassport by remember { mutableStateOf(false) }

            LaunchedEffect(copiedPassport) {
                if (copiedPassport) {
                    kotlinx.coroutines.delay(1800)
                    copiedPassport = false
                }
            }

            ImpulseCard(
                onClick = {
                    if (pubKeyHash.isNotBlank()) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Impulse KEM Fingerprint", pubKeyHash)
                        clipboard?.setPrimaryClip(clip)
                        runCatching {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        }
                        copiedPassport = true
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = clientName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = clientName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (pubKeyHash.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Surface(
                                    shape = MicroShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "…${pubKeyHash.take(8)}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = if (copiedPassport) "✓ copied" else "tap to copy",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = if (copiedPassport) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                    ShieldBadge(
                        text = "PQ-ID",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Tactical Tunnel & Server stats ──
            val anyConnected = onlineCount > 0
            val anyConnecting = visibleServers.any {
                serverStates[it.id]?.state in listOf(
                    ConnectionState.CONNECTING, ConnectionState.CONNECTED,
                    ConnectionState.AUTHENTICATING, ConnectionState.AUTHENTICATED
                )
            }

            // Pulse animation for tunnel ring when connecting or live
            val infiniteTransition = rememberInfiniteTransition(label = "tunnel_pulse")
            val pulseAlpha by if (!reduceMotion && (anyConnecting || anyConnected)) {
                infiniteTransition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 0.85f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(if (anyConnecting) 700 else 2200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse_alpha"
                )
            } else {
                remember { mutableFloatStateOf(0.4f) }
            }

            ImpulseCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    anyConnected -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
                                    anyConnecting -> MaterialTheme.colorScheme.tertiary.copy(alpha = pulseAlpha)
                                    errorCount > 0 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            anyConnected -> "SECURE TUNNEL ACTIVE (QUIC)"
                            anyConnecting -> "HANDSHAKE IN PROGRESS…"
                            errorCount > 0 -> "TUNNEL DEGRADED"
                            else -> "TUNNEL STANDBY"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = when {
                            anyConnected -> MaterialTheme.colorScheme.primary
                            anyConnecting -> MaterialTheme.colorScheme.tertiary
                            errorCount > 0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

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

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    thickness = 0.5.dp
                )
                Spacer(Modifier.height(10.dp))

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
                ImpulseButton(
                    text = stringResource(R.string.home_connect_all),
                    icon = Icons.Default.Link,
                    onClick = {
                        runCatching {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                        visibleServers.forEach { server ->
                            val st = serverStates[server.id]?.state
                            if (st == null || st == ConnectionState.DISCONNECTED || st == ConnectionState.ERROR) {
                                connectionManager.connect(server, clientName)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ImpulseDestructiveButton(
                    text = stringResource(R.string.home_disconnect_all),
                    icon = Icons.Default.LinkOff,
                    onClick = {
                        visibleServers.forEach { server ->
                            connectionManager.disconnect(server.id)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
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
                            shape = ChipShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            border = BorderStroke(
                                StandardBorderWidth,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
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

private data class TitleAnimations(
    val shimmerSweep: Float,
    val jitterPhase: Float
)

/**
 * Bespoke Quantum Impulse Emblem:
 * - Outer cryptographic hexagon with glowing aura
 * - Inner dashed quantum lattice
 * - Dynamic high-frequency Impulse waveform spike with pulse nodes
 * - Active chrome/silver shimmer sweep reflection
 */
@Composable
private fun ImpulseEmblem(
    modifier: Modifier = Modifier,
    shimmerSweep: Float = -1f,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    accentColor: Color = MaterialTheme.colorScheme.tertiary
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) * 0.44f

        // 1. Outer Quantum Hexagon
        val hexPath = Path()
        for (i in 0..5) {
            val angleRad = Math.toRadians((60.0 * i - 30.0)).toFloat()
            val x = cx + radius * kotlin.math.cos(angleRad)
            val y = cy + radius * kotlin.math.sin(angleRad)
            if (i == 0) hexPath.moveTo(x, y) else hexPath.lineTo(x, y)
        }
        hexPath.close()

        // Hexagon background aura glow
        drawPath(
            path = hexPath,
            brush = Brush.radialGradient(
                colors = listOf(primaryColor.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius * 1.15f
            )
        )

        // Hexagon outer border
        drawPath(
            path = hexPath,
            brush = Brush.linearGradient(
                colors = listOf(primaryColor, accentColor),
                start = Offset(cx - radius, cy - radius),
                end = Offset(cx + radius, cy + radius)
            ),
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 2. Inner Quantum Sub-Lattice (smaller concentric hexagon with dash effect)
        val innerRadius = radius * 0.68f
        val innerHexPath = Path()
        for (i in 0..5) {
            val angleRad = Math.toRadians((60.0 * i - 30.0)).toFloat()
            val x = cx + innerRadius * kotlin.math.cos(angleRad)
            val y = cy + innerRadius * kotlin.math.sin(angleRad)
            if (i == 0) innerHexPath.moveTo(x, y) else innerHexPath.lineTo(x, y)
        }
        innerHexPath.close()

        drawPath(
            path = innerHexPath,
            color = primaryColor.copy(alpha = 0.35f),
            style = Stroke(
                width = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        )

        // 3. Central Dynamic Impulse Wave: "—\/\/\—" cutting through the center
        val wavePath = Path()
        val startX = cx - radius * 0.82f
        val endX = cx + radius * 0.82f
        val step = (endX - startX) / 8f

        wavePath.moveTo(startX, cy)
        wavePath.lineTo(startX + step * 2f, cy)
        wavePath.lineTo(startX + step * 3f, cy - radius * 0.42f)
        wavePath.lineTo(startX + step * 4f, cy + radius * 0.50f)
        wavePath.lineTo(startX + step * 5f, cy - radius * 0.28f)
        wavePath.lineTo(startX + step * 6f, cy)
        wavePath.lineTo(endX, cy)

        // Glow behind the impulse wave
        drawPath(
            path = wavePath,
            color = primaryColor.copy(alpha = 0.40f),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Sharp precision impulse line
        drawPath(
            path = wavePath,
            brush = Brush.horizontalGradient(
                colors = listOf(primaryColor, Color.White, accentColor),
                startX = startX,
                endX = endX
            ),
            style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Core Quantum Pulse Nodes at vertices and impulse peaks
        drawCircle(
            color = Color.White,
            radius = 3.dp.toPx(),
            center = Offset(startX + step * 4f, cy + radius * 0.50f)
        )
        drawCircle(
            color = Color.White,
            radius = 3.dp.toPx(),
            center = Offset(startX + step * 3f, cy - radius * 0.42f)
        )

        // 4. Shimmer Gleam Reflection on Emblem
        if (shimmerSweep in 0f..1f) {
            val sweepX = (startX - 100f) + shimmerSweep * ((endX - startX) + 200f)
            val sheenBrush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.70f),
                    Color.Transparent
                ),
                start = Offset(sweepX - 35f, cy - radius),
                end = Offset(sweepX + 35f, cy + radius)
            )
            drawPath(
                path = hexPath,
                brush = sheenBrush,
                style = Stroke(width = 3.4.dp.toPx())
            )
            drawPath(
                path = wavePath,
                brush = sheenBrush,
                style = Stroke(width = 3.8.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

