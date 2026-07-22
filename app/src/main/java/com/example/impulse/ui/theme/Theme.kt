package com.example.impulse.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun ImpulseTheme(
    darkTheme: Boolean = when (ThemeSettings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.OLED -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    },
    content: @Composable () -> Unit
) {
    val preset = ThemeConfig.preset
    val palettes = ThemePalettes[preset] ?: ThemePalettes[ThemePreset.CYBER_BLUE]!!
    val isOled = ThemeSettings.isOLEDMode

    val colors = when {
        isOled -> palettes.oled
        darkTheme -> palettes.dark
        else -> palettes.light
    }
    val colorScheme = if (darkTheme) colors.toDarkScheme() else colors.toLightScheme()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = getTypography(ThemeSettings.fontScale),
        content = content
    )
}
