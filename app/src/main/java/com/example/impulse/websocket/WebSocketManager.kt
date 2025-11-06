package com.example.impulse.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

enum class WebSocketState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

class WebSocketManager {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _currentState = MutableStateFlow(WebSocketState.DISCONNECTED)
    val currentState: StateFlow<WebSocketState> = _currentState.asStateFlow()

    var onMessageReceived: ((String) -> Unit)? = null

    fun connect(url: String) {
        Log.d("WebSocket", "Попытка подключения к: $url")
        _currentState.value = WebSocketState.CONNECTING

        try {
            val request = Request.Builder().url(url).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("WebSocket", "✅ WebSocket подключен успешно")
                    _currentState.value = WebSocketState.CONNECTED
                    onMessageReceived?.invoke("✅ WebSocket подключен успешно")

                    CoroutineScope(Dispatchers.IO).launch {
                        webSocket.send("Hello from Android")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WebSocket", "📨 Получено сообщение: $text")
                    onMessageReceived?.invoke("📨 Сервер: $text")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d("WebSocket", "📨 Получены бинарные данные: ${bytes.hex()}")
                    onMessageReceived?.invoke("📨 Сервер (бинарные данные): ${bytes.hex()}")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WebSocket", "🔌 Соединение закрывается: $code - $reason")
                    _currentState.value = WebSocketState.DISCONNECTED
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WebSocket", "🔌 Соединение закрыто: $code - $reason")
                    _currentState.value = WebSocketState.DISCONNECTED
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WebSocket", "❌ Ошибка подключения: ${t.message}", t)
                    _currentState.value = WebSocketState.ERROR
                    onMessageReceived?.invoke("❌ Ошибка подключения: ${t.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("WebSocket", "❌ Исключение при подключении: ${e.message}", e)
            _currentState.value = WebSocketState.ERROR
            onMessageReceived?.invoke("❌ Ошибка: ${e.message}")
        }
    }

    fun sendMessage(message: String): Boolean {
        Log.d("WebSocket", "📤 Попытка отправить сообщение: '$message'")
        Log.d("WebSocket", "📤 Длина сообщения: ${message.length}")
        Log.d("WebSocket", "📤 Текущее состояние: ${_currentState.value}")

        return if (_currentState.value == WebSocketState.CONNECTED && message.isNotEmpty()) {
            try {
                Log.d("WebSocket", "📤 Состояние CONNECTED, отправляем сообщение...")
                val success = webSocket?.send(message) ?: false
                if (success) {
                    Log.d("WebSocket", "✅ Сообщение отправлено: '$message'")
                    onMessageReceived?.invoke("📤 Вы: $message")
                    true
                } else {
                    Log.e("WebSocket", "❌ WebSocket.send() вернул false")
                    onMessageReceived?.invoke("❌ Не удалось отправить сообщение")
                    false
                }
            } catch (e: Exception) {
                Log.e("WebSocket", "❌ Ошибка при отправке: ${e.message}", e)
                onMessageReceived?.invoke("❌ Ошибка отправки: ${e.message}")
                false
            }
        } else {
            Log.w("WebSocket", "⚠️ Нет подключения для отправки сообщения. Состояние: ${_currentState.value}, сообщение не пустое: ${message.isNotEmpty()}")
            onMessageReceived?.invoke("⚠️ Нет подключения к серверу")
            false
        }
    }

    fun disconnect() {
        Log.d("WebSocket", "🔌 Запрос на отключение")
        webSocket?.close(1000, "Пользовательское отключение")
        _currentState.value = WebSocketState.DISCONNECTED
    }

    fun getCurrentState(): WebSocketState = _currentState.value
}