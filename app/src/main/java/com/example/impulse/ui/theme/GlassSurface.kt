package com.example.impulse.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted-glass surface with an adaptive fallback.
 *
 * On Android 12+ (API 31) the content behind is blurred for a true glass look.
 * On older OS versions the same composable renders a semi-opaque surface with
 * a subtle top highlight — no blur (unsupported pre-S) — so it never looks
 * broken, just less "glassy" (Bug: "translucency of popups on old OS").
 *
 * Usage:
 * ```
 * GlassSurface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
 *     Text("...")
 * }
 * ```
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    alpha: Float = 0.72f,
    blurRadius: Dp = 14.dp,
    topEdgeHighlight: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.background

    val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(modifier = modifier) {
        if (modern) {
            // Backdrop blur + translucent surface = glass. `matchParentSize`
            // (not `fillMaxSize`) so this layer only fills the size determined
            // by the foreground `content()`, and never inflates the parent.
            // Using fillMaxSize here made the nav-bar GlassSurface expand to
            // the whole screen when used as a Scaffold bottomBar (Bug: "white
            // screen at the bottom").
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(background.copy(alpha = 0.6f))
                    .blur(blurRadius)
            )
        }
        // Foreground surface (always drawn, blur or not). Wraps `content()` so
        // the content size defines the GlassSurface size (no matchParentSize
        // here — otherwise the parent would measure to zero).
        Box(
            modifier = Modifier
                .clip(shape)
                .background(surface.copy(alpha = alpha))
                .then(
                    if (topEdgeHighlight) {
                        Modifier.background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.06f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = 200f,
                            )
                        )
                    } else Modifier
                )
                .border(width = 1.dp, color = outline.copy(alpha = 0.35f), shape = shape)
        ) {
            content()
        }
    }
}

/**
 * Convenience for a plain elevated panel with glass styling when blur is
 * unavailable — keeps call sites identical across OS versions.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), shape = shape)
    ) {
        content()
    }
}
