package com.example.impulse.websocket

import android.util.Log
import com.example.impulse.encryption.MessageEncryption
import com.example.impulse.ui.screens.ChatHistoryManager
import com.example.impulse.ui.screens.MessageParser
import com.example.impulse.ui.screens.MessageType
import com.example.impulse.util.LogStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class WebSocketState {
    DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, ERROR
}

class WebSocketManager private constructor() {
    private var webSocket: WebSocket? = null
    // The server auto-generates a self-signed certificate per IP, so the system
    // trust store would reject it (SSLHandshakeException -> connection impossible).
    // We build a client that trusts the server's self-signed cert and skips
    // hostname verification. This is acceptable for a self-hosted LAN chat where
    // the user controls both ends.
    private val client = createSelfSignedTrustingClient()

    /**
     * Builds an OkHttpClient that accepts the server's self-signed certificate.
     * Uses a permissive X509TrustManager + a hostname verifier that accepts any host.
     */
    private fun createSelfSignedTrustingClient(): OkHttpClient {
        return try {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for persistent connections
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS) // Send ping every 30 seconds to keep connection alive (less aggressive)
                .build()
        } catch (e: Exception) {
            // Fallback to a default client if SSL setup somehow fails
            Log.e("WebSocket", "Ошибка настройки SSL: ${e.message}", e)
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }
    }

    private val _currentState = MutableStateFlow(WebSocketState.DISCONNECTED)
    val currentState: StateFlow<WebSocketState> = _currentState.asStateFlow()

    // Multi-listener support to avoid callback conflicts
    // First listener = background (service), Last listener = UI (ChatScreen)
    private val messageListeners = mutableListOf<(String, Boolean) -> Unit>()
    private val reconnectListeners = mutableListOf<() -> Unit>()
    
    // Public API for listener management
    fun addMessageListener(listener: (String, Boolean) -> Unit) {
        messageListeners.add(listener)
    }
    
    fun removeMessageListener(listener: (String, Boolean) -> Unit) {
        messageListeners.remove(listener)
    }
    
    fun addReconnectListener(listener: () -> Unit) {
        reconnectListeners.add(listener)
    }
    
    fun removeReconnectListener(listener: () -> Unit) {
        reconnectListeners.remove(listener)
    }

    // Internal method to notify ALL listeners
    private fun notifyMessageListeners(message: String, isFullWidth: Boolean) {
        // Always persist the message into the per-server history, regardless of
        // whether the chat UI is currently mounted. This fixes the bug where
        // messages arriving while the user is on the Home/Settings tab were
        // dropped (no UI listener registered) and never shown when returning
        // to the chat. The ChatScreen listener only mirrors this into its own
        // visible list; it must NOT re-add to history (that would duplicate).
        if (currentServerId.isNotEmpty()) {
            try {
                val parsed = MessageParser.parse(message, isFullWidth)
                // Only real chat content is persisted into history. System / error /
                // informational (join, leave, auth) messages are transient and must
                // NOT accumulate in the chat history (they caused the "wall of
                // system messages" and wasted memory on reconnects).
                if (parsed.messageType == MessageType.CONTENT) {
                    ChatHistoryManager.addMessage(currentServerId, parsed)
                }
            } catch (e: Exception) {
                Log.e("WebSocket", "Ошибка сохранения истории: ${e.message}", e)
            }
        }
        messageListeners.forEach { it(message, isFullWidth) }
    }
    
    private fun notifyReconnectListeners() {
        reconnectListeners.forEach { it() }
    }

    private var isAuthenticated = false
    private var intentionalDisconnect = false

    private var lastUrl: String? = null
    private var lastPassword: String? = null
    private var lastName: String = "AndroidClient"
    private var encryptionKey: String = ""
    // ID of the server we are currently connected to. Used to persist incoming
    // messages into the correct per-server history even when the chat UI is not
    // mounted (e.g. user is on the Home/Settings tab).
    private var currentServerId: String = ""
    private var clientId: Int = 0
    private var clientName: String = ""
    
    // Auth timeout job
    private var authTimeoutJob: Job? = null
    private val authTimeoutMs = 20000L // 20 seconds auth timeout (increased for slow networks)

    // Application-level ping/pong for connection health monitoring
    private var pingJob: Job? = null
    private val pingIntervalMs = 60000L // 60 seconds - less aggressive
    private val pongTimeoutMs = 30000L // 30 seconds to wait for pong - more tolerant
    private var lastPongReceived = System.currentTimeMillis()
    private var pingSentTime = 0L
    private var waitingForPong = false

    companion object {
        private var INSTANCE: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSocketManager().also { INSTANCE = it }
            }
        }
    }

    fun connect(url: String, password: String? = null, name: String = "AndroidClient", encryptionKey: String = "", serverId: String = "") {
        Log.d("WebSocket", "Попытка подключения к: $url")
        LogStorage.addLog("Попытка подключения к: $url")

        lastUrl = url
        lastPassword = password
        lastName = name
        this.encryptionKey = encryptionKey
        currentServerId = serverId
        intentionalDisconnect = false // Reset intentional disconnect flag on new connection

        cancelAuthTimeout()
        
        _currentState.value = WebSocketState.CONNECTING
        isAuthenticated = false

        try {
            val request = Request.Builder().url(url).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("WebSocket", "✅ WebSocket подключен успешно")
                    LogStorage.addLog("WebSocket подключен успешно")
                    _currentState.value = WebSocketState.CONNECTED

                    val unifiedMessage = JSONObject().apply {
                        put("version", 1)
                        put("timestamp", System.currentTimeMillis())
                        put("type", "auth")
                        put("name", name)
                        put("password", password ?: "")
                    }

                    Log.d("WebSocket", "Отправка JSON аутентификации: $unifiedMessage")
                    LogStorage.addLog("Отправка данных аутентификации")
                    webSocket.send(unifiedMessage.toString())
                    
                    // Start auth timeout
                    startAuthTimeout()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WebSocket", "Получено: $text")
                    LogStorage.addLog("Получено: $text")

                    try {
                        val json = JSONObject(text)

                        if (json.has("type")) {
                            when (val msgTypeStr = json.optString("type", "").lowercase()) {
                                "auth_result", "auth", "auth_response", "authresult" -> {
                                    cancelAuthTimeout()
                                    handleAuthResultMessage(json)
                                }
                                "event" -> {
                                    handleEventMessage(json)
                                }
                                "chat" -> {
                                    handleChatMessage(json)
                                }
                                "error" -> {
                                    handleErrorMessage(json)
                                }
                                "system" -> {
                                    val payload = json.optJSONObject("payload") ?: json
                                    handleSystemMessage(payload)
                                }
                                "ping" -> {
                                    // Server ping - respond with pong
                                    val pongMsg = JSONObject().apply {
                                        put("type", "pong")
                                        put("timestamp", System.currentTimeMillis())
                                    }
                                    webSocket.send(pongMsg.toString())
                                }
                                "pong" -> {
                                    // Received pong from server - connection is healthy
                                    handlePongReceived()
                                }
                                else -> {
                                    Log.d("WebSocket", "Неизвестный тип сообщения: $msgTypeStr")
                                }
                            }
                        } else {
                            Log.d("WebSocket", "Неверный формат сообщения (нет поля type)")
                        }
                    } catch (e: Exception) {
                        Log.e("WebSocket", "Ошибка обработки сообщения: ${e.message}", e)
                    }
                    
                    // Notify ALL listeners
                    notifyMessageListeners(text, false)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d("WebSocket", "Получены бинарные данные")
                    LogStorage.addLog("Получены бинарные данные")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WebSocket", "Соединение закрывается: $reason")
                    LogStorage.addLog("Соединение закрывается: $reason")

                    isAuthenticated = false
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WebSocket", "Соединение закрыто: $reason (код: $code)")
                    LogStorage.addLog("Соединение закрыто: $reason (код: $code)")

                    cancelAuthTimeout()
                    stopPingPong()
                    
                    val isNormalClosure = code == 1000 || code == 1001
                    // 1006 = abnormal closure (e.g. network drop, TLS reset).
                    // This is a transient failure and SHOULD trigger a reconnect.

                    // If we intentionally disconnected, always treat as clean disconnect
                    // regardless of the close code returned by the server
                    if (intentionalDisconnect) {
                        _currentState.value = WebSocketState.DISCONNECTED
                    } else if (isNormalClosure) {
                        // Only clean closures (1000/1001) stay disconnected.
                        _currentState.value = WebSocketState.DISCONNECTED
                    } else {
                        _currentState.value = WebSocketState.ERROR
                        // Auto-reconnect for unexpected disconnections
                        scheduleReconnect()
                    }
                    isAuthenticated = false
                    intentionalDisconnect = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WebSocket", "Ошибка подключения: ${t.message}", t)
                    LogStorage.addLog("Ошибка подключения: ${t.message}")
                    
                    cancelAuthTimeout()
                    stopPingPong()
                    
                    // Don't set ERROR state if we're intentionally disconnecting
                    if (!intentionalDisconnect) {
                        _currentState.value = WebSocketState.ERROR
                        // Schedule reconnect on failure
                        scheduleReconnect()
                    }
                    isAuthenticated = false

                    val errorMsg = JSONObject().apply {
                        put("type", "system")
                        put("payload", JSONObject().apply {
                            put("content", "Ошибка подключения: ${t.message}")
                        })
                        put("timestamp", System.currentTimeMillis())
                    }
                    notifyMessageListeners(errorMsg.toString(), false)
                }
            })
        } catch (e: Exception) {
            Log.e("WebSocket", "Исключение при подключении: ${e.message}", e)
            LogStorage.addLog("Исключение: ${e.message}")
            cancelAuthTimeout()
            stopPingPong()
            _currentState.value = WebSocketState.ERROR
            isAuthenticated = false

            val errorMsg = JSONObject().apply {
                put("type", "system")
                put("payload", JSONObject().apply {
                    put("content", "Исключение при подключении: ${e.message}")
                })
                put("timestamp", System.currentTimeMillis())
            }
            notifyMessageListeners(errorMsg.toString(), false)
        }
    }

    private fun startAuthTimeout() {
        cancelAuthTimeout()
        authTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
            delay(authTimeoutMs)
            // Check if still in CONNECTED state (not yet authenticated)
            if (_currentState.value == WebSocketState.CONNECTED && !isAuthenticated) {
                Log.w("WebSocket", "Таймаут аутентификации (${authTimeoutMs}мс)")
                LogStorage.addLog("Таймаут аутентификации")
                _currentState.value = WebSocketState.ERROR
                isAuthenticated = false
                
                val errorMsg = JSONObject().apply {
                    put("type", "system")
                    put("payload", JSONObject().apply {
                        put("content", "Таймаут аутентификации: сервер не ответил")
                    })
                    put("timestamp", System.currentTimeMillis())
                }
                notifyMessageListeners(errorMsg.toString(), false)
            }
        }
    }

    private fun cancelAuthTimeout() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
    }

    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val reconnectBaseDelayMs = 2000L   // start at 2s
    private val reconnectMaxDelayMs = 60000L   // cap at 60s
    private val reconnectFactor = 2.0

    /**
     * Exponential backoff with jitter to avoid reconnect storms when many
     * clients drop at once (e.g. server restart, network blip).
     */
    private fun nextReconnectDelayMs(): Long {
        val exp = (reconnectBaseDelayMs * Math.pow(reconnectFactor, reconnectAttempts.toDouble())).toLong()
        val capped = exp.coerceAtMost(reconnectMaxDelayMs)
        // Add up to 30% random jitter so clients don't reconnect in lockstep.
        val jitter = (capped * 0.3 * Math.random()).toLong()
        return capped + jitter
    }

    private fun scheduleReconnect() {
        // Only reconnect if we have a saved URL and not intentionally disconnected
        if (lastUrl != null && !intentionalDisconnect) {
            reconnectJob?.cancel()
            val delayMs = nextReconnectDelayMs()
            reconnectAttempts++
            reconnectJob = CoroutineScope(Dispatchers.IO).launch {
                delay(delayMs)
                if (!intentionalDisconnect && lastUrl != null) {
                    Log.d("WebSocket", "Попытка переподключения #$reconnectAttempts к: $lastUrl (через ${delayMs}мс)")
                    LogStorage.addLog("Попытка переподключения #$reconnectAttempts к: $lastUrl")
                    connect(lastUrl!!, lastPassword, lastName, encryptionKey, currentServerId)
                }
            }
        }
    }

    private fun startPingPong() {
        stopPingPong()
        lastPongReceived = System.currentTimeMillis()
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (!intentionalDisconnect && _currentState.value != WebSocketState.DISCONNECTED) {
                delay(pingIntervalMs)
                if (intentionalDisconnect || _currentState.value == WebSocketState.DISCONNECTED) break
                
                val socket = webSocket
                if (socket != null && _currentState.value == WebSocketState.AUTHENTICATED) {
                    pingSentTime = System.currentTimeMillis()
                    val pingMsg = JSONObject().apply {
                        put("type", "ping")
                        put("timestamp", pingSentTime)
                    }
                    socket.send(pingMsg.toString())
                    // Don't force close on pong timeout - just log it
                    // The OkHttp pingInterval will handle keep-alive
                }
            }
        }
    }

    private fun stopPingPong() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun handlePongReceived() {
        lastPongReceived = System.currentTimeMillis()
        val rtt = lastPongReceived - pingSentTime
        Log.d("WebSocket", "Pong received, RTT: ${rtt}ms")
    }

    private fun handleAuthResultMessage(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val clientId = json.optInt("client_id", 0)
        val message = json.optString("message", "")

        if (success) {
            val wasReconnecting = isAuthenticated == false && _currentState.value != WebSocketState.DISCONNECTED
            isAuthenticated = true
            this.clientId = clientId
            this.clientName = lastName
            _currentState.value = WebSocketState.AUTHENTICATED
            // Successful (re)connection: reset backoff so the next drop
            // starts again from the short base delay.
            reconnectAttempts = 0
            reconnectJob?.cancel()
            reconnectJob = null
            LogStorage.addLog("Аутентификация успешна (client_id=$clientId)")

            val infoMsg = JSONObject().apply {
                put("type", "informational")
                put("payload", JSONObject().apply {
                    put("content", "Вы успешно подключились к серверу")
                })
                put("timestamp", System.currentTimeMillis())
            }
            notifyMessageListeners(infoMsg.toString(), true)
            
            // Notify about reconnection for security (clear chat history)
            if (wasReconnecting) {
                notifyReconnectListeners()
            }
        } else {
            isAuthenticated = false
            this.clientId = 0
            this.clientName = ""
            _currentState.value = WebSocketState.ERROR
            val systemMsg = JSONObject().apply {
                put("type", "system")
                put("payload", JSONObject().apply {
                    put("content", "Ошибка аутентификации: $message")
                })
                put("timestamp", System.currentTimeMillis())
            }
            notifyMessageListeners(systemMsg.toString(), false)
            LogStorage.addLog("Ошибка аутентификации: $message")
        }
    }

    private fun handleEventMessage(json: JSONObject) {
        val event = json.optString("event", "")
        val userId = json.optInt("user_id", 0)
        val userName = json.optString("user_name", "Пользователь")

        val content = when (event) {
            "joined" -> "Пользователь $userName присоединился к чату"
            "left" -> "Пользователь $userName покинул чат"
            else -> "Пользователь $userName $event"
        }

        val infoMsg = JSONObject().apply {
            put("type", "informational")
            put("payload", JSONObject().apply {
                put("content", content)
                put("event", event)
                put("user_name", userName)
                put("user_id", userId)
            })
            put("timestamp", System.currentTimeMillis())
        }
        notifyMessageListeners(infoMsg.toString(), true)
    }

    private fun handleChatMessage(json: JSONObject) {
        // Server now sends: content, sender_id (optional), sender_name (optional)
        val senderName = json.optString("sender_name", json.optString("user_name", "Неизвестный"))
        val senderId = json.optInt("sender_id", 0)
        var content = json.optString("content", json.optString("message", ""))

        if (encryptionKey.isNotEmpty()) {
            content = MessageEncryption.decrypt(content, encryptionKey)
        }

        // Mark as "own" when the server echoes our own message back, so the UI
        // does not show a duplicate of what we already rendered locally on send.
        val isOwn = clientId != 0 && senderId == clientId

        val contentMsg = JSONObject().apply {
            put("type", "content")
            put("payload", JSONObject().apply {
                put("sender_name", senderName)
                put("sender_id", senderId)
                put("content", content)
                put("is_own", isOwn)
            })
            put("timestamp", System.currentTimeMillis())
        }
        notifyMessageListeners(contentMsg.toString(), false)
    }

    private fun handleErrorMessage(json: JSONObject) {
        val code = json.optInt("code", 0)
        val message = json.optString("message", "")
        val systemMsg = JSONObject().apply {
            put("type", "system")
            put("payload", JSONObject().apply {
                put("content", "Ошибка сервера ($code): $message")
            })
            put("timestamp", System.currentTimeMillis())
        }
        notifyMessageListeners(systemMsg.toString(), false)
        LogStorage.addLog("Ошибка сервера ($code): $message")
    }

    private fun handleSystemMessage(payload: JSONObject) {
        val content = payload.optString("content", "")
        val systemMsg = JSONObject().apply {
            put("type", "system")
            put("payload", JSONObject().apply {
                put("content", content)
            })
            put("timestamp", System.currentTimeMillis())
        }
        notifyMessageListeners(systemMsg.toString(), false)
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

        // Allow sending in CONNECTED state too (for auth retry), but primarily AUTHENTICATED
        val currentState = _currentState.value
        if ((currentState == WebSocketState.AUTHENTICATED || currentState == WebSocketState.CONNECTED) && message.isNotEmpty()) {
            try {
                var encryptedMessage = message
                if (encryptionKey.isNotEmpty()) {
                    encryptedMessage = MessageEncryption.encrypt(message, encryptionKey)
                }

                val contentMsg = JSONObject().apply {
                    put("version", 1)
                    put("timestamp", System.currentTimeMillis())
                    put("type", "chat")
                    put("sender_name", lastName)
                    put("content", encryptedMessage)
                }

                val jsonString = contentMsg.toString()
                Log.d("WebSocket", "Sending JSON: $jsonString")
                val success = socket.send(jsonString)

                if (success) {
                    Log.d("WebSocket", "Сообщение отправлено успешно")
                    LogStorage.addLog("Сообщение отправлено успешно")
                    return true
                } else {
                    Log.e("WebSocket", "Не удалось отправить - socket.send вернул false")
                    LogStorage.addLog("Не удалось отправить - socket.send вернул false")
                    return false
                }
            } catch (e: Exception) {
                Log.e("WebSocket", "Ошибка отправки: ${e.message}", e)
                LogStorage.addLog("Ошибка отправки: ${e.message}")
                return false
            }
        } else {
            Log.w("WebSocket", "Невозможно отправить сообщение (state: $currentState, message empty: ${message.isEmpty()})")
            LogStorage.addLog("Невозможно отправить сообщение (state: $currentState)")
            return false
        }
    }

    fun disconnect() {
        Log.d("WebSocket", "Отключение")
        LogStorage.addLog("Отключение")
        intentionalDisconnect = true
        cancelAuthTimeout()
        reconnectJob?.cancel()
        reconnectJob = null

        val socket = webSocket
        if (socket != null) {
            try {
                socket.close(1000, "Отключение")
            } catch (e: Exception) {
                Log.e("WebSocket", "Ошибка отключения: ${e.message}", e)
                LogStorage.addLog("Ошибка отключения: ${e.message}")
            }
            // Immediately update state so UI can react without waiting for onClosed
            _currentState.value = WebSocketState.DISCONNECTED
            isAuthenticated = false
            clientId = 0
            clientName = ""
        } else {
            // No active connection, just reset state
            _currentState.value = WebSocketState.DISCONNECTED
            isAuthenticated = false
            clientId = 0
            clientName = ""
            intentionalDisconnect = false
        }
    }

    fun getClientId(): Int = clientId
    fun getClientName(): String = clientName

    // Force state update - useful for UI synchronization
    fun forceStateUpdate(state: WebSocketState) {
        _currentState.value = state
    }

    /** Returns true if the WebSocket is connected and authenticated, ready to send messages. */
    fun canSendMessages(): Boolean {
        val state = _currentState.value
        return webSocket != null && (state == WebSocketState.AUTHENTICATED || state == WebSocketState.CONNECTED)
    }

    /** Returns the current connection state for UI display. */
    fun getConnectionState(): WebSocketState = _currentState.value
}