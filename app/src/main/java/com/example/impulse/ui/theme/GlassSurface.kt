package com.example.impulse.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted-glass surface with an adaptive fallback.
 *
 * On Android 12+ (API 31) the content behind is blurred for a true glass look.
 * On older OS versions the same composable renders a clean semi-transparent
 * panel — no blur (unsupported pre-S) and no hard border (which rendered as a
 * thick dark outline on old devices). Size is defined by `content()`; the
 * blurred backdrop uses `matchParentSize` so it never inflates the parent
 * (Bug: "white screen at the bottom" from an over-eager fillMaxSize).
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
    content: @Composable BoxScope.() -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val background = MaterialTheme.colorScheme.background
    val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(modifier = modifier) {
        if (modern) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(background.copy(alpha = 0.6f))
                    .blur(blurRadius)
            )
        }
        Box(
            modifier = Modifier
                .clip(shape)
                .background(surface.copy(alpha = alpha))
        ) {
            content()
        }
    }
}
