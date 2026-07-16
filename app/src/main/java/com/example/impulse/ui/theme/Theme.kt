package com.example.impulse.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Builds an opaque HSL color from the given parameters.
 * All theme colors are derived from the single accent hue so the whole
 * UI stays visually consistent when the user changes the accent.
 * Secondary and tertiary use the SAME hue as the accent (no hue mixing) —
 * they differ only in lightness/saturation for subtle visual hierarchy.
 */
private fun hsl(hue: Float, saturation: Float, lightness: Float, alpha: Float = 1f): Color {
    return DynamicColor(
        hue = hue,
        saturation = saturation.coerceIn(0f, 1f),
        lightness = lightness.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f)
    ).toColor()
}

/** Picks a readable foreground (on*) color for a given background lightness. */
private fun onColorFor(lightness: Float): Color {
    return if (lightness > 0.6f) Color(0xFF1A1A1A) else Color.White
}

@Composable
fun ImpulseTheme(
    darkTheme: Boolean = when (ThemeSettings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.OLED -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    },
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val accent = ThemeSettings.accentColor
    val oledMode = ThemeSettings.isOLEDMode

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> {
            val hue = accent.hue
            val sat = accent.saturation.coerceIn(0.35f, 1f)

            // Single accent hue drives the whole ramp. Secondary/tertiary keep
            // the SAME hue (no mixing) — only lightness differs for hierarchy.
            val primaryL = 0.58f
            // Error: fixed red hue for semantic meaning
            val errorHue = 4f

            val primary = hsl(hue, sat, primaryL)
            val secondary = hsl(hue, sat, 0.62f)
            val tertiary = hsl(hue, sat, 0.64f)
            val error = hsl(errorHue, 0.75f, 0.62f)

            val background = if (oledMode) Color.Black else hsl(hue, 0.18f, 0.07f)
            val surface = if (oledMode) Color.Black else hsl(hue, 0.15f, 0.10f)
            val surfaceVariant = if (oledMode) Color(0xFF121212) else hsl(hue, 0.14f, 0.16f)

            darkColorScheme(
                primary = primary,
                onPrimary = Color.White,
                primaryContainer = hsl(hue, sat, 0.22f),
                onPrimaryContainer = Color.White,

                secondary = secondary,
                onSecondary = Color.White,
                secondaryContainer = hsl(hue, sat, 0.20f),
                onSecondaryContainer = Color.White,

                tertiary = tertiary,
                onTertiary = Color.White,
                tertiaryContainer = hsl(hue, sat, 0.22f),
                onTertiaryContainer = Color.White,

                error = error,
                onError = Color.White,
                errorContainer = hsl(errorHue, 0.6f, 0.22f),
                onErrorContainer = Color(0xFFFFDAD6),

                background = background,
                onBackground = hsl(hue, 0.10f, 0.92f),

                surface = surface,
                onSurface = hsl(hue, 0.10f, 0.92f),

                surfaceVariant = surfaceVariant,
                onSurfaceVariant = hsl(hue, 0.12f, 0.78f),

                surfaceContainer = if (oledMode) Color(0xFF0A0A0A) else hsl(hue, 0.14f, 0.13f),
                surfaceContainerHigh = if (oledMode) Color(0xFF121212) else hsl(hue, 0.14f, 0.18f),
                surfaceContainerHighest = if (oledMode) Color(0xFF1A1A1A) else hsl(hue, 0.14f, 0.24f),

                outline = hsl(hue, 0.12f, 0.45f),
                outlineVariant = if (oledMode) Color(0xFF1A1A1A) else hsl(hue, 0.12f, 0.22f),
                scrim = Color.Black,
                inverseSurface = hsl(hue, 0.10f, 0.90f),
                inverseOnSurface = hsl(hue, 0.10f, 0.12f),
                inversePrimary = hsl(hue, sat, 0.70f)
            )
        }
        else -> {
            val hue = accent.hue
            val sat = accent.saturation.coerceIn(0.35f, 1f)

            // Softer, more readable light theme. Primary is a touch deeper so
            // white-on-primary text has good contrast; surfaces get a gentle
            // accent tint instead of being near-white, giving the UI warmth.
            val primaryL = 0.50f
            // Error: fixed red hue for semantic meaning
            val errorHue = 4f

            val primary = hsl(hue, sat, primaryL)
            val secondary = hsl(hue, sat * 0.85f, 0.55f)
            val tertiary = hsl(hue, sat * 0.85f, 0.58f)
            val error = hsl(errorHue, 0.70f, 0.52f)

            // Neutral tint kept low so the theme reads as light, not colored.
            val nSat = 0.04f

            val background = hsl(hue, nSat, 0.99f)
            val surface = hsl(hue, nSat, 1.00f)
            val surfaceVariant = hsl(hue, 0.08f, 0.96f)

            lightColorScheme(
                primary = primary,
                onPrimary = onColorFor(primaryL),
                primaryContainer = hsl(hue, sat * 0.7f, 0.90f),
                onPrimaryContainer = hsl(hue, sat * 0.8f, 0.20f),

                secondary = secondary,
                onSecondary = onColorFor(0.55f),
                secondaryContainer = hsl(hue, sat * 0.6f, 0.92f),
                onSecondaryContainer = hsl(hue, sat * 0.8f, 0.20f),

                tertiary = tertiary,
                onTertiary = onColorFor(0.58f),
                tertiaryContainer = hsl(hue, sat * 0.6f, 0.92f),
                onTertiaryContainer = hsl(hue, sat * 0.8f, 0.20f),

                error = error,
                onError = Color.White,
                errorContainer = hsl(errorHue, 0.55f, 0.94f),
                onErrorContainer = hsl(errorHue, 0.7f, 0.32f),

                background = background,
                onBackground = hsl(hue, 0.10f, 0.10f),

                surface = surface,
                onSurface = hsl(hue, 0.10f, 0.10f),

                surfaceVariant = surfaceVariant,
                onSurfaceVariant = hsl(hue, 0.08f, 0.32f),

                surfaceContainer = hsl(hue, nSat, 0.96f),
                surfaceContainerHigh = hsl(hue, nSat, 0.94f),
                surfaceContainerHighest = hsl(hue, nSat, 0.91f),

                outline = hsl(hue, 0.08f, 0.70f),
                outlineVariant = hsl(hue, 0.08f, 0.90f),
                scrim = Color.Black,
                inverseSurface = hsl(hue, 0.12f, 0.18f),
                inverseOnSurface = hsl(hue, 0.10f, 0.95f),
                inversePrimary = hsl(hue, sat, 0.60f)
            )
        }
    }

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
