package com.example.impulse

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.impulse.locale.LocalePreferences
import com.example.impulse.locale.LocaleSettings
import com.example.impulse.ui.theme.ThemePreferences
import com.example.impulse.service.WebTransportForegroundService
import com.example.impulse.ui.screens.BiometricLockScreen
import com.example.impulse.ui.screens.MainScreen
import com.example.impulse.ui.theme.ImpulseTheme
import com.example.impulse.ui.theme.ThemeSettings
import java.util.Locale

class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val localePreferences = LocalePreferences(newBase)
        val lang = localePreferences.getSavedLanguage()
        if (lang.isNotEmpty()) {
            val locale = Locale.forLanguageTag(lang)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val localePreferences = LocalePreferences(applicationContext)
        LocaleSettings.initialize(localePreferences)

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val themePreferences = ThemePreferences(applicationContext)
        ThemeSettings.initialize(themePreferences)

        setContent {
            ImpulseTheme {
                var isUnlocked = remember { mutableStateOf(false) }
                val biometricHelper = com.example.impulse.util.BiometricHelper(applicationContext)

                if (isUnlocked.value.not() && biometricHelper.isBiometricAvailable()) {
                    BiometricLockScreen(
                        onUnlock = { isUnlocked.value = true }
                    )
                } else {
                    MainScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Keep the WebTransport connection alive in the foreground service,
        // even while the app is in the background.
        val serviceIntent = Intent(this, WebTransportForegroundService::class.java).apply {
            action = "START"
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onStop() {
        super.onStop()
        // The connection must stay alive after minimizing the app. Always (re)start
        // the foreground service so the transport is never dropped.
        val serviceIntent = Intent(this, WebTransportForegroundService::class.java).apply {
            action = "START"
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
