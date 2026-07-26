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
import androidx.compose.material3.MaterialTheme

@Composable
fun DecorativeBackground(
    modifier: Modifier = Modifier,
    contentAlignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val accentColor = MaterialTheme.colorScheme.primary

    val infiniteTransition = rememberInfiniteTransition(label = "bg")

    val drift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(80000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "drift"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

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
