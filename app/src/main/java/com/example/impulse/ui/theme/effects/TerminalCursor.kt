package com.example.impulse.ui.theme.effects

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.impulse.ui.theme.ThemeSettings

@Composable
fun TerminalCursor(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00FF41),
    width: Dp = 8.dp,
) {
    if (!ThemeSettings.terminalCursorEnabled) return

    val infinite = rememberInfiniteTransition()
    val visible by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(530, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    Box(
        modifier = modifier
            .width(width)
            .drawBehind {
                drawRect(
                    color = color.copy(alpha = visible),
                    size = Size(size.width, size.height),
                )
            }
    )
}
