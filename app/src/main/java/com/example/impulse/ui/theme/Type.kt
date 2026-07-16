package com.example.impulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Stylized type system for Impulse.
 *  - Display & headline use a slightly tighter, heavier treatment for a
 *     confident, modern look.
 *  - Body text uses comfortable line-height and near-zero tracking for
 *     effortless reading.
 *  - Labels keep a touch of positive tracking so they read as UI chrome.
 *
 * Uses the platform default sans-serif stack (safe, never crashes). A bundled
 * niche typeface (e.g. Manrope) can be dropped into res/font later and wired
 * here once a verified TTF is available.
 */
val AppFontFamily = FontFamily.SansSerif

enum class FontSize(val scale: Float, val displayName: String) {
    SMALL(0.85f, "Маленький"),
    MEDIUM(1.0f, "Средний"),
    LARGE(1.15f, "Большой")
}

/** Continuous font scale (0.8f..1.4f). Kept for backward compatibility. */
fun getTypography(fontSize: FontSize = FontSize.MEDIUM): Typography = getTypography(fontSize.scale)

fun getTypography(scale: Float = 1.0f): Typography {
    val s = scale.coerceIn(0.8f, 1.4f)

    return Typography(
        displayLarge = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(57.sp, s),
            lineHeight = scaledFontSize(64.sp, s),
            letterSpacing = (-0.5).sp
        ),
        displayMedium = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(45.sp, s),
            lineHeight = scaledFontSize(52.sp, s),
            letterSpacing = (-0.25).sp
        ),
        displaySmall = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(36.sp, s),
            lineHeight = scaledFontSize(44.sp, s),
            letterSpacing = (-0.15).sp
        ),

        headlineLarge = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(32.sp, s),
            lineHeight = scaledFontSize(40.sp, s),
            letterSpacing = (-0.1).sp
        ),
        headlineMedium = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(28.sp, s),
            lineHeight = scaledFontSize(36.sp, s),
            letterSpacing = (-0.1).sp
        ),
        headlineSmall = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(24.sp, s),
            lineHeight = scaledFontSize(32.sp, s),
            letterSpacing = 0.sp
        ),

        titleLarge = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(22.sp, s),
            lineHeight = scaledFontSize(28.sp, s),
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(16.sp, s),
            lineHeight = scaledFontSize(24.sp, s),
            letterSpacing = 0.1.sp
        ),
        titleSmall = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(14.sp, s),
            lineHeight = scaledFontSize(20.sp, s),
            letterSpacing = 0.1.sp
        ),

        bodyLarge = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = scaledFontSize(16.sp, s),
            lineHeight = scaledFontSize(25.sp, s),
            letterSpacing = 0.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = scaledFontSize(14.sp, s),
            lineHeight = scaledFontSize(21.sp, s),
            letterSpacing = 0.1.sp
        ),
        bodySmall = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = scaledFontSize(12.sp, s),
            lineHeight = scaledFontSize(16.sp, s),
            letterSpacing = 0.2.sp
        ),

        labelLarge = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(14.sp, s),
            lineHeight = scaledFontSize(20.sp, s),
            letterSpacing = 0.1.sp
        ),
        labelMedium = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(12.sp, s),
            lineHeight = scaledFontSize(16.sp, s),
            letterSpacing = 0.4.sp
        ),
        labelSmall = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(11.sp, s),
            lineHeight = scaledFontSize(16.sp, s),
            letterSpacing = 0.4.sp
        )
    )
}

fun scaledFontSize(baseSp: androidx.compose.ui.unit.TextUnit, scale: Float): androidx.compose.ui.unit.TextUnit {
    return (baseSp.value * scale).sp
}
