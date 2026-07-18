package com.example.impulse.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.impulse.data.MessageRepository
import com.example.impulse.util.LogManager
import java.util.concurrent.TimeUnit

/**
 * Periodic [WorkManager] job that enforces the 72-hour message TTL even when
 * the app is not actively connected. Runs every 6 hours (the minimum reliable
 * periodic interval on Android) so expired rows are reclaimed regardless of
 * foreground/background state. A purge also runs on every connect (see
 * [com.example.impulse.ChatController.connect]).
 */
class TtlPurgeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val repo = MessageRepository(applicationContext)
            val removed = repo.purgeExpired()
            LogManager.i(TAG, "periodic TTL purge complete: removed $removed")
            Result.success()
        } catch (e: Exception) {
            LogManager.e(TAG, "periodic TTL purge failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TtlPurgeWorker"
        private const val UNIQUE_NAME = "impulse_ttl_purge"
        private const val INTERVAL_HOURS = 6L

        /** Schedules the recurring purge. Idempotent (keeps existing if present). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TtlPurgeWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            LogManager.i(TAG, "scheduled periodic TTL purge every $INTERVAL_HOURS h")
        }
    }
}
