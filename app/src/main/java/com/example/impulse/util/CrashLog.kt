package com.example.impulse.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Durable crash/diagnostic log storage.
 *
 * Crashes are written to `impulse_crash.log` in the app's internal files
 * directory (not accessible to other apps). The most recent crash is kept, plus
 * a rolling history of the last few, so a developer can retrieve the trace even
 * when logcat is unavailable (e.g. a crash on a field device).
 *
 * Companion to [LogManager] (live diagnostics) and the global
 * [android.os.Process] uncaught-exception handler installed in the Application.
 */
object CrashLog {

    private const val CRASH_FILE = "impulse_crash.log"
    private const val MAX_HISTORY = 5
    private const val HISTORY_DIR = "crashes"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Appends a crash report to disk. Safe to call from any thread. */
    @Synchronized
    fun writeCrash(report: String) {
        try {
            val ctx = lastContext ?: return
            val dir = File(ctx.filesDir, HISTORY_DIR)
            if (!dir.exists()) dir.mkdirs()
            // Keep a rolling history of recent crashes.
            val stamp = dateFormat.format(Date()).replace(' ', '_').replace(':', '-')
            val file = File(dir, "crash_$stamp.log")
            file.writeText(report)
            // Trim old history.
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_HISTORY)
                ?.forEach { it.delete() }
            // Also overwrite the single "latest" file for easy retrieval.
            File(ctx.filesDir, CRASH_FILE).writeText(report)
        } catch (_: Exception) {
            // Best-effort only.
        }
    }

    /**
     * Builds a single crash report string. [extra] carries diagnostic context
     * (e.g. connection states and log sizes) collected by the caller.
     */
    internal fun buildCrashReport(
        thread: Thread,
        throwable: Throwable,
        versionName: String,
        versionCode: Int,
        sdkInt: Int,
        release: String,
        manufacturer: String,
        model: String,
        timeMillis: Long,
        extra: String,
    ): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.append("=== Impulse crash ===\n")
            pw.append("Thread: ${thread.name} (${thread.id})\n")
            pw.append("App version: $versionName ($versionCode)\n")
            pw.append("SDK: $sdkInt ($release)\n")
            pw.append("Device: $manufacturer $model\n")
            pw.append("Time: $timeMillis\n")
            if (extra.isNotBlank()) {
                pw.append(extra).append('\n')
            }
            throwable.printStackTrace(pw)
        }
        return sw.toString()
    }

    /** Call once at startup (Application.onCreate) so we know where to write. */
    fun init(context: Context) {
        lastContext = context.applicationContext
    }

    @Volatile
    private var lastContext: Context? = null
}
