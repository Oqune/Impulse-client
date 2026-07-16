package com.example.impulse.data

import android.content.Context
import android.content.SharedPreferences
import com.example.impulse.ui.theme.DynamicColor
import com.example.impulse.ui.theme.FontSize
import com.example.impulse.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemePreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "theme_preferences",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val THEME_MODE_KEY = "theme_mode"
        private const val ACCENT_HUE_KEY = "accent_hue"
        private const val ACCENT_SATURATION_KEY = "accent_saturation"
        private const val ACCENT_LIGHTNESS_KEY = "accent_lightness"
        private const val ACCENT_ALPHA_KEY = "accent_alpha"
        private const val FONT_SIZE_KEY = "font_size"
        private const val FONT_SCALE_KEY = "font_scale"
    }

    private val _themeModeFlow = MutableStateFlow(getThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow.asStateFlow()

    private val _accentColorFlow = MutableStateFlow(getAccentColor())
    val accentColorFlow: StateFlow<DynamicColor> = _accentColorFlow.asStateFlow()

    private val _fontSizeFlow = MutableStateFlow(getFontSize())
    val fontSizeFlow: StateFlow<FontSize> = _fontSizeFlow.asStateFlow()

    private val _fontScaleFlow = MutableStateFlow(getFontScale())
    val fontScaleFlow: StateFlow<Float> = _fontScaleFlow.asStateFlow()

    private fun getThemeMode(): ThemeMode {
        val themeModeString = prefs.getString(THEME_MODE_KEY, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(themeModeString ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    private fun getAccentColor(): DynamicColor {
        val hue = prefs.getFloat(ACCENT_HUE_KEY, 210f)
        val saturation = prefs.getFloat(ACCENT_SATURATION_KEY, 0.85f)
        val lightness = prefs.getFloat(ACCENT_LIGHTNESS_KEY, 0.5f)
        val alpha = prefs.getFloat(ACCENT_ALPHA_KEY, 1.0f)
        return DynamicColor(hue, saturation, lightness, alpha)
    }

    private fun getFontSize(): FontSize {
        val fontSizeString = prefs.getString(FONT_SIZE_KEY, FontSize.MEDIUM.name)
        return try {
            FontSize.valueOf(fontSizeString ?: FontSize.MEDIUM.name)
        } catch (e: IllegalArgumentException) {
            FontSize.MEDIUM
        }
    }

    fun saveThemeMode(themeMode: ThemeMode) {
        prefs.edit().putString(THEME_MODE_KEY, themeMode.name).apply()
        _themeModeFlow.value = themeMode
    }

    fun saveAccentColor(accentColor: DynamicColor) {
        prefs.edit()
            .putFloat(ACCENT_HUE_KEY, accentColor.hue)
            .putFloat(ACCENT_SATURATION_KEY, accentColor.saturation)
            .putFloat(ACCENT_LIGHTNESS_KEY, accentColor.lightness)
            .putFloat(ACCENT_ALPHA_KEY, accentColor.alpha)
            .apply()
        _accentColorFlow.value = accentColor
    }

    fun saveFontSize(fontSize: FontSize) {
        prefs.edit().putString(FONT_SIZE_KEY, fontSize.name).apply()
        _fontSizeFlow.value = fontSize
    }

    private fun getFontScale(): Float {
        return prefs.getFloat(FONT_SCALE_KEY, 1.0f).coerceIn(0.8f, 1.4f)
    }

    fun saveFontScale(scale: Float) {
        prefs.edit().putFloat(FONT_SCALE_KEY, scale).apply()
        _fontScaleFlow.value = scale
    }
}