package com.example.impulse.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
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
}

object ThemeSettings {
    private var preferences: ThemePreferences? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var _themeMode by mutableStateOf(ThemeMode.SYSTEM)
    val themeMode: ThemeMode get() = _themeMode

    private var _accentColor by mutableStateOf(DynamicColor(210f, 0.85f, 0.5f))
    val accentColor: DynamicColor get() = _accentColor

    private var _fontScale by mutableStateOf(1.0f)
    val fontScale: Float get() = _fontScale

    private var _preset by mutableStateOf(ThemePreset.NEON)
    val preset: ThemePreset get() = _preset

    private var _hue by mutableStateOf(140f)
    val hue: Float get() = _hue

    private var _oledEnabled by mutableStateOf(false)
    val oledEnabled: Boolean get() = _oledEnabled

    private var _ultraContrastEnabled by mutableStateOf(false)
    val ultraContrastEnabled: Boolean get() = _ultraContrastEnabled

    val isDarkActive: Boolean
        get() = when (_themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> _isSystemDark
            ThemeMode.LIGHT -> false
        }

    private var _isSystemDark = false
    fun setSystemDark(dark: Boolean) { _isSystemDark = dark }

    fun initialize(themePreferences: ThemePreferences) {
        preferences = themePreferences
        scope.launch { themePreferences.themeModeFlow.collect { _themeMode = it } }
        scope.launch { themePreferences.accentColorFlow.collect { _accentColor = it } }
        scope.launch { themePreferences.fontScaleFlow.collect { _fontScale = it } }
        scope.launch { themePreferences.themePresetFlow.collect { _preset = it } }
        scope.launch { themePreferences.hueFlow.collect { _hue = it } }
        scope.launch { themePreferences.oledFlow.collect { _oledEnabled = it } }
        scope.launch { themePreferences.ultraContrastFlow.collect { _ultraContrastEnabled = it } }
    }

    fun setThemeMode(mode: ThemeMode) { _themeMode = mode; preferences?.saveThemeMode(mode) }
    fun setFontScale(scale: Float) { _fontScale = scale.coerceIn(0.8f, 1.4f); preferences?.saveFontScale(_fontScale) }
    fun setHue(hue: Float) { _hue = hue.coerceIn(0f, 360f); preferences?.saveHue(_hue) }

    fun setOledEnabled(enabled: Boolean) {
        _oledEnabled = enabled
        if (enabled) _ultraContrastEnabled = false
        preferences?.saveOled(enabled)
        if (enabled) preferences?.saveUltraContrast(false)
    }

    fun setUltraContrastEnabled(enabled: Boolean) {
        _ultraContrastEnabled = enabled
        if (enabled) _oledEnabled = false
        preferences?.saveUltraContrast(enabled)
        if (enabled) preferences?.saveOled(false)
    }
}
