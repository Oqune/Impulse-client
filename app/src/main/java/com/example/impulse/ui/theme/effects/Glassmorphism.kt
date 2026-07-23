package com.example.impulse.ui.theme.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.example.impulse.ui.theme.ThemeSettings

fun Modifier.glassBackground(
    backgroundColor: Color,
    blurRadius: Float = 20f,
    alpha: Float = 0.15f,
): Modifier {
    if (!ThemeSettings.glassEnabled) return this
    return this.then(drawBehind {
        drawRect(
            color = backgroundColor.copy(alpha = alpha),
            size = size,
        )
        drawRect(
            color = Color.White.copy(alpha = 0.05f),
            topLeft = androidx.compose.ui.geometry.Offset.Zero,
            size = androidx.compose.ui.geometry.Size(size.width, 1f),
        )
    })
}
