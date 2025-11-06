package com.example.impulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.impulse.ui.screens.MainScreen
import com.example.impulse.ui.theme.ImpulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ImpulseTheme {
                MainScreen()
            }
        }
    }
}