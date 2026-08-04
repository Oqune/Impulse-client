package com.example.impulse

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.locale.LocalePreferences
import com.example.impulse.locale.LocaleSettings
import com.example.impulse.ui.theme.ThemePreferences
import com.example.impulse.ui.screens.BiometricLockScreen
import com.example.impulse.ui.screens.MainScreen
import com.example.impulse.ui.theme.ImpulseTheme
import com.example.impulse.ui.theme.ThemeSettings
import com.example.impulse.util.LogManager
import com.example.impulse.util.NameGenerator
import java.util.Locale

/**
 * Privacy-first: no background connection. The connection lives only while the
 * app is in the foreground. [onStart] reconnects to the selected server;
 * [onStop] disconnects cleanly so no session lingers in the background.
 */
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
        // Reconnect to the selected server when the app comes to the foreground.
        try {
            val prefs = ServerPreferences(applicationContext)
            val server = prefs.getSelectedServer()
            if (server != null) {
                val clientName = prefs.getClientName().takeIf { it.isNotBlank() }
                    ?: NameGenerator.generate()
                LogManager.i(TAG, "onStart: connecting to ${server.id}")
                ConnectionManager.getInstance(this).connect(server, clientName)
            }
        } catch (t: Throwable) {
            // Never let an exception in lifecycle connection tear down the app;
            // log the full stack so it can be retrieved without logcat.
            LogManager.e(TAG, "onStart connect failed", t)
            com.example.impulse.util.CrashLog.writeCrash(
                com.example.impulse.util.CrashLog.buildCrashReport(
                    thread = Thread.currentThread(),
                    throwable = t,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    sdkInt = android.os.Build.VERSION.SDK_INT,
                    release = android.os.Build.VERSION.RELEASE,
                    manufacturer = android.os.Build.MANUFACTURER,
                    model = android.os.Build.MODEL,
                    timeMillis = System.currentTimeMillis(),
                    extra = "MainActivity.onStart",
                )
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Privacy: drop the connection when leaving the foreground. No session
        // persists in the background; no messages are delivered while hidden.
        LogManager.i(TAG, "onStop: disconnecting all (privacy no-background)")
        try {
            ConnectionManager.getInstance(this).disconnectAll()
        } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
