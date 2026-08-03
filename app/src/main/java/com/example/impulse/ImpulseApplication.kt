package com.example.impulse

import android.app.Application
import android.os.Build
import android.util.Log
import com.example.impulse.util.CrashLog
import com.example.impulse.util.LogManager
import com.example.impulse.service.NetworkMonitor
import com.example.impulse.service.TtlPurgeWorker

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
        LogManager.init(this, BuildConfig.DEBUG)
        CrashLog.init(this)
        TtlPurgeWorker.schedule(this)
        // Reconnect servers whose transport died during a network outage (Bug:
        // "no instant reconnect on WiFi <-> cellular handover").
        NetworkMonitor.getInstance(this)
        installGlobalCrashHandler()
    }

    private fun installGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val extra = buildCrashExtra()
                val report = CrashLog.buildCrashReport(
                    thread = thread,
                    throwable = throwable,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    sdkInt = Build.VERSION.SDK_INT,
                    release = Build.VERSION.RELEASE,
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    timeMillis = System.currentTimeMillis(),
                    extra = extra,
                )
                CrashLog.writeCrash(report)
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

    private fun buildCrashExtra(): String {
        val sb = StringBuilder()
        runCatching {
            val states = ConnectionManager.getInstance(this).serverStates.value
            sb.append("Connections:\n")
            for ((id, status) in states) {
                sb.append("  $id: ${status.state} lastError=${status.lastError ?: "null"}\n")
            }
        }.onFailure { sb.append("Connections: unavailable\n") }
        runCatching {
            sb.append("logBytes=").append(com.example.impulse.util.FileLogger.getLogSize()).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "ImpulseApplication"
    }
}
