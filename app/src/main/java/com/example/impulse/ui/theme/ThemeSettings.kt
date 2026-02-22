package com.example.impulse.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.example.impulse.data.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Enum для выбора темы
enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

// Enum для акцентных цветов
// Enum для акцентных цветов
enum class AccentColor(val displayName: String, val lightColor: Color, val darkColor: Color) {
    BLUE("Синий", Color(0xFF2196F3), Color(0xFF64B5F6)),
    GREEN("Зеленый", Color(0xFF4CAF50), Color(0xFF81C784)),
    PURPLE("Фиолетовый", Color(0xFF9C27B0), Color(0xFFCE93D8)),
    RED("Красный", Color(0xFFE53935), Color(0xFFEF5350)),
    TEAL("Бирюзовый", Color(0xFF009688), Color(0xFF4DB6AC)),
    PINK("Розовый", Color(0xFFE91E63), Color(0xFFF48FB1)),
    AMBER("Желтый", Color(0xFFFFC107), Color(0xFFFFD54F)),
    LIME("Лаймовый", Color(0xFFCDDC39), Color(0xFFDCE775))
}

// Singleton для хранения настроек темы
object ThemeSettings {
    private var preferences: ThemePreferences? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private var _themeMode by mutableStateOf(ThemeMode.SYSTEM)
    val themeMode: ThemeMode
        get() = _themeMode

    private var _accentColor by mutableStateOf(AccentColor.BLUE)
    val accentColor: AccentColor
        get() = _accentColor

    // Инициализация с загрузкой сохранённых настроек
    fun initialize(themePreferences: ThemePreferences) {
        preferences = themePreferences

        // Загружаем сохранённые настройки
        scope.launch {
            themePreferences.themeModeFlow.collect { mode ->
                _themeMode = mode
            }
        }

        scope.launch {
            themePreferences.accentColorFlow.collect { color ->
                _accentColor = color
            }
        }
    }

    // Установка режима темы с сохранением
    fun setThemeMode(mode: ThemeMode) {
        _themeMode = mode
        preferences?.saveThemeMode(mode)
    }

    // Установка акцентного цвета с сохранением
    fun setAccentColor(color: AccentColor) {
        _accentColor = color
        preferences?.saveAccentColor(color)
    }
}