package com.example.impulse.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// Enum для выбора темы
enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

// Enum для акцентных цветов
enum class AccentColor(val displayName: String, val lightColor: Color, val darkColor: Color) {
    BLUE("Синий", Color(0xFF3F51B5), Color(0xFF8FA8FF)),
    GREEN("Зелёный", Color(0xFF4CAF50), Color(0xFF81C784)),
    PURPLE("Фиолетовый", Color(0xFF9C27B0), Color(0xFFCE93D8)),
    RED("Красный", Color(0xFFE53935), Color(0xFFEF5350)),
    ORANGE("Оранжевый", Color(0xFFFF6F00), Color(0xFFFFB74D)),
    TEAL("Бирюзовый", Color(0xFF009688), Color(0xFF80DEEA)),
    PINK("Розовый", Color(0xFFE91E63), Color(0xFFF48FB1)),
    INDIGO("Индиго", Color(0xFF3F51B5), Color(0xFF9FA8DA))
}

// Singleton для хранения настроек темы
object ThemeSettings {
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    var accentColor by mutableStateOf(AccentColor.BLUE)
}