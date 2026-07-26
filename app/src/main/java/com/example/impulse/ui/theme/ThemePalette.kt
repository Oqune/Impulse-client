package com.example.impulse.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class ThemePreset(
    val displayName: String,
    val previewPrimary: Color,
    val previewSecondary: Color,
    val previewBg: Color,
    val previewPrimaryLight: Color,
    val previewSecondaryLight: Color,
    val previewBgLight: Color,
) {
    NEON("Neon", Color(0xFF00FF41), Color(0xFF00CC33), Color(0xFF0A0F0A),
        Color(0xFF2E7D32), Color(0xFF388E3C), Color(0xFFF0F5F0)),
    EMBER("Ember", Color(0xFFFF5722), Color(0xFFE64A19), Color(0xFF1A0E08),
        Color(0xFFBF360C), Color(0xFFD84315), Color(0xFFFBF3F0)),
    FROST("Frost", Color(0xFF80DEEA), Color(0xFF4DD0E1), Color(0xFF0A1620),
        Color(0xFF00838F), Color(0xFF0097A7), Color(0xFFF0F8FA)),
    SYNTHWAVE("Synthwave", Color(0xFFFF2D78), Color(0xFFE91E63), Color(0xFF120818),
        Color(0xFFC51162), Color(0xFFD81B60), Color(0xFFFCF0F5)),
    ACID("Acid", Color(0xFFC6FF00), Color(0xFFAEEA00), Color(0xFF0E1208),
        Color(0xFF558B2F), Color(0xFF689F38), Color(0xFFF4F8F0)),
    CRIMSON("Crimson", Color(0xFFFF1744), Color(0xFFD50000), Color(0xFF180808),
        Color(0xFFB71C1C), Color(0xFFC62828), Color(0xFFFCF0F1)),
    PHANTOM("Phantom", Color(0xFFCFD8DC), Color(0xFFB0BEC5), Color(0xFF0C0E10),
        Color(0xFF455A64), Color(0xFF546E7A), Color(0xFFF4F6F8)),
    COBALT("Cobalt", Color(0xFF2979FF), Color(0xFF2962FF), Color(0xFF080E1A),
        Color(0xFF1565C0), Color(0xFF1976D2), Color(0xFFF0F4FA)),
}

data class PaletteTriple(
    val light: ColorSchemeColors,
    val dark: ColorSchemeColors,
    val oled: ColorSchemeColors,
    val ultraContrast: ColorSchemeColors,
    val lightUltraContrast: ColorSchemeColors,
)

data class ColorSchemeColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
)

fun ColorSchemeColors.toDarkScheme() = darkColorScheme(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, onSecondary = onSecondary,
    secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary, onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
    error = error, onError = onError,
    errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    background = background, onBackground = onBackground,
    surface = surface, onSurface = onSurface,
    surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
    surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest,
    outline = outline, outlineVariant = outlineVariant,
    scrim = scrim, inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary,
)

fun ColorSchemeColors.toLightScheme() = lightColorScheme(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, onSecondary = onSecondary,
    secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary, onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
    error = error, onError = onError,
    errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    background = background, onBackground = onBackground,
    surface = surface, onSurface = onSurface,
    surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
    surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest,
    outline = outline, outlineVariant = outlineVariant,
    scrim = scrim, inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary,
)

// ── Helpers ─────────────────────────────────────────────────────────────

private fun dark(
    primary: Color, bg: Color,
    secondary: Color = primary.copy(alpha = 0.75f),
    tertiary: Color = primary.copy(alpha = 0.6f),
    error: Color = Color(0xFFEF9A9A),
) = ColorSchemeColors(
    primary = primary, onPrimary = bg,
    primaryContainer = primary.copy(alpha = 0.18f), onPrimaryContainer = primary.copy(alpha = 0.9f),
    secondary = secondary, onSecondary = bg,
    secondaryContainer = secondary.copy(alpha = 0.15f), onSecondaryContainer = secondary.copy(alpha = 0.85f),
    tertiary = tertiary, onTertiary = bg,
    tertiaryContainer = tertiary.copy(alpha = 0.12f), onTertiaryContainer = tertiary.copy(alpha = 0.8f),
    error = error, onError = Color(0xFF3D0000),
    errorContainer = Color(0xFF6B1A1A), onErrorContainer = Color(0xFFFFCDD2),
    background = bg, onBackground = primary.copy(alpha = 0.88f),
    surface = bg.lighten(0.02f), onSurface = primary.copy(alpha = 0.88f),
    surfaceVariant = bg.lighten(0.06f), onSurfaceVariant = primary.copy(alpha = 0.55f),
    surfaceContainer = bg.lighten(0.04f), surfaceContainerHigh = bg.lighten(0.07f),
    surfaceContainerHighest = bg.lighten(0.10f),
    outline = primary.copy(alpha = 0.28f), outlineVariant = primary.copy(alpha = 0.12f),
    scrim = Color.Black,
    inverseSurface = primary.copy(alpha = 0.88f), inverseOnSurface = bg,
    inversePrimary = primary.copy(alpha = 0.7f),
)

private fun oled(
    primary: Color,
    secondary: Color = primary.copy(alpha = 0.75f),
    tertiary: Color = primary.copy(alpha = 0.6f),
    error: Color = Color(0xFFEF9A9A),
) = ColorSchemeColors(
    primary = primary, onPrimary = Color.Black,
    primaryContainer = primary.copy(alpha = 0.14f), onPrimaryContainer = primary.copy(alpha = 0.9f),
    secondary = secondary, onSecondary = Color.Black,
    secondaryContainer = secondary.copy(alpha = 0.12f), onSecondaryContainer = secondary.copy(alpha = 0.85f),
    tertiary = tertiary, onTertiary = Color.Black,
    tertiaryContainer = tertiary.copy(alpha = 0.10f), onTertiaryContainer = tertiary.copy(alpha = 0.8f),
    error = error, onError = Color(0xFF3D0000),
    errorContainer = Color(0xFF5C1515), onErrorContainer = Color(0xFFFFCDD2),
    background = Color.Black, onSurface = primary.copy(alpha = 0.82f),
    surface = Color.Black, onBackground = primary.copy(alpha = 0.82f),
    surfaceVariant = Color(0xFF0A0A0A), onSurfaceVariant = primary.copy(alpha = 0.48f),
    surfaceContainer = Color(0xFF060606), surfaceContainerHigh = Color(0xFF0E0E0E),
    surfaceContainerHighest = Color(0xFF161616),
    outline = primary.copy(alpha = 0.22f), outlineVariant = primary.copy(alpha = 0.08f),
    scrim = Color.Black,
    inverseSurface = primary.copy(alpha = 0.82f), inverseOnSurface = Color.Black,
    inversePrimary = primary.copy(alpha = 0.65f),
)

private fun ultraContrast(
    primary: Color,
    secondary: Color = primary.copy(alpha = 0.8f),
    tertiary: Color = primary.copy(alpha = 0.65f),
    error: Color = Color(0xFFFF8A80),
) = ColorSchemeColors(
    primary = primary, onPrimary = Color.Black,
    primaryContainer = primary.copy(alpha = 0.22f), onPrimaryContainer = Color.White,
    secondary = secondary, onSecondary = Color.Black,
    secondaryContainer = secondary.copy(alpha = 0.18f), onSecondaryContainer = Color.White,
    tertiary = tertiary, onTertiary = Color.Black,
    tertiaryContainer = tertiary.copy(alpha = 0.15f), onTertiaryContainer = Color.White,
    error = error, onError = Color.Black,
    errorContainer = Color(0xFF8B0000), onErrorContainer = Color.White,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF0A0A0A), onSurfaceVariant = Color(0xFFE0E0E0),
    surfaceContainer = Color(0xFF050505), surfaceContainerHigh = Color(0xFF101010),
    surfaceContainerHighest = Color(0xFF1A1A1A),
    outline = Color(0xFF666666), outlineVariant = Color(0xFF333333),
    scrim = Color.Black,
    inverseSurface = Color.White, inverseOnSurface = Color.Black,
    inversePrimary = primary.copy(alpha = 0.7f),
)

private fun Color.lighten(fraction: Float): Color {
    val r = red + (1f - red) * fraction
    val g = green + (1f - green) * fraction
    val b = blue + (1f - blue) * fraction
    return Color(r, g, b, alpha)
}

// ── Palettes ────────────────────────────────────────────────────────────

val ThemePalettes: Map<ThemePreset, PaletteTriple> = mapOf(

    // Matrix green — pitch black bg, pure neon
    ThemePreset.NEON to PaletteTriple(
        dark = dark(Color(0xFF00FF41), Color(0xFF0A0F0A)),
        light = light(Color(0xFF1B8A2D), Color(0xFFF2F8F3)),
        oled = oled(Color(0xFF00FF41)),
        ultraContrast = ultraContrast(Color(0xFF00FF41)),
        lightUltraContrast = lightUltraContrast(Color(0xFF0D5C1A)),
    ),

    // Burnt orange — fire warmth, dark brown bg
    ThemePreset.EMBER to PaletteTriple(
        dark = dark(Color(0xFFFF5722), Color(0xFF1A0E08)),
        light = light(Color(0xFFBF360C), Color(0xFFFBF3F0)),
        oled = oled(Color(0xFFFF5722)),
        ultraContrast = ultraContrast(Color(0xFFFF6E40)),
        lightUltraContrast = lightUltraContrast(Color(0xFF8B2500)),
    ),

    // Icy cyan — cold blue-black bg
    ThemePreset.FROST to PaletteTriple(
        dark = dark(Color(0xFF80DEEA), Color(0xFF0A1620)),
        light = light(Color(0xFF00838F), Color(0xFFF0F8FA)),
        oled = oled(Color(0xFF80DEEA)),
        ultraContrast = ultraContrast(Color(0xFF84FFFF)),
        lightUltraContrast = lightUltraContrast(Color(0xFF005662)),
    ),

    // Hot pink — deep purple-black bg
    ThemePreset.SYNTHWAVE to PaletteTriple(
        dark = dark(Color(0xFFFF2D78), Color(0xFF120818)),
        light = light(Color(0xFFC51162), Color(0xFFFCF0F5)),
        oled = oled(Color(0xFFFF2D78)),
        ultraContrast = ultraContrast(Color(0xFFFF4081)),
        lightUltraContrast = lightUltraContrast(Color(0xFF880E4F)),
    ),

    // Yellow-green toxic — dark olive-black bg
    ThemePreset.ACID to PaletteTriple(
        dark = dark(Color(0xFFC6FF00), Color(0xFF0E1208)),
        light = light(Color(0xFF689F38), Color(0xFFF4F8F0)),
        oled = oled(Color(0xFFC6FF00)),
        ultraContrast = ultraContrast(Color(0xFFCCFF90)),
        lightUltraContrast = lightUltraContrast(Color(0xFF33691E)),
    ),

    // Deep blood red — very dark red-black bg
    ThemePreset.CRIMSON to PaletteTriple(
        dark = dark(Color(0xFFFF1744), Color(0xFF180808)),
        light = light(Color(0xFFB71C1C), Color(0xFFFCF0F1)),
        oled = oled(Color(0xFFFF1744)),
        ultraContrast = ultraContrast(Color(0xFFFF5252)),
        lightUltraContrast = lightUltraContrast(Color(0xFF7F0000)),
    ),

    // Silver ghost — near-black bg, achromatic
    ThemePreset.PHANTOM to PaletteTriple(
        dark = dark(Color(0xFFCFD8DC), Color(0xFF0C0E10)),
        light = light(Color(0xFF546E7A), Color(0xFFF4F6F8)),
        oled = oled(Color(0xFFCFD8DC)),
        ultraContrast = ultraContrast(Color(0xFFECEFF1)),
        lightUltraContrast = lightUltraContrast(Color(0xFF263238)),
    ),

    // Deep electric blue — navy-black bg
    ThemePreset.COBALT to PaletteTriple(
        dark = dark(Color(0xFF2979FF), Color(0xFF080E1A)),
        light = light(Color(0xFF1565C0), Color(0xFFF0F4FA)),
        oled = oled(Color(0xFF2979FF)),
        ultraContrast = ultraContrast(Color(0xFF448AFF)),
        lightUltraContrast = lightUltraContrast(Color(0xFF0D47A1)),
    ),
)

// ── Light helper ────────────────────────────────────────────────────────

private fun light(primary: Color, bg: Color) = ColorSchemeColors(
    primary = primary, onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = 0.12f), onPrimaryContainer = primary,
    secondary = primary.copy(alpha = 0.75f), onSecondary = Color.White,
    secondaryContainer = primary.copy(alpha = 0.08f), onSecondaryContainer = primary.copy(alpha = 0.8f),
    tertiary = primary.copy(alpha = 0.6f), onTertiary = Color.White,
    tertiaryContainer = primary.copy(alpha = 0.06f), onTertiaryContainer = primary.copy(alpha = 0.7f),
    error = Color(0xFFD32F2F), onError = Color.White,
    errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFF3D0000),
    background = bg, onBackground = Color(0xFF1A1C1E),
    surface = primary.copy(alpha = 0.03f), onSurface = Color(0xFF1A1C1E),
    surfaceVariant = primary.copy(alpha = 0.06f), onSurfaceVariant = primary.copy(alpha = 0.55f),
    surfaceContainer = primary.copy(alpha = 0.07f),
    surfaceContainerHigh = primary.copy(alpha = 0.10f),
    surfaceContainerHighest = primary.copy(alpha = 0.14f),
    outline = primary.copy(alpha = 0.30f), outlineVariant = primary.copy(alpha = 0.12f),
    scrim = Color.Black,
    inverseSurface = Color(0xFF2F3033), inverseOnSurface = Color(0xFFF0F0F3),
    inversePrimary = primary.copy(alpha = 0.7f),
)

private fun lightUltraContrast(
    primary: Color,
    secondary: Color = primary.copy(alpha = 0.8f),
    tertiary: Color = primary.copy(alpha = 0.65f),
    error: Color = Color(0xFFD32F2F),
) = ColorSchemeColors(
    primary = primary, onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = 0.15f), onPrimaryContainer = primary,
    secondary = secondary, onSecondary = Color.White,
    secondaryContainer = secondary.copy(alpha = 0.12f), onSecondaryContainer = secondary,
    tertiary = tertiary, onTertiary = Color.White,
    tertiaryContainer = tertiary.copy(alpha = 0.10f), onTertiaryContainer = tertiary,
    error = error, onError = Color.White,
    errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFF3D0000),
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFF5F5F5), onSurfaceVariant = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFFFAFAFA), surfaceContainerHigh = Color(0xFFF0F0F0),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    outline = Color(0xFF333333), outlineVariant = Color(0xFF999999),
    scrim = Color.Black,
    inverseSurface = Color(0xFF1A1A1A), inverseOnSurface = Color.White,
    inversePrimary = primary.copy(alpha = 0.7f),
)
