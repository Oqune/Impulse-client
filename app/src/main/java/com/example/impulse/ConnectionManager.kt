package com.example.impulse

import android.content.Context
import com.example.impulse.data.ServerConfig
import com.example.impulse.transport.ConnectionState
import com.example.impulse.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages multiple [ChatController] instances — one per server.
 * Singleton that coordinates all server connections.
 */
class ConnectionManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controllers = mutableMapOf<String, ChatController>()
    private val observerJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private val _serverStates = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())
    val serverStates: StateFlow<Map<String, ServerStatus>> = _serverStates.asStateFlow()

    data class ServerStatus(
        val server: ServerConfig,
        val state: ConnectionState,
        val lastError: String?,
        val lastMessage: String?,
        val lastMessageTime: Long = 0L,
    )

    @Synchronized
    fun getController(server: ServerConfig): ChatController {
        return controllers.getOrPut(server.id) {
            LogManager.i(TAG, "Creating ChatController for server=${server.id} (${server.name})")
            val ctrl = ChatController(context.applicationContext)
            observeController(server, ctrl)
            ctrl
        }
    }

    @Synchronized
    fun getControllerOrNull(serverId: String): ChatController? {
        return controllers[serverId]
    }

    private fun observeController(server: ServerConfig, controller: ChatController) {
        observerJobs[server.id]?.cancel()
        observerJobs[server.id] = scope.launch {
            controller.state.collect {
                refreshStates()
            }
        }
        observerJobs["error_${server.id}"]?.cancel()
        observerJobs["error_${server.id}"] = scope.launch {
            controller.lastError.collect {
                refreshStates()
            }
        }
    }

    fun connect(server: ServerConfig, clientName: String) {
        val controller = getController(server)
        LogManager.i(TAG, "Connecting to server=${server.id} (${server.name})")
        controller.connect(server, clientName)
        refreshStates()
    }

    @Synchronized
    fun disconnect(serverId: String) {
        controllers[serverId]?.disconnect()
        LogManager.i(TAG, "Disconnected from server=$serverId")
        refreshStates()
    }

    fun disconnectAll() {
        synchronized(this) {
            controllers.values.forEach { it.disconnect() }
        }
        LogManager.i(TAG, "Disconnected from all servers")
        refreshStates()
    }

    @Synchronized
    fun refreshStates() {
        val map = mutableMapOf<String, ServerStatus>()
        for ((id, ctrl) in controllers) {
            val server = ctrl.currentServer ?: continue
            map[id] = ServerStatus(
                server = server,
                state = ctrl.state.value,
                lastError = ctrl.lastError.value,
                lastMessage = null,
                lastMessageTime = 0L,
            )
        }
        _serverStates.value = map
    }

    fun setAutoReconnect(enabled: Boolean) {
        synchronized(this) {
            controllers.values.forEach { it.setAutoReconnect(enabled) }
        }
    }

    fun setServerAutoReconnect(serverId: String, enabled: Boolean) {
        synchronized(this) {
            controllers[serverId]?.setAutoReconnect(enabled)
        }
    }

    fun getPublicKeyHash(): String {
        synchronized(this) {
            return controllers.values.firstOrNull()?.publicKeyHash ?: ""
        }
    }

    companion object {
        private const val TAG = "ConnectionManager"

        @Volatile
        private var INSTANCE: ConnectionManager? = null

        fun getInstance(context: Context): ConnectionManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectionManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
