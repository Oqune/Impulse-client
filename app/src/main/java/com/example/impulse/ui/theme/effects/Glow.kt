package com.example.impulse.ui.theme.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.impulse.ui.theme.ThemeSettings

fun Modifier.glow(
    color: Color,
    radius: Dp = 12.dp,
    alpha: Float = 0.5f,
): Modifier {
    if (!ThemeSettings.glowEnabled) return this
    return this.then(drawBehind {
        val r = radius.toPx()
        drawCircle(
            color = color.copy(alpha = alpha * 0.3f),
            radius = r * 2f,
            center = Offset(size.width / 2f, size.height / 2f),
        )
        drawCircle(
            color = color.copy(alpha = alpha * 0.5f),
            radius = r,
            center = Offset(size.width / 2f, size.height / 2f),
        )
    })
}
