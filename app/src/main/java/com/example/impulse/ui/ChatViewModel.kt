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
    // Keyed by (sender + plaintext) so we can deduplicate when the real
    // message arrives via the DB observer.
    private val pendingOptimistic = mutableListOf<ChatController.DecryptedMessage>()
    private val optimisticMutex = Mutex()

    private suspend fun mergeAndSort(
        dbMessages: List<ChatController.DecryptedMessage>
    ): List<ChatController.DecryptedMessage> = optimisticMutex.withLock {
        // Build a set of (sender, plaintext) keys from DB for content-based dedup.
        // This matches optimistic messages (negative temp IDs) to their confirmed
        // DB counterparts (positive server IDs) — temp IDs never match server IDs.
        val dbContentKeys = dbMessages.map { it.sender to it.plaintext }.toSet()
        pendingOptimistic.removeAll { pending ->
            (pending.sender to pending.plaintext) in dbContentKeys
        }
        // DB messages are authoritative; optimistic fills gaps for messages not yet in DB.
        val all = dbMessages + pendingOptimistic
        all.sortedBy { it.timestamp }
    }

    private val optimisticListener: (ChatController.DecryptedMessage) -> Unit = { dm ->
        if (dm.serverMsgId < 0) {
            viewModelScope.launch {
                optimisticMutex.withLock { pendingOptimistic.add(dm) }
                _messages.value = mergeAndSort(_messages.value)
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
