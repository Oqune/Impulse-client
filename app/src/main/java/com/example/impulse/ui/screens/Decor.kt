package com.example.impulse.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import com.example.impulse.util.isReduceMotionEnabled

/**
 * Decorative dot-pattern background. The infinite animation is created HERE,
 * lazily — only when a screen actually draws the background — instead of once
 * for the whole app inside the theme. Pass `animated = false` on screens where
 * a static background is preferable (weak GPUs, dialogs).
 */
@Composable
fun DecorativeBackground(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val accentColor = MaterialTheme.colorScheme.primary

    // Respect the system "remove animations" setting: skip the infinite dot
    // pattern entirely when the user asked for reduced motion (Bug: "infinite
    // animations ignore reduced-motion setting").
    val reduceMotion = isReduceMotionEnabled(LocalContext.current)

    val (drift, pulse) = if (animated && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "dot_pattern")
        val d by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(80000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "drift"
        )
        val p by transition.animateFloat(
            initialValue = 0.20f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        d to p
    } else {
        0f to 0.25f
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = backgroundColor)

            val gridSpacing = 40f
            val scrolledOffset = drift * gridSpacing

            for (x in 0..(size.width / gridSpacing).toInt()) {
                for (y in 0..(size.height / gridSpacing).toInt()) {
                    val px = x * gridSpacing + (scrolledOffset % gridSpacing)
                    val py = y * gridSpacing
                    if (px <= size.width) {
                        val isAccent = x % 4 == 0 && y % 4 == 0
                        drawCircle(
                            color = if (isAccent) accentColor.copy(alpha = pulse) else gridColor.copy(alpha = 0.20f),
                            radius = if (isAccent) 2.8f else 1.8f,
                            center = Offset(px, py)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = contentAlignment
        ) {
            content()
        }
    }
}
