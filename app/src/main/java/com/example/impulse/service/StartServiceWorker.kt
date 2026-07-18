package com.example.impulse.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.impulse.data.ServerPreferences

/**
 * Expedited worker used by [com.example.impulse.receiver.BootReceiver] to start
 * [WebTransportForegroundService] after boot. On Android 14+ a `dataSync`
 * foreground service cannot be started directly from `BOOT_COMPLETED`; an
 * expedited WorkManager job is the sanctioned path and is allowed to promote
 * itself to a foreground service.
 */
class StartServiceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val serverPreferences = ServerPreferences(applicationContext)
        if (serverPreferences.getSelectedServer() == null) {
            return Result.success()
        }
        val intent = Intent(applicationContext, WebTransportForegroundService::class.java).apply {
            action = "START"
        }
        try {
            ContextCompat.startForegroundService(applicationContext, intent)
        } catch (e: Exception) {
            return Result.failure()
        }
        return Result.success()
    }
}
