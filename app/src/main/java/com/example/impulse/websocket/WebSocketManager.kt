package com.example.impulse.websocket

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

enum class WebSocketState {
    DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, ERROR
}

class WebSocketManager() {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _currentState = MutableStateFlow(WebSocketState.DISCONNECTED)
    val currentState: StateFlow<WebSocketState> = _currentState.asStateFlow()

    var onMessageReceived: ((String) -> Unit)? = null
    private var isAuthenticated = false
    private var pendingPassword: String? = null

    // Храним информацию о текущем подключении
    private var currentUrl: String? = null
    private var currentPassword: String? = null

    companion object {
        private var INSTANCE: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSocketManager().also { INSTANCE = it }
            }
        }

        fun destroyInstance() {
            INSTANCE?.disconnect()
            INSTANCE = null
        }
    }

    fun connect(url: String, password: String? = null) {
        // Если уже подключены к этому же серверу, не переподключаемся
        if (currentUrl == url && (_currentState.value == WebSocketState.AUTHENTICATED || _currentState.value == WebSocketState.CONNECTED)) {
            Log.d("WebSocket", "Уже подключены к этому серверу")
            return
        }

        // Если подключены к другому серверу, отключаемся
        if (currentUrl != null && currentUrl != url) {
            disconnect()
        }

        Log.d("WebSocket", "Попытка подключения к: $url")
        _currentState.value = WebSocketState.CONNECTING
        isAuthenticated = false
        pendingPassword = password
        currentUrl = url
        currentPassword = password

        try {
            val request = Request.Builder().url(url).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("WebSocket", "✅ WebSocket подключен успешно")
                    _currentState.value = WebSocketState.CONNECTED

                    // Send password immediately after connection if provided
                    if (password != null && password.isNotEmpty()) {
                        Log.d("WebSocket", "🔐 Отправка пароля для аутентификации")
                        webSocket.send(password)
                    } else {
                        // No password required, consider as authenticated
                        isAuthenticated = true
                        _currentState.value = WebSocketState.AUTHENTICATED
                        onMessageReceived?.invoke("✅ WebSocket подключен успешно")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WebSocket", "📨 Получено сообщение: $text")

                    // Handle authentication response
                    if (!isAuthenticated && pendingPassword != null) {
                        // Более либеральная проверка - любое сообщение после отправки пароля считаем успехом
                        isAuthenticated = true
                        _currentState.value = WebSocketState.AUTHENTICATED
                        onMessageReceived?.invoke("🔐 Аутентификация успешна. $text")
                        Log.d("WebSocket", "🔐 Состояние изменено на AUTHENTICATED")
                        pendingPassword = null
                    } else {
                        onMessageReceived?.invoke("📨 Сервер: $text")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d("WebSocket", "📨 Получены бинарные данные")
                    onMessageReceived?.invoke("📨 Сервер (бинарные данные)")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WebSocket", "🔌 Соединение закрывается: $code - $reason")
                    _currentState.value = WebSocketState.DISCONNECTED
                    isAuthenticated = false
                    currentUrl = null
                    currentPassword = null
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WebSocket", "🔌 Соединение закрыто: $code - $reason")
                    _currentState.value = WebSocketState.DISCONNECTED
                    isAuthenticated = false
                    currentUrl = null
                    currentPassword = null
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WebSocket", "❌ Ошибка подключения: ${t.message}", t)
                    _currentState.value = WebSocketState.ERROR
                    isAuthenticated = false
                    currentUrl = null
                    currentPassword = null
                    onMessageReceived?.invoke("❌ Ошибка подключения: ${t.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("WebSocket", "❌ Исключение при подключении: ${e.message}", e)
            _currentState.value = WebSocketState.ERROR
            isAuthenticated = false
            currentUrl = null
            currentPassword = null
            onMessageReceived?.invoke("❌ Ошибка: ${e.message}")
        }
    }

    fun sendMessage(message: String): Boolean {
        Log.d("WebSocket", "📤 Попытка отправить сообщение: '$message'")
        Log.d("WebSocket", "📤 Длина сообщения: ${message.length}")
        Log.d("WebSocket", "📤 Текущее состояние: ${_currentState.value}")

        // Проверяем, что WebSocket существует и состояние позволяет отправлять сообщения
        val socket = webSocket
        if (socket == null) {
            Log.w("WebSocket", "⚠️ WebSocket равен null")
            onMessageReceived?.invoke("⚠️ Нет подключения к серверу")
            return false
        }

        return if (_currentState.value == WebSocketState.AUTHENTICATED && message.isNotEmpty()) {
            try {
                Log.d("WebSocket", "📤 Состояние AUTHENTICATED, отправляем сообщение...")
                val success = socket.send(message)
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
            val stateMsg = when (_currentState.value) {
                WebSocketState.DISCONNECTED -> "Нет подключения"
                WebSocketState.CONNECTING -> "Подключение в процессе"
                WebSocketState.CONNECTED -> "Ожидание аутентификации"
                WebSocketState.AUTHENTICATED -> "Аутентифицирован"
                WebSocketState.ERROR -> "Ошибка подключения"
            }
            Log.w("WebSocket", "⚠️ Невозможно отправить сообщение. Состояние: ${_currentState.value}, Сообщение пустое: ${message.isEmpty()}")
            onMessageReceived?.invoke("⚠️ $stateMsg")
            false
        }
    }

    fun disconnect() {
        Log.d("WebSocket", "🔌 Запрос на отключение")
        try {
            // Проверяем, что WebSocket существует перед закрытием
            webSocket?.close(1000, "Пользовательское отключение")
        } catch (e: Exception) {
            Log.e("WebSocket", "❌ Ошибка при отключении: ${e.message}", e)
        } finally {
            _currentState.value = WebSocketState.DISCONNECTED
            isAuthenticated = false
            currentUrl = null
            currentPassword = null
        }
    }

    // Мягкое отключение - очищаем только ссылки, но не закрываем соединение
    fun softDisconnect() {
        Log.d("WebSocket", "🔌 Мягкое отключение")
        _currentState.value = WebSocketState.DISCONNECTED
        isAuthenticated = false
        // Не очищаем currentUrl и currentPassword, чтобы можно было восстановить соединение
    }

    fun getCurrentState(): WebSocketState = _currentState.value

    fun getCurrentUrl(): String? = currentUrl
    fun getCurrentPassword(): String? = currentPassword
}