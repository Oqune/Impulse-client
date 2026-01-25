package com.example.impulse.websocket

import android.util.Log
import com.example.impulse.util.LogStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class WebSocketState {
    DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, ERROR
}

class WebSocketManager private constructor() {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _currentState = MutableStateFlow(WebSocketState.DISCONNECTED)
    val currentState: StateFlow<WebSocketState> = _currentState.asStateFlow()

    var onMessageReceived: ((String) -> Unit)? = null
    private var isAuthenticated = false

    // Данные для повторного подключения
    private var lastUrl: String? = null
    private var lastPassword: String? = null
    private var lastName: String = "AndroidClient"

    companion object {
        private var INSTANCE: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSocketManager().also { INSTANCE = it }
            }
        }
    }

    fun connect(url: String, password: String? = null, name: String = "AndroidClient") {
        Log.d("WebSocket", "Попытка подключения к: $url")
        LogStorage.addLog("Попытка подключения к: $url")

        // Сохраняем данные для возможного повторного подключения
        lastUrl = url
        lastPassword = password
        lastName = name

        _currentState.value = WebSocketState.CONNECTING
        isAuthenticated = false

        try {
            val request = Request.Builder().url(url).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("WebSocket", "✅ WebSocket подключен успешно")
                    LogStorage.addLog("WebSocket подключен успешно")
                    _currentState.value = WebSocketState.CONNECTED

                    // Отправляем данные аутентификации в формате JSON
                    if (password != null && password.isNotEmpty()) {
                        val authJson = JSONObject().apply {
                            put("password", password)
                            put("name", name)
                        }

                        Log.d("WebSocket", "Отправка JSON: ${authJson.toString()}")
                        LogStorage.addLog("Отправка данных аутентификации")
                        webSocket.send(authJson.toString())
                    } else {
                        // Без пароля считаем аутентификацию успешной
                        isAuthenticated = true
                        _currentState.value = WebSocketState.AUTHENTICATED
                        // Не показываем системное сообщение в чате
                        LogStorage.addLog("Подключено без пароля")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WebSocket", "Получено: $text")
                    LogStorage.addLog("Получено: $text")

                    // Обработка аутентификации
                    if (!isAuthenticated) {
                        try {
                            // Пытаемся распарсить как JSON
                            val json = JSONObject(text)
                            val msgType = json.optString("msg_type", "")
                            val content = json.optString("content", "")

                            if (msgType == "Auth" && content == "AUTH_SUCCESS") {
                                isAuthenticated = true
                                _currentState.value = WebSocketState.AUTHENTICATED
                                // Не показываем системное сообщение в чате, только в логах
                                LogStorage.addLog("Аутентификация успешна")
                            } else {
                                _currentState.value = WebSocketState.ERROR
                                // Показываем ошибку в чате
                                onMessageReceived?.invoke("❌ Ошибка аутентификации")
                                LogStorage.addLog("Ошибка аутентификации: $text")
                            }
                        } catch (e: Exception) {
                            // Если не JSON, проверяем текстовые ответы
                            if (text.contains("успешно") || text.contains("authenticated") ||
                                text.contains("welcome") || text.contains("Connected") ||
                                text.contains("Success") || text.contains("OK")) {

                                isAuthenticated = true
                                _currentState.value = WebSocketState.AUTHENTICATED
                                // Не показываем системное сообщение в чате, только в логах
                                LogStorage.addLog("Аутентификация успешна (текст)")
                            } else {
                                _currentState.value = WebSocketState.ERROR
                                // Показываем ошибку в чате
                                onMessageReceived?.invoke("❌ Ошибка: $text")
                                LogStorage.addLog("Ошибка аутентификации: $text")
                            }
                        }
                        return
                    }

                    // Обработка сообщений после аутентификации
                    processMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d("WebSocket", "Получены бинарные данные")
                    LogStorage.addLog("Получены бинарные данные")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WebSocket", "Соединение закрывается: $reason")
                    LogStorage.addLog("Соединение закрывается: $reason")
                    _currentState.value = WebSocketState.DISCONNECTED
                    isAuthenticated = false
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WebSocket", "Соединение закрыто: $reason")
                    LogStorage.addLog("Соединение закрыто: $reason")
                    _currentState.value = WebSocketState.DISCONNECTED
                    isAuthenticated = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WebSocket", "Ошибка подключения: ${t.message}", t)
                    LogStorage.addLog("Ошибка подключения: ${t.message}")
                    _currentState.value = WebSocketState.ERROR
                    isAuthenticated = false
                    onMessageReceived?.invoke("❌ Ошибка: ${t.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("WebSocket", "Исключение при подключении: ${e.message}", e)
            LogStorage.addLog("Исключение: ${e.message}")
            _currentState.value = WebSocketState.ERROR
            isAuthenticated = false
            onMessageReceived?.invoke("❌ Ошибка: ${e.message}")
        }
    }

    private fun processMessage(text: String) {
        try {
            // Пытаемся распарсить как JSON
            val json = JSONObject(text)
            val msgType = json.optString("msg_type", "")
            val senderName = json.optString("sender_name", "Система")
            val content = json.optString("content", "")

            when (msgType) {
                "System" -> {
                    // Системные сообщения показываем в чате
                    onMessageReceived?.invoke("[$senderName] $content")
                }
                "Content" -> {
                    // Контентные сообщения в чат
                    onMessageReceived?.invoke("[$senderName] $content")
                }
                else -> {
                    // Другие типы или не JSON - отправляем как есть
                    onMessageReceived?.invoke(text)
                }
            }
        } catch (e: Exception) {
            // Не JSON - отправляем как есть
            onMessageReceived?.invoke(text)
        }
    }

    fun sendMessage(message: String): Boolean {
        Log.d("WebSocket", "Отправка: $message")
        LogStorage.addLog("Отправка: $message")

        val socket = webSocket
        if (socket == null) {
            Log.w("WebSocket", "Нет подключения")
            LogStorage.addLog("Нет подключения")
            return false
        }

        return if (_currentState.value == WebSocketState.AUTHENTICATED && message.isNotEmpty()) {
            try {
                val success = socket.send(message)
                if (success) {
                    Log.d("WebSocket", "Сообщение отправлено")
                    LogStorage.addLog("Сообщение отправлено")
                    return true
                } else {
                    Log.e("WebSocket", "Не удалось отправить")
                    LogStorage.addLog("Не удалось отправить")
                    return false
                }
            } catch (e: Exception) {
                Log.e("WebSocket", "Ошибка отправки: ${e.message}", e)
                LogStorage.addLog("Ошибка отправки: ${e.message}")
                return false
            }
        } else {
            Log.w("WebSocket", "Невозможно отправить сообщение")
            LogStorage.addLog("Невозможно отправить сообщение")
            return false
        }
    }

    fun disconnect() {
        Log.d("WebSocket", "Отключение")
        LogStorage.addLog("Отключение")
        try {
            webSocket?.close(1000, "Отключение")
        } catch (e: Exception) {
            Log.e("WebSocket", "Ошибка отключения: ${e.message}", e)
            LogStorage.addLog("Ошибка отключения: ${e.message}")
        } finally {
            _currentState.value = WebSocketState.DISCONNECTED
            isAuthenticated = false
        }
    }

    fun getCurrentState(): WebSocketState = _currentState.value
}