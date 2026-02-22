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

@Composable
fun ImpulseTheme(
    darkTheme: Boolean = when (ThemeSettings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    },
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val accentColor = ThemeSettings.accentColor

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            // Основной акцентный цвет
            primary = accentColor.darkColor,
            onPrimary = Color.White,
            primaryContainer = accentColor.darkColor.copy(alpha = 0.25f),
            onPrimaryContainer = accentColor.darkColor,

            // Вторичный цвет (для карточек, кнопок)
            secondary = accentColor.darkColor.copy(alpha = 0.8f),
            onSecondary = Color.White,
            secondaryContainer = accentColor.darkColor.copy(alpha = 0.18f),
            onSecondaryContainer = accentColor.darkColor,

            // Фоны с оттенком акцентного цвета
            background = Color(0xFF1C1B1F).copy(
                red = (Color(0xFF1C1B1F).red + accentColor.darkColor.red * 0.05f).coerceIn(0f, 1f),
                green = (Color(0xFF1C1B1F).green + accentColor.darkColor.green * 0.05f).coerceIn(0f, 1f),
                blue = (Color(0xFF1C1B1F).blue + accentColor.darkColor.blue * 0.05f).coerceIn(0f, 1f)
            ),
            onBackground = Color(0xFFE6E1E5),

            surface = Color(0xFF1C1B1F).copy(
                red = (Color(0xFF1C1B1F).red + accentColor.darkColor.red * 0.03f).coerceIn(0f, 1f),
                green = (Color(0xFF1C1B1F).green + accentColor.darkColor.green * 0.03f).coerceIn(0f, 1f),
                blue = (Color(0xFF1C1B1F).blue + accentColor.darkColor.blue * 0.03f).coerceIn(0f, 1f)
            ),
            onSurface = Color(0xFFE6E1E5),

            surfaceVariant = Color(0xFF49454F).copy(
                red = (Color(0xFF49454F).red + accentColor.darkColor.red * 0.1f).coerceIn(0f, 1f),
                green = (Color(0xFF49454F).green + accentColor.darkColor.green * 0.1f).coerceIn(0f, 1f),
                blue = (Color(0xFF49454F).blue + accentColor.darkColor.blue * 0.1f).coerceIn(0f, 1f)
            ),
            onSurfaceVariant = Color(0xFFCAC4D0),

            // Контейнеры
            surfaceContainer = accentColor.darkColor.copy(alpha = 0.12f),
            surfaceContainerHigh = accentColor.darkColor.copy(alpha = 0.17f),
            surfaceContainerHighest = accentColor.darkColor.copy(alpha = 0.22f)
        )
        else -> lightColorScheme(
            // Основной акцентный цвет
            primary = accentColor.lightColor,
            onPrimary = Color.White,
            primaryContainer = accentColor.lightColor.copy(alpha = 0.15f),
            onPrimaryContainer = accentColor.lightColor,

            // Вторичный цвет (для карточек, кнопок)
            secondary = accentColor.lightColor.copy(alpha = 0.8f),
            onSecondary = Color.White,
            secondaryContainer = accentColor.lightColor.copy(alpha = 0.12f),
            onSecondaryContainer = accentColor.lightColor,


            // Фоны с оттенком акцентного цвета
            background = Color(0xFFFFFBFE).copy(
                red = (Color(0xFFFFFBFE).red - (1f - accentColor.lightColor.red) * 0.02f).coerceIn(0f, 1f),
                green = (Color(0xFFFFFBFE).green - (1f - accentColor.lightColor.green) * 0.02f).coerceIn(0f, 1f),
                blue = (Color(0xFFFFFBFE).blue - (1f - accentColor.lightColor.blue) * 0.02f).coerceIn(0f, 1f)
            ),
            onBackground = Color(0xFF1C1B1F),

            surface = Color(0xFFFFFBFE).copy(
                red = (Color(0xFFFFFBFE).red - (1f - accentColor.lightColor.red) * 0.01f).coerceIn(0f, 1f),
                green = (Color(0xFFFFFBFE).green - (1f - accentColor.lightColor.green) * 0.01f).coerceIn(0f, 1f),
                blue = (Color(0xFFFFFBFE).blue - (1f - accentColor.lightColor.blue) * 0.01f).coerceIn(0f, 1f)
            ),
            onSurface = Color(0xFF1C1B1F),

            // Карточки используют surfaceVariant - делаем его с акцентным оттенком
            surfaceVariant = Color(0xFFF3EDF7).copy(
                red = (Color(0xFFF3EDF7).red - (1f - accentColor.lightColor.red) * 0.08f).coerceIn(0f, 1f),
                green = (Color(0xFFF3EDF7).green - (1f - accentColor.lightColor.green) * 0.08f).coerceIn(0f, 1f),
                blue = (Color(0xFFF3EDF7).blue - (1f - accentColor.lightColor.blue) * 0.08f).coerceIn(0f, 1f)
            ),
            onSurfaceVariant = Color(0xFF49454F),

            // Контейнеры
            surfaceContainer = accentColor.lightColor.copy(alpha = 0.08f),
            surfaceContainerHigh = accentColor.lightColor.copy(alpha = 0.12f),
            surfaceContainerHighest = accentColor.lightColor.copy(alpha = 0.17f)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Цвет статус-бара такой же как фон
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}