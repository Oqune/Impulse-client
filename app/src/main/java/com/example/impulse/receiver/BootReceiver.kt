package com.example.impulse.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.impulse.data.ServerPreferences
import com.example.impulse.service.WebSocketForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serverPreferences = ServerPreferences(context)
            val savedServer = serverPreferences.getSelectedServer()

            // Keep the connection alive after reboot whenever a server is set.
            if (savedServer != null) {
                val serviceIntent = Intent(context, WebSocketForegroundService::class.java)
                serviceIntent.action = "START"
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}