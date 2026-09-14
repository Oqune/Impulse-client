package com.example.impulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.impulse.R

val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono))
// Orbitron lacks Cyrillic glyphs which caused Russian text to fall back to generic system font while English used Orbitron.
// Unifying brand header typography on JetBrainsMono provides 100% harmonious Latin + Cyrillic glyphs across all Android devices.
val Orbitron = JetBrainsMono

enum class FontSize(val scale: Float, val displayName: String) {
    SMALL(0.85f, "Маленький"),
    MEDIUM(1.0f, "Средний"),
    LARGE(1.15f, "Большой")
}

fun getTypography(scale: Float = 1.0f): Typography {
    val s = scale.coerceIn(0.8f, 1.4f)
    val bodyFont = FontFamily.Default
    val brandHeaderFont = JetBrainsMono
    val titleFont = JetBrainsMono

    return Typography(
        displayLarge = TextStyle(
            fontFamily = brandHeaderFont,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(57.sp, s),
            lineHeight = scaledFontSize(64.sp, s),
            letterSpacing = (-1).sp,
        ),
        displayMedium = TextStyle(
            fontFamily = brandHeaderFont,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(45.sp, s),
            lineHeight = scaledFontSize(52.sp, s),
            letterSpacing = (-0.5).sp,
        ),
        displaySmall = TextStyle(
            fontFamily = brandHeaderFont,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(36.sp, s),
            lineHeight = scaledFontSize(44.sp, s),
            letterSpacing = (-0.25).sp,
        ),

        headlineLarge = TextStyle(
            fontFamily = brandHeaderFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(32.sp, s),
            lineHeight = scaledFontSize(40.sp, s),
            letterSpacing = 0.sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = brandHeaderFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(28.sp, s),
            lineHeight = scaledFontSize(36.sp, s),
            letterSpacing = 0.sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = titleFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(24.sp, s),
            lineHeight = scaledFontSize(32.sp, s),
            letterSpacing = (-0.25).sp,
        ),

        titleLarge = TextStyle(
            fontFamily = titleFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(22.sp, s),
            lineHeight = scaledFontSize(28.sp, s),
            letterSpacing = 0.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = titleFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(16.sp, s),
            lineHeight = scaledFontSize(24.sp, s),
            letterSpacing = 0.5.sp,
        ),
        titleSmall = TextStyle(
            fontFamily = titleFont,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(14.sp, s),
            lineHeight = scaledFontSize(20.sp, s),
            letterSpacing = 0.3.sp,
        ),

        bodyLarge = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = scaledFontSize(16.sp, s),
            lineHeight = scaledFontSize(25.sp, s),
            letterSpacing = 0.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = scaledFontSize(14.sp, s),
            lineHeight = scaledFontSize(21.sp, s),
            letterSpacing = 0.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = scaledFontSize(12.sp, s),
            lineHeight = scaledFontSize(16.sp, s),
            letterSpacing = 0.sp,
        ),

        labelLarge = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(14.sp, s),
            lineHeight = scaledFontSize(20.sp, s),
            letterSpacing = 0.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(12.sp, s),
            lineHeight = scaledFontSize(16.sp, s),
            letterSpacing = 0.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(11.sp, s),
            lineHeight = scaledFontSize(16.sp, s),
            letterSpacing = 0.sp,
        ),
    )
}

fun scaledFontSize(baseSp: androidx.compose.ui.unit.TextUnit, scale: Float): androidx.compose.ui.unit.TextUnit {
    return (baseSp.value * scale).sp
}
