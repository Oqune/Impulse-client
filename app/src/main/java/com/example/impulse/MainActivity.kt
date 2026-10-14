package com.example.impulse

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
    private lateinit var unlockState: MainActivityUnlockState
    private lateinit var biometricHelper: com.example.impulse.util.BiometricHelper

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

        unlockState = ViewModelProvider(this)[MainActivityUnlockState::class.java]
        biometricHelper = com.example.impulse.util.BiometricHelper(applicationContext)

        enableEdgeToEdge()
        val serverPreferences = ServerPreferences(applicationContext)
        applyScreenshotProtection(serverPreferences.getAllowScreenshots())

        val themePreferences = ThemePreferences(applicationContext)
        ThemeSettings.initialize(themePreferences)

        setContent {
            ImpulseTheme {
                val isUnlocked = unlockState.isUnlocked.collectAsState().value
                val isActivityStarted = unlockState.isActivityStarted.collectAsState().value

                if (!isUnlocked && biometricHelper.isBiometricAvailable()) {
                    BiometricLockScreen(
                        autoPrompt = isActivityStarted,
                        onUnlock = {
                            // A prompt result may arrive after Home/onStop.
                            // It must not restore unlock state in the background.
                            if (unlockState.isActivityStarted.value &&
                                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                                unlockState.unlock()
                                connectSelectedServer()
                            } else {
                                LogManager.i(TAG, "Ignoring authentication result while Activity is stopped")
                            }
                        }
                    )
                } else {
                    MainScreen()
                }
            }
        }
    }

    fun applyScreenshotProtection(allowScreenshots: Boolean) {
        if (allowScreenshots) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    override fun onStart() {
        super.onStart()
        unlockState.setActivityStarted(true)
        if (biometricHelper.isBiometricAvailable() && !unlockState.isUnlocked.value) {
            LogManager.i(TAG, "onStart: waiting for biometric unlock before connecting")
            return
        }
        connectSelectedServer()
    }

    private fun connectSelectedServer() {
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
        unlockState.setActivityStarted(false)
        if (!isChangingConfigurations && biometricHelper.isBiometricAvailable()) {
            unlockState.lock()
        }
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

/** Retained across configuration changes, but not saved across process death. */
internal class MainActivityUnlockState : ViewModel() {
    private val _isUnlocked = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isUnlocked = _isUnlocked
    private val _isActivityStarted = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isActivityStarted = _isActivityStarted

    fun unlock() { _isUnlocked.value = true }
    fun lock() { _isUnlocked.value = false }
    fun setActivityStarted(started: Boolean) { _isActivityStarted.value = started }
}
