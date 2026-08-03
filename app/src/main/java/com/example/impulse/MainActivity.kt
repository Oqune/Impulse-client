package com.example.impulse

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

        requestIgnoreBatteryOptimizations()

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

    /**
     * Ask the user to exempt this app from battery optimizations so the QUIC
     * connection survives Doze. Without this, Doze suspends UDP/QUIC traffic
     * ~15 min after screen-off and the background connection silently dies
     * (Bug: "app minimized -> connection dies"). The system shows a one-time
     * dialog; if already exempted or denied, this is a no-op.
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // No activity to handle it (rare); fall back to app details.
        }
    }
}
