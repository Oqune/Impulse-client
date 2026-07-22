package com.example.impulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.impulse.R

val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono))
val Orbitron = FontFamily(Font(R.font.orbitron))

enum class FontSize(val scale: Float, val displayName: String) {
    SMALL(0.85f, "Маленький"),
    MEDIUM(1.0f, "Средний"),
    LARGE(1.15f, "Большой")
}

fun getTypography(scale: Float = 1.0f): Typography {
    val s = scale.coerceIn(0.8f, 1.4f)

    return Typography(
        displayLarge = TextStyle(
            fontFamily = Orbitron,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(57.sp, s),
            lineHeight = scaledFontSize(64.sp, s),
            letterSpacing = (-1).sp,
        ),
        displayMedium = TextStyle(
            fontFamily = Orbitron,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(45.sp, s),
            lineHeight = scaledFontSize(52.sp, s),
            letterSpacing = (-0.5).sp,
        ),
        displaySmall = TextStyle(
            fontFamily = Orbitron,
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize(36.sp, s),
            lineHeight = scaledFontSize(44.sp, s),
            letterSpacing = (-0.25).sp,
        ),

        headlineLarge = TextStyle(
            fontFamily = Orbitron,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(32.sp, s),
            lineHeight = scaledFontSize(40.sp, s),
            letterSpacing = 0.sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = Orbitron,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(28.sp, s),
            lineHeight = scaledFontSize(36.sp, s),
            letterSpacing = 0.sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(24.sp, s),
            lineHeight = scaledFontSize(32.sp, s),
            letterSpacing = (-0.25).sp,
        ),

        titleLarge = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(22.sp, s),
            lineHeight = scaledFontSize(28.sp, s),
            letterSpacing = 0.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledFontSize(16.sp, s),
            lineHeight = scaledFontSize(24.sp, s),
            letterSpacing = 0.5.sp,
        ),
        titleSmall = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(14.sp, s),
            lineHeight = scaledFontSize(20.sp, s),
            letterSpacing = 0.3.sp,
        ),

        bodyLarge = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Normal,
            fontSize = scaledFontSize(16.sp, s),
            lineHeight = scaledFontSize(25.sp, s),
            letterSpacing = 0.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Normal,
            fontSize = scaledFontSize(14.sp, s),
            lineHeight = scaledFontSize(21.sp, s),
            letterSpacing = 0.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Normal,
            fontSize = scaledFontSize(12.sp, s),
            lineHeight = scaledFontSize(16.sp, s),
            letterSpacing = 0.sp,
        ),

        labelLarge = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(14.sp, s),
            lineHeight = scaledFontSize(20.sp, s),
            letterSpacing = 0.5.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(12.sp, s),
            lineHeight = scaledFontSize(16.sp, s),
            letterSpacing = 0.8.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Medium,
            fontSize = scaledFontSize(11.sp, s),
            lineHeight = scaledFontSize(16.sp, s),
            letterSpacing = 0.5.sp,
        ),
    )
}

fun getTypography(fontSize: FontSize = FontSize.MEDIUM): Typography = getTypography(fontSize.scale)

fun scaledFontSize(baseSp: androidx.compose.ui.unit.TextUnit, scale: Float): androidx.compose.ui.unit.TextUnit {
    return (baseSp.value * scale).sp
}
