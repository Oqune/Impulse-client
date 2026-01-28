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

// Типы сообщений для унификации передачи данных
enum class MessageType {
    TECHNICAL,     // Аутентификация, подключение, отключение
    INFORMATIONAL, // Информация о подключении/отключении пользователей
    CONTENT,       // Сообщения от пользователей (будет зашифровано)
    SYSTEM         // Ошибки, служебная информация
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

    var onMessageReceived: ((String, Boolean) -> Unit)? = null // Второй параметр - это флаг для полноширинных сообщений
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

                    // Создаем унифицированное аутентификационное сообщение в формате сервера
                    val unifiedMessage = JSONObject().apply {
                        put("type", "technical")
                        put("payload", JSONObject().apply {
                            put("name", name)
                            if (password != null && password.isNotEmpty()) {
                                put("password", password)
                            }
                        })
                        put("timestamp", System.currentTimeMillis() / 1000)
                    }

                    Log.d("WebSocket", "Отправка JSON аутентификации: ${unifiedMessage.toString()}")
                    LogStorage.addLog("Отправка данных аутентификации")
                    webSocket.send(unifiedMessage.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WebSocket", "Получено: $text")
                    LogStorage.addLog("Получено: $text")

                    try {
                        val json = JSONObject(text)

                        // Проверяем, есть ли поле "type" (унифицированный формат)
                        if (json.has("type")) {
                            val msgTypeStr = json.optString("type", "").lowercase()

                            when (msgTypeStr) {
                                "technical", "auth_response" -> {
                                    // Обработка технических сообщений (включая ответ на аутентификацию)
                                    handleTechnicalMessage(json)
                                }
                                "informational", "info" -> {
                                    // Обработка информационных сообщений
                                    val payload = json.optJSONObject("payload") ?: json
                                    handleInformationalMessage(payload)
                                }
                                "content" -> {
                                    // Обработка контентных сообщений
                                    val payload = json.optJSONObject("payload") ?: json
                                    handleContentMessage(payload)
                                }
                                "system" -> {
                                    // Обработка системных сообщений
                                    val payload = json.optJSONObject("payload") ?: json
                                    handleSystemMessage(payload)
                                }
                                else -> {
                                    // Неизвестный тип сообщения - пробуем обработать как информационное
                                    handleInformationalMessage(json)
                                }
                            }
                        } else {
                            // Старый формат или специфичные сообщения сервера
                            handleLegacyMessage(text)
                        }
                    } catch (e: Exception) {
                        // Если не удалось распарсить как JSON, обрабатываем как текст
                        handlePlainTextMessage(text)
                    }
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

                    // Отправляем системное сообщение об ошибке
                    val errorMsg = JSONObject().apply {
                        put("type", "system")
                        put("payload", JSONObject().apply {
                            put("content", "Ошибка подключения: ${t.message}")
                        })
                        put("timestamp", System.currentTimeMillis() / 1000)
                    }
                    onMessageReceived?.invoke(errorMsg.toString(), false)
                }
            })
        } catch (e: Exception) {
            Log.e("WebSocket", "Исключение при подключении: ${e.message}", e)
            LogStorage.addLog("Исключение: ${e.message}")
            _currentState.value = WebSocketState.ERROR
            isAuthenticated = false

            // Отправляем системное сообщение об ошибке
            val errorMsg = JSONObject().apply {
                put("type", "system")
                put("payload", JSONObject().apply {
                    put("content", "Исключение при подключении: ${e.message}")
                })
                put("timestamp", System.currentTimeMillis() / 1000)
            }
            onMessageReceived?.invoke(errorMsg.toString(), false)
        }
    }

    // Обработка технических сообщений (аутентификация, подключение и т.д.)
    private fun handleTechnicalMessage(json: JSONObject) {
        val payload = json.optJSONObject("payload") ?: json
        val success = payload.optBoolean("success", false)
        val message = payload.optString("message", "")

        // Проверяем успешность аутентификации
        if (success || message.contains("успешно", true) || message.contains("success", true) ||
            message.contains("authenticated", true)) {

            isAuthenticated = true
            _currentState.value = WebSocketState.AUTHENTICATED
            LogStorage.addLog("Аутентификация успешна")

            // Отправляем информационное сообщение о подключении
            val infoMsg = JSONObject().apply {
                put("type", "informational")
                put("payload", JSONObject().apply {
                    put("content", "Вы успешно подключились к серверу")
                })
                put("timestamp", System.currentTimeMillis() / 1000)
            }
            onMessageReceived?.invoke(infoMsg.toString(), true)
        } else {
            // Аутентификация не удалась
            _currentState.value = WebSocketState.ERROR
            val errorMsg = payload.optString("error", "Ошибка аутентификации")
            val systemMsg = JSONObject().apply {
                put("type", "system")
                put("payload", JSONObject().apply {
                    put("content", "Ошибка аутентификации: $errorMsg")
                })
                put("timestamp", System.currentTimeMillis() / 1000)
            }
            onMessageReceived?.invoke(systemMsg.toString(), false)
            LogStorage.addLog("Ошибка аутентификации: $errorMsg")
        }
    }

    // Обработка информационных сообщений (подключения/отключении пользователей)
    private fun handleInformationalMessage(payload: JSONObject) {
        // Получаем данные из payload
        val event = payload.optString("event", "")
        val userId = payload.optInt("user_id", 0)
        val userName = payload.optString("user_name", payload.optString("username", "Пользователь"))

        // Формируем понятное сообщение для пользователя
        val content = when (event) {
            "joined" -> "Пользователь $userName присоединился к чату"
            "left" -> "Пользователь $userName покинул чат"
            else -> payload.optString("content", "Пользователь $userName $event")
        }

        val infoMsg = JSONObject().apply {
            put("type", "informational")
            put("payload", JSONObject().apply {
                put("content", content)
                put("event", event)
                put("user_name", userName)
                put("user_id", userId)
            })
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        onMessageReceived?.invoke(infoMsg.toString(), true)
    }

    // Обработка контентных сообщений (сообщения от пользователей)
    private fun handleContentMessage(payload: JSONObject) {
        val senderName = payload.optString("sender_name", payload.optString("user_name", "Неизвестный"))
        val content = payload.optString("content", payload.optString("message", ""))
        val contentMsg = JSONObject().apply {
            put("type", "content")
            put("payload", JSONObject().apply {
                put("sender_name", senderName)
                put("content", content)
            })
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        onMessageReceived?.invoke(contentMsg.toString(), false)
    }

    // Обработка системных сообщений (ошибки, уведомления)
    private fun handleSystemMessage(payload: JSONObject) {
        val content = payload.optString("content", "")
        val systemMsg = JSONObject().apply {
            put("type", "system")
            put("payload", JSONObject().apply {
                put("content", content)
            })
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        onMessageReceived?.invoke(systemMsg.toString(), false)
    }

    // Обработка сообщений в старом формате или специфичных сообщений сервера
    private fun handleLegacyMessage(text: String) {
        if (!isAuthenticated) {
            // Логика аутентификации для старого формата
            if (text.contains("успешно") || text.contains("authenticated") ||
                text.contains("welcome") || text.contains("Connected") ||
                text.contains("Success") || text.contains("OK") ||
                text.contains("\"success\":true")) {

                isAuthenticated = true
                _currentState.value = WebSocketState.AUTHENTICATED
                LogStorage.addLog("Аутентификация успешна (текст)")

                // Отправляем информационное сообщение о подключении
                val infoMsg = JSONObject().apply {
                    put("type", "informational")
                    put("payload", JSONObject().apply {
                        put("content", "Вы успешно подключились к серверу")
                    })
                    put("timestamp", System.currentTimeMillis() / 1000)
                }
                onMessageReceived?.invoke(infoMsg.toString(), true)
            } else {
                _currentState.value = WebSocketState.ERROR
                val systemMsg = JSONObject().apply {
                    put("type", "system")
                    put("payload", JSONObject().apply {
                        put("content", "Ошибка аутентификации: $text")
                    })
                    put("timestamp", System.currentTimeMillis() / 1000)
                }
                onMessageReceived?.invoke(systemMsg.toString(), false)
                LogStorage.addLog("Ошибка аутентификации: $text")
            }
            return
        }

        // Обычные текстовые сообщения после аутентификации
        val systemMsg = JSONObject().apply {
            put("type", "system")
            put("payload", JSONObject().apply {
                put("content", text)
            })
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        onMessageReceived?.invoke(systemMsg.toString(), false)
    }

    // Обработка простых текстовых сообщений
    private fun handlePlainTextMessage(text: String) {
        // Простые текстовые сообщения обрабатываем как системные
        val systemMsg = JSONObject().apply {
            put("type", "system")
            put("payload", JSONObject().apply {
                put("content", text)
            })
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        onMessageReceived?.invoke(systemMsg.toString(), false)
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
                // Создаем контентное сообщение в унифицированном формате
                val contentMsg = JSONObject().apply {
                    put("type", "content")
                    put("payload", JSONObject().apply {
                        put("sender_name", lastName)
                        put("content", message)
                    })
                    put("timestamp", System.currentTimeMillis() / 1000)
                }

                val success = socket.send(contentMsg.toString())

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