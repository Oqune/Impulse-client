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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.impulse.MainActivity
import com.example.impulse.ChatController
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.ServerPreferences
import com.example.impulse.transport.ConnectionState
import com.example.impulse.ui.theme.ThemeSettings
import com.example.impulse.util.NameGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Keeps the WebTransport connection alive while the app is in the background.
 * Delegates all protocol logic to [ChatController].
 */
class WebTransportForegroundService : Service() {

    private val TAG = "WebTransportFgService"
    private val CONNECTION_CHANNEL_ID = "webtransport_channel"
    private val NOTIFICATION_ID = 1001
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private var serverPreferences: ServerPreferences? = null
    private var chatController: ChatController? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        serverPreferences = ServerPreferences(this)
        chatController = ChatController.getInstance(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Impulse::WTLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(12))
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    private fun createConnectionNotification(title: String, content: String, isConnected: Boolean): Notification {
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
        // Promote to a foreground service IMMEDIATELY (within the ~10s window
        // required by startForegroundService). On Android 14+ a dataSync FGS may
        // not be startable from BOOT_COMPLETED; the BootReceiver launches it via
        // WorkManager (expedited) instead, and we guard startForeground so a
        // platform rejection degrades gracefully instead of crashing the app.
        try {
            startForeground(NOTIFICATION_ID, createConnectionNotification(
                "Отключено", "Фоновое подключение неактивно", false
            ))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        when (intent?.action ?: "START") {
            "START" -> startConnection()
            "STOP" -> stopConnection()
        }
        return START_STICKY
    }

    private fun startConnection() {
        job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job!!)
        scope.launch {
            val savedServer = serverPreferences?.getSelectedServer()
            val savedClientName = serverPreferences?.getClientName()?.takeIf { it.isNotBlank() }
                ?: NameGenerator.generate()

            if (savedServer != null) {
                chatController?.connect(savedServer, savedClientName)
                chatController?.state?.collect { state ->
                    updateConnectionNotification(state)
                }
            } else {
                updateConnectionNotification(ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun stopConnection() {
        chatController?.disconnect()
        job?.cancel()
        updateConnectionNotification(ConnectionState.DISCONNECTED)
    }

    private fun updateConnectionNotification(state: ConnectionState) {
        val (title, content) = when (state) {
            ConnectionState.CONNECTING -> "Подключение..." to "Устанавливается соединение с сервером"
            ConnectionState.CONNECTED -> "Подключено" to "Ожидание аутентификации"
            ConnectionState.AUTHENTICATING -> "Аутентификация..." to "Проверка пароля сервера"
            ConnectionState.AUTHENTICATED -> "Канал установлен" to "Вычисление группового секрета"
            ConnectionState.READY -> "Аутентифицировано" to "Защищённое соединение установлено"
            ConnectionState.ERROR -> "Ошибка" to "Потеряно соединение с сервером"
            else -> "Отключено" to "Фоновое подключение неактивно"
        }
        val notification = createConnectionNotification(
            title, content, state == ConnectionState.AUTHENTICATED
        )
        // The service is already promoted to foreground in onStartCommand; just
        // update the existing notification instead of re-calling startForeground
        // (which would re-trigger the FGS-start restrictions).
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        chatController?.disconnect()
        releaseWakeLock()
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
