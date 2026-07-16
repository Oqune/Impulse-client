package com.example.impulse.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.example.impulse.data.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

enum class ThemeMode {
    LIGHT, DARK, SYSTEM, OLED
}

@Serializable
data class DynamicColor(
    val hue: Float = 210f,
    val saturation: Float = 0.85f,
    val lightness: Float = 0.5f,
    val alpha: Float = 1.0f
) {
    fun toColor(isDark: Boolean = false): Color {
        val c = (1 - kotlin.math.abs(2 * lightness - 1)) * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val m = lightness - c / 2

        val (r, g, b) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val red = ((r + m) * 255).toInt()
        val green = ((g + m) * 255).toInt()
        val blue = ((b + m) * 255).toInt()

        return Color(android.graphics.Color.argb(
            (alpha * 255).toInt(),
            red,
            green,
            blue
        ))
    }

    fun lightColor(isDark: Boolean = false): Color = toColor(isDark)
    fun darkColor(isDark: Boolean = false): Color = toColor(isDark)
}

object ThemeSettings {
    private var preferences: ThemePreferences? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var _themeMode by mutableStateOf(ThemeMode.SYSTEM)
    val themeMode: ThemeMode get() = _themeMode

    private var _accentColor by mutableStateOf(DynamicColor(210f, 0.85f, 0.5f))
    val accentColor: DynamicColor get() = _accentColor

    private var _fontSize by mutableStateOf(FontSize.MEDIUM)
    val fontSize: FontSize get() = _fontSize

    private var _fontScale by mutableStateOf(1.0f)
    val fontScale: Float get() = _fontScale

    val isOLEDMode: Boolean get() = _themeMode == ThemeMode.OLED

    fun initialize(themePreferences: ThemePreferences) {
        preferences = themePreferences
        scope.launch { themePreferences.themeModeFlow.collect { _themeMode = it } }
        scope.launch { themePreferences.accentColorFlow.collect { _accentColor = it } }
        scope.launch { themePreferences.fontSizeFlow.collect { _fontSize = it } }
        scope.launch { themePreferences.fontScaleFlow.collect { _fontScale = it } }
    }

    fun setThemeMode(mode: ThemeMode) { _themeMode = mode; preferences?.saveThemeMode(mode) }
    fun setAccentColor(color: DynamicColor) { _accentColor = color; preferences?.saveAccentColor(color) }
    fun setFontSize(size: FontSize) { _fontSize = size; preferences?.saveFontSize(size) }
    fun setFontScale(scale: Float) { _fontScale = scale.coerceIn(0.8f, 1.4f); preferences?.saveFontScale(_fontScale) }
}
