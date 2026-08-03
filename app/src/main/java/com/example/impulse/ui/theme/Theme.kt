package com.example.impulse.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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

    val variant = ThemeSettings.themeVariant
    val context = LocalContext.current

    // Material You (wallpaper accent) is available only on Android 12+;
    // below it falls back to the classic hue palette.
    val colorScheme = if (variant == ThemeVariant.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val palettes = generatePaletteFromHue(ThemeSettings.hue, darkTheme)
        val colors = when (variant) {
            ThemeVariant.ULTRA_CONTRAST -> if (darkTheme) palettes.ultraContrast else palettes.lightUltraContrast
            ThemeVariant.OLED -> if (darkTheme) palettes.oled else palettes.dark
            ThemeVariant.MATERIAL_YOU -> if (darkTheme) palettes.dark else palettes.light
            ThemeVariant.CLASSIC -> if (darkTheme) palettes.dark else palettes.light
        }
        if (darkTheme) colors.toDarkScheme() else colors.toLightScheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val bg = colorScheme.background
            window.setBackgroundDrawable(ColorDrawable(bg.toArgb()))
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
