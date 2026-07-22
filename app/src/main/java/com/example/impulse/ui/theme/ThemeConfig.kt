package com.example.impulse.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ThemeConfig {
    var preset by mutableStateOf(ThemePreset.CYBER_BLUE)

    var glowEnabled by mutableStateOf(false)
    var glitchEnabled by mutableStateOf(false)
    var glassEnabled by mutableStateOf(false)
    var terminalCursorEnabled by mutableStateOf(false)
}
