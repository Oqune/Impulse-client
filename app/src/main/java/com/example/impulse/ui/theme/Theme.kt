package com.example.impulse.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = getTypography(ThemeSettings.fontScale),
        shapes = ImpulseShapes,
    ) {
        content()
    }
}
