package com.example.impulse

import android.app.Application
import android.os.Build
import android.util.Log
import com.example.impulse.data.ServerPreferences
import com.example.impulse.util.CrashLog
import com.example.impulse.util.LogManager
import com.example.impulse.service.TtlPurgeWorker
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Application entry point.
 *
 * Installs a process-wide [Thread.setDefaultUncaughtExceptionHandler] so that
 * ANY otherwise-fatal exception is first written to a crash log file on disk
 * (see [CrashLog]) before the process terminates. This guarantees that even
 * when the app "crashes without logs" we can later retrieve the stack trace
 * from the device (e.g. via `adb pull` or a "Send logs" action) and pinpoint
 * the root cause.
 *
 * The handler deliberately re-throws to the previously-installed handler (the
 * platform default) so the normal Android crash reporting / "App has stopped"
 * dialog still occurs — we only ADD durable logging, we do not suppress the
 * system behaviour.
 */
class ImpulseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Honour the user's persisted "Вести логи" preference so the file-logging
        // toggle survives process restarts.
        val loggingEnabled = ServerPreferences(this).getLoggingEnabled()
        LogManager.init(this, BuildConfig.DEBUG, loggingEnabled)
        CrashLog.init(this)
        TtlPurgeWorker.schedule(this)
        installGlobalCrashHandler()
    }

    private fun installGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.append("=== Impulse crash ===\n")
                    pw.append("Thread: ${thread.name} (${thread.id})\n")
                    pw.append("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                    pw.append("SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
                    pw.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    pw.append("Time: ${System.currentTimeMillis()}\n")
                    throwable.printStackTrace(pw)
                }
                CrashLog.writeCrash(sw.toString())
                Log.e(TAG, "Uncaught exception captured to crash log", throwable)
            } catch (logError: Exception) {
                // Never let the logging itself crash the handler.
                Log.e(TAG, "Failed to write crash log", logError)
            } finally {
                // Delegate to the platform default so the standard crash flow runs.
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        private const val TAG = "ImpulseApplication"
    }
}
