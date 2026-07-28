package com.example.impulse

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.FragmentActivity
import com.example.impulse.data.ServerPreferences
import com.example.impulse.locale.LocalePreferences
import com.example.impulse.locale.LocaleSettings
import com.example.impulse.ui.theme.ThemePreferences
import com.example.impulse.service.WebTransportForegroundService
import com.example.impulse.ui.screens.BiometricLockScreen
import com.example.impulse.ui.screens.MainScreen
import com.example.impulse.ui.theme.ImpulseTheme
import com.example.impulse.ui.theme.ThemeSettings

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val localePreferences = LocalePreferences(applicationContext)
        LocaleSettings.initialize(localePreferences)
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(LocaleSettings.languageCode)
        )

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val themePreferences = ThemePreferences(applicationContext)
        ThemeSettings.initialize(themePreferences)

        val serverPreferences = ServerPreferences(applicationContext)

        setContent {
            ImpulseTheme {
                var isUnlocked = remember { mutableStateOf(false) }
                val biometricHelper = com.example.impulse.util.BiometricHelper(applicationContext)
                val biometricEnabled = serverPreferences.getBiometricEnabled()

                if (isUnlocked.value.not() && biometricEnabled && biometricHelper.isBiometricAvailable()) {
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
