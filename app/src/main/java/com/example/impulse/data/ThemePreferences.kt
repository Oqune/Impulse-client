package com.example.impulse.data

import android.content.Context
import android.content.SharedPreferences
import com.example.impulse.ui.theme.AccentColor
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
        private const val ACCENT_COLOR_KEY = "accent_color"
    }

    // StateFlow для режима темы
    private val _themeModeFlow = MutableStateFlow(getThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow.asStateFlow()

    // StateFlow для акцентного цвета
    private val _accentColorFlow = MutableStateFlow(getAccentColor())
    val accentColorFlow: StateFlow<AccentColor> = _accentColorFlow.asStateFlow()

    // Получение режима темы
    private fun getThemeMode(): ThemeMode {
        val themeModeString = prefs.getString(THEME_MODE_KEY, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(themeModeString ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    // Получение акцентного цвета
    private fun getAccentColor(): AccentColor {
        val accentColorString = prefs.getString(ACCENT_COLOR_KEY, AccentColor.BLUE.name)
        return try {
            AccentColor.valueOf(accentColorString ?: AccentColor.BLUE.name)
        } catch (e: IllegalArgumentException) {
            AccentColor.BLUE
        }
    }

    // Сохранение режима темы
    fun saveThemeMode(themeMode: ThemeMode) {
        prefs.edit().putString(THEME_MODE_KEY, themeMode.name).apply()
        _themeModeFlow.value = themeMode
    }

    // Сохранение акцентного цвета
    fun saveAccentColor(accentColor: AccentColor) {
        prefs.edit().putString(ACCENT_COLOR_KEY, accentColor.name).apply()
        _accentColorFlow.value = accentColor
    }
}