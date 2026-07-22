package com.example.impulse.ui.screens

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Draws a set of large, faint glyphs behind the screen content to give the
 * UI a modern, stylised look that is tinted by the current accent color.
 */
@Composable
fun DecorativeBackground(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    alpha: Float = 0.05f,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier, contentAlignment = contentAlignment) {
        val glyphColor = tint.copy(alpha = alpha)

        Text(
            text = "@",
            color = glyphColor,
            fontSize = 200.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-50).dp, y = (-40).dp)
                .rotate(15f)
        )
        Text(
            text = "#",
            color = glyphColor,
            fontSize = 160.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = 24.dp)
                .rotate(-10f)
        )
        Text(
            text = "✶",
            color = glyphColor,
            fontSize = 150.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(y = (-20).dp)
                .rotate(20f)
        )
        Text(
            text = "❯",
            color = glyphColor,
            fontSize = 180.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(y = (-30).dp)
                .rotate(-15f)
        )
        Text(
            text = "◈",
            color = glyphColor,
            fontSize = 170.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(y = 40.dp)
                .rotate(10f)
        )

        content()
    }
}
