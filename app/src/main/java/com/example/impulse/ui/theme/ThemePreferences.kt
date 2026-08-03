package com.example.impulse.ui.theme

import android.content.Context
import android.content.SharedPreferences
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
        private const val FONT_SCALE_KEY = "font_scale"
        private const val THEME_PRESET_KEY = "theme_preset"
        private const val HUE_KEY = "theme_hue"
        private const val OLED_KEY = "oled_enabled"
        private const val ULTRA_CONTRAST_KEY = "ultra_contrast_enabled"
        private const val THEME_VARIANT_KEY = "theme_variant"
    }

    private val _themeModeFlow = MutableStateFlow(getThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow.asStateFlow()

    private val _accentColorFlow = MutableStateFlow(getAccentColor())
    val accentColorFlow: StateFlow<DynamicColor> = _accentColorFlow.asStateFlow()

    private val _fontScaleFlow = MutableStateFlow(getFontScale())
    val fontScaleFlow: StateFlow<Float> = _fontScaleFlow.asStateFlow()

    private val _themePresetFlow = MutableStateFlow(getThemePreset())
    val themePresetFlow: StateFlow<ThemePreset> = _themePresetFlow.asStateFlow()

    private val _hueFlow = MutableStateFlow(getHue())
    val hueFlow: StateFlow<Float> = _hueFlow.asStateFlow()

    private val _themeVariantFlow = MutableStateFlow(getThemeVariant())
    val themeVariantFlow: StateFlow<ThemeVariant> = _themeVariantFlow.asStateFlow()

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

    private fun getThemePreset(): ThemePreset {
        val name = prefs.getString(THEME_PRESET_KEY, ThemePreset.NEON.name)
        return try {
            ThemePreset.valueOf(name ?: ThemePreset.NEON.name)
        } catch (e: IllegalArgumentException) {
            ThemePreset.NEON
        }
    }

    private fun getHue(): Float = prefs.getFloat(HUE_KEY, 140f)

    /**
     * Read the theme variant, migrating legacy boolean flags (OLED / Ultra
     * Contrast) that predate the unified enum.
     */
    private fun getThemeVariant(): ThemeVariant {
        val stored = prefs.getString(THEME_VARIANT_KEY, null)
        if (stored != null) {
            return try {
                ThemeVariant.valueOf(stored)
            } catch (_: IllegalArgumentException) {
                ThemeVariant.CLASSIC
            }
        }
        // Migration from v2.6.0 booleans.
        return when {
            prefs.getBoolean(OLED_KEY, false) -> ThemeVariant.OLED
            prefs.getBoolean(ULTRA_CONTRAST_KEY, false) -> ThemeVariant.ULTRA_CONTRAST
            else -> ThemeVariant.CLASSIC
        }.also { prefs.edit().putString(THEME_VARIANT_KEY, it.name).apply() }
    }

    fun saveThemeMode(themeMode: ThemeMode) {
        prefs.edit().putString(THEME_MODE_KEY, themeMode.name).apply()
        _themeModeFlow.value = themeMode
    }

    fun saveFontScale(scale: Float) {
        prefs.edit().putFloat(FONT_SCALE_KEY, scale).apply()
        _fontScaleFlow.value = scale
    }

    private fun getFontScale(): Float {
        return prefs.getFloat(FONT_SCALE_KEY, 1.0f).coerceIn(0.8f, 1.4f)
    }

    fun saveHue(hue: Float) {
        prefs.edit().putFloat(HUE_KEY, hue).apply()
        _hueFlow.value = hue
    }

    fun saveThemeVariant(variant: ThemeVariant) {
        prefs.edit().putString(THEME_VARIANT_KEY, variant.name).apply()
        _themeVariantFlow.value = variant
    }
}
