package com.example.impulse.locale

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object LocaleSettings {
    private var preferences: LocalePreferences? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var _languageCode by mutableStateOf(LocalePreferences.DEFAULT_LANGUAGE)
    val languageCode: String get() = _languageCode

    fun initialize(localePreferences: LocalePreferences) {
        preferences = localePreferences
        scope.launch { localePreferences.languageFlow.collect { _languageCode = it } }
    }

    fun setLanguage(code: String) {
        _languageCode = code
        preferences?.saveLanguage(code)
    }
}
