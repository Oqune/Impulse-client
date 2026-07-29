package com.example.impulse.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme

/**
 * CompositionLocal that carries the shared dot-pattern animation values.
 * Provided once by [ImpulseTheme], consumed by every [DecorativeBackground].
 */
class DotPatternValues(
    val drift: Float,
    val pulse: Float
)

val LocalDotPattern = compositionLocalOf { DotPatternValues(0f, 0.25f) }

@Composable
fun DecorativeBackground(
    modifier: Modifier = Modifier,
    contentAlignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val accentColor = MaterialTheme.colorScheme.primary

    val dotPattern = LocalDotPattern.current
    val drift = dotPattern.drift
    val pulse = dotPattern.pulse

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
