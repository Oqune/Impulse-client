package com.example.impulse.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.impulse.ChatController
import com.example.impulse.data.MessageRepository
import com.example.impulse.data.ServerConfig
import com.example.impulse.transport.ConnectionState
import com.example.impulse.util.LogManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatViewModel(
    private val chatController: ChatController,
    private val repository: MessageRepository,
    private val server: ServerConfig
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = chatController.state

    private val _messages = MutableStateFlow<List<ChatController.DecryptedMessage>>(emptyList())
    val messages: StateFlow<List<ChatController.DecryptedMessage>> = _messages.asStateFlow()

    // Optimistic (temp) messages not yet echoed back by the server.
    // Removed only when a NEW DB row with the same (sender, plaintext) arrives
    // (the server echo) — one echo confirms at most one optimistic copy, so a
    // repeated message text never causes a just-sent message to disappear.
    private val pendingOptimistic = mutableListOf<ChatController.DecryptedMessage>()
    private val optimisticMutex = Mutex()
    private var lastRawDb: List<ChatController.DecryptedMessage> = emptyList()
    private var lastDbContentCounts: Map<Pair<String, String>, Int> = emptyMap()

    private suspend fun mergeAndSort(
        dbMessages: List<ChatController.DecryptedMessage>
    ): List<ChatController.DecryptedMessage> = optimisticMutex.withLock {
        lastRawDb = dbMessages
        val dbContentCounts = dbMessages.groupingBy { it.sender to it.plaintext }.eachCount()
        val prevCounts = lastDbContentCounts
        lastDbContentCounts = dbContentCounts
        // Only remove optimistic copies confirmed by a newly-arrived DB row.
        val consumed = mutableMapOf<Pair<String, String>, Int>()
        val iter = pendingOptimistic.iterator()
        while (iter.hasNext()) {
            val pending = iter.next()
            val key = pending.sender to pending.plaintext
            val arrived = (dbContentCounts[key] ?: 0) - (prevCounts[key] ?: 0)
            val used = consumed[key] ?: 0
            if (arrived > used) {
                consumed[key] = used + 1
                iter.remove()
            }
        }
        val all = dbMessages + pendingOptimistic
        all.sortedBy { it.timestamp }
    }

    private val optimisticListener: (ChatController.DecryptedMessage) -> Unit = { dm ->
        if (dm.serverMsgId < 0) {
            viewModelScope.launch {
                optimisticMutex.withLock { pendingOptimistic.add(dm) }
                _messages.value = mergeAndSort(lastRawDb)
            }
        }
    }

    init {
        // DB observer: the authoritative source for all persisted messages.
        viewModelScope.launch {
            repository.observe(server.id)
                .map { entities ->
                    entities.mapNotNull { entity -> chatController.decryptEntity(entity) }
                }
                .catch { e ->
                    LogManager.e("ChatViewModel", "observe failed", e)
                }
                .collect { dbMessages ->
                    _messages.value = mergeAndSort(dbMessages)
                }
        }

        // Optimistic listener: instant UI feedback for sent messages.
        chatController.addMessageListener(optimisticListener)
    }

    /** Sends a chat message; returns true if it was accepted by the transport. */
    suspend fun send(text: String): Boolean {
        if (text.isBlank()) return false
        return chatController.sendChat(text)
    }

    /** Clears local message history for this server and re-syncs from the server. */
    suspend fun clearHistory() {
        optimisticMutex.withLock { pendingOptimistic.clear() }
        chatController.clearHistory()
    }

    override fun onCleared() {
        chatController.removeMessageListener(optimisticListener)
        super.onCleared()
    }
}
