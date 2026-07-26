package com.example.impulse.util

import android.content.Context
import android.os.Build
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around [Timber] for structured logging in Impulse.
 *
 * Installs [DebugTree] for debug builds (detailed logcat) and [ReleaseTree]
 * for production (ERROR/ASSERT only). No file logging — use logcat directly.
 *
 * Usage:
 *   LogManager.d("ChatController") { "group secret derived from ${keys.size}" }
 *   LogManager.i("WebTransportClient", "session ready")
 *   LogManager.e("ChatController", "auth failed", throwable)
 */
object LogManager {

    private val initialized = AtomicBoolean(false)

    fun init(context: Context, isDebug: Boolean) {
        if (initialized.getAndSet(true)) return
        if (isDebug) {
            Timber.plant(DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        i("LogManager", "initialized (debug=$isDebug, sdk=${Build.VERSION.SDK_INT})")
    }

    // ---- Level helpers ----

    fun v(tag: String, message: String) = Timber.tag(tag).v(message)
    fun v(tag: String, lazy: () -> String) = Timber.tag(tag).v(lazy())
    fun d(tag: String, message: String) = Timber.tag(tag).d(message)
    fun d(tag: String, lazy: () -> String) = Timber.tag(tag).d(lazy())
    fun i(tag: String, message: String) = Timber.tag(tag).i(message)
    fun i(tag: String, lazy: () -> String) = Timber.tag(tag).i(lazy())
    fun w(tag: String, message: String) = Timber.tag(tag).w(message)
    fun w(tag: String, lazy: () -> String) = Timber.tag(tag).w(lazy())
    fun w(tag: String, message: String, t: Throwable) = Timber.tag(tag).w(t, message)
    fun e(tag: String, message: String, t: Throwable? = null) =
        if (t != null) Timber.tag(tag).e(t, message) else Timber.tag(tag).e(message)

    // ---- Privacy helpers ----

    fun shortHash(full: String?): String = full?.take(8)?.lowercase() ?: "—"

    fun redact(value: String?): String = if (value.isNullOrEmpty()) "—" else "<redacted>"

    internal fun redactSecrets(message: String): String {
        val hexMasked = message.replace(Regex("[0-9a-fA-F]{32,}"), "<hex-redacted>")
        val pairMasked = hexMasked.replace(
            Regex(
                "(?i)\\b(password|passwd|pwd|secret|privatekey|private_key|privkey|" +
                    "ciphertext|cipher|token|apikey|api_key|bearer|seed)\\b\\s*[=:]\\s*\\S+"
            ),
            "<redacted>"
        )
        return pairMasked.replace(
            Regex(
                "(?i)\\b(password|passwd|pwd|secret|privatekey|private_key|privkey|" +
                    "ciphertext|cipher|token|apikey|api_key|bearer|seed)\\b"
            ),
            "<redacted>"
        )
    }

    // ---- Trees ----

    private class DebugTree : Timber.DebugTree() {
        override fun createStackElementTag(element: StackTraceElement): String =
            "Impulse:${element.fileName}:${element.lineNumber}"

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            super.log(priority, tag, redactSecrets(message), t)
        }
    }

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= android.util.Log.ERROR) {
                android.util.Log.e(tag, redactSecrets(message), t)
            }
        }
    }
}
