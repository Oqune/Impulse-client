package com.example.impulse.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.impulse.data.ServerPreferences
import com.example.impulse.service.StartServiceWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serverPreferences = ServerPreferences(context)
            // Only auto-start when a server is already configured.
            if (serverPreferences.getSelectedServer() == null) return

            // On Android 14+ a dataSync foreground service cannot be started
            // directly from BOOT_COMPLETED. An expedited WorkManager job is the
            // sanctioned way to promote to a foreground service after boot.
            val work = OneTimeWorkRequestBuilder<StartServiceWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "start-impulse-service",
                ExistingWorkPolicy.REPLACE,
                work
            )
        }
    }
}
