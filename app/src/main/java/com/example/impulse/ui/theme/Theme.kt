package com.example.impulse.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.core.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.impulse.ui.screens.DotPatternValues
import com.example.impulse.ui.screens.LocalDotPattern

@Composable
fun ImpulseTheme(
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    remember(isSystemDark) { ThemeSettings.setSystemDark(isSystemDark) }

    val darkTheme = when (ThemeSettings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemDark
    }

      val preset = ThemeSettings.preset
      val palettes = generatePaletteFromHue(ThemeSettings.hue, darkTheme)
      val oled = ThemeSettings.oledEnabled && darkTheme
      val ultraContrast = ThemeSettings.ultraContrastEnabled

    val colors = when {
        ultraContrast && darkTheme -> palettes.ultraContrast
        ultraContrast && !darkTheme -> palettes.lightUltraContrast
        oled -> palettes.oled
        darkTheme -> palettes.dark
        else -> palettes.light
    }
    val colorScheme = if (darkTheme) colors.toDarkScheme() else colors.toLightScheme()

    // ── Shared dot-pattern animation (one transition for the whole app) ──
    val infiniteTransition = rememberInfiniteTransition(label = "dot_pattern")
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
        initialValue = 0.20f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalDotPattern provides DotPatternValues(drift, pulse)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = getTypography(ThemeSettings.fontScale),
            shapes = ImpulseShapes,
        ) {
            content()
        }
    }
}
