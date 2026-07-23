package com.example.impulse.ui.theme.effects

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.example.impulse.ui.theme.ThemeSettings

@Composable
fun GlitchOverlay(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00FF41),
    intensity: Float = 1f,
) {
    if (!ThemeSettings.glitchEnabled) return

    val infinite = rememberInfiniteTransition()
    val offsetX by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 4f * intensity,
        animationSpec = infiniteRepeatable(
            animation = tween(100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val visible by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, delayMillis = 5000),
            repeatMode = RepeatMode.Restart,
        ),
    )
    val sliceHeight by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(80),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    if (visible > 0.5f) {
        Box(
            modifier = modifier.drawWithContent {
                drawContent()
                val y = size.height * 0.3f
                drawRect(
                    color = color.copy(alpha = 0.15f * visible),
                    topLeft = androidx.compose.ui.geometry.Offset(
                        offsetX, y
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        size.width, sliceHeight
                    ),
                )
            }
        )
    }
}
