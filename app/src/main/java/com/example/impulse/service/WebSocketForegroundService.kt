package com.example.impulse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.impulse.MainActivity
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.ui.theme.ThemeSettings
import com.example.impulse.util.NameGenerator
import com.example.impulse.websocket.WebSocketManager
import com.example.impulse.websocket.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class WebSocketForegroundService : Service() {

    private val CONNECTION_CHANNEL_ID = "websocket_channel"
    private val NOTIFICATION_ID = 1001
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private var serverPreferences: ServerPreferences? = null
    private var webSocketManager: WebSocketManager? = null
    private var notificationManager: NotificationManager? = null
    private var currentServerId: String = ""
    private var reconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        serverPreferences = ServerPreferences(this)
        webSocketManager = WebSocketManager.getInstance()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Impulse::WebSocketWakeLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(12))
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Silent status channel - required for the foreground service.
            // No sound, no vibration, no badge: this is NOT a message notification.
            val channel = NotificationChannel(
                CONNECTION_CHANNEL_ID,
                "Статус подключения",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Поддержка фонового подключения к серверу чата"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun accentColorInt(): Int {
        val accent = ThemeSettings.accentColor
        return android.graphics.Color.HSVToColor(
            floatArrayOf(accent.hue, accent.saturation, accent.lightness)
        )
    }

    private fun createConnectionNotification(
        title: String,
        content: String,
        isConnected: Boolean = false
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setColor(accentColorInt())
            .setColorized(isConnected)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Show the foreground notification immediately so the system does not
        // kill the service for not calling startForeground in time.
        updateConnectionNotification(WebSocketState.DISCONNECTED)

        val action = intent?.action ?: "START"
        when (action) {
            "START" -> startWebSocketConnection()
            "STOP" -> stopWebSocketConnection()
            "RECONNECT" -> reconnectWebSocket()
        }
        return START_STICKY
    }

    private fun startWebSocketConnection() {
        job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job!!)
        scope.launch {
           // The app must stay connected whenever a server is configured,
            // regardless of the auto-connect toggle (which now only controls
            // connecting on device boot).
            val savedServer = serverPreferences?.getSelectedServer()
            val savedClientName = serverPreferences?.getClientName()?.takeIf { it.isNotBlank() }
                ?: NameGenerator.generate()

            if (savedServer != null) {
                currentServerId = savedServer.id
                val url = savedServer.getWebSocketUrl()
                val password = savedServer.password
                val encryptionKey = ""

                webSocketManager?.connect(url, password, savedClientName, encryptionKey, currentServerId)

                // Keep the connection notification in sync with the state.
                webSocketManager?.currentState?.collect { state ->
                    updateConnectionNotification(state)
                }
            } else {
                updateConnectionNotification(WebSocketState.DISCONNECTED)
            }
        }
    }

    private fun stopWebSocketConnection() {
        webSocketManager?.disconnect()
        job?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        updateConnectionNotification(WebSocketState.DISCONNECTED)
    }

    private fun reconnectWebSocket() {
        stopWebSocketConnection()
        // Small delay before reconnecting to avoid rapid reconnect loops
        reconnectJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            kotlinx.coroutines.delay(2000)
            startWebSocketConnection()
        }
    }

    private fun updateConnectionNotification(state: WebSocketState) {
        val (title, content) = when (state) {
            WebSocketState.CONNECTING -> "Подключение..." to "Устанавливается соединение с сервером"
            WebSocketState.CONNECTED -> "Подключено" to "Ожидание аутентификации"
            WebSocketState.AUTHENTICATED -> "В сети" to "Подключено к серверу чата"
            WebSocketState.ERROR -> "Ошибка" to "Потеряно соединение с сервером"
            else -> "Отключено" to "Фоновое подключение неактивно"
        }

        val notification = createConnectionNotification(title, content, state == WebSocketState.AUTHENTICATED)
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        reconnectJob?.cancel()
        webSocketManager?.disconnect()
        releaseWakeLock()
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
