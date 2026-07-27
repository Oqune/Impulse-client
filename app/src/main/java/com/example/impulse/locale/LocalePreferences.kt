package com.example.impulse.locale

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalePreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "locale_preferences",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val LANGUAGE_KEY = "language_code"
        const val DEFAULT_LANGUAGE = "en"
    }

    private val _languageFlow = MutableStateFlow(getLanguage())
    val languageFlow: StateFlow<String> = _languageFlow.asStateFlow()

    private fun getLanguage(): String = prefs.getString(LANGUAGE_KEY, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE

    fun saveLanguage(code: String) {
        prefs.edit().putString(LANGUAGE_KEY, code).apply()
        _languageFlow.value = code
    }
}
