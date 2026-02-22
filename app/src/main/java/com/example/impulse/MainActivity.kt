package com.example.impulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.impulse.data.ThemePreferences
import com.example.impulse.ui.screens.MainScreen
import com.example.impulse.ui.theme.ImpulseTheme
import com.example.impulse.ui.theme.ThemeSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализируем настройки темы
        val themePreferences = ThemePreferences(applicationContext)
        ThemeSettings.initialize(themePreferences)

        setContent {
            ImpulseTheme {
                MainScreen()
            }
        }
    }
}