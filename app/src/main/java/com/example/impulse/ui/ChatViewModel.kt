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

    private fun mergeAndSort(
        dbMessages: List<ChatController.DecryptedMessage>
    ): List<ChatController.DecryptedMessage> {
        // Drop pending entries whose real counterpart is now in the DB
        val dbIds = dbMessages.map { it.serverMsgId }.toSet()
        pendingOptimistic.removeAll { pending ->
            // Remove if the real message arrived, or if a DB entry matches by sender+content
            pending.serverMsgId in dbIds ||
                dbMessages.any { it.sender == pending.sender && it.plaintext == pending.plaintext }
        }
        return (dbMessages + pendingOptimistic).distinctBy { it.serverMsgId }.sortedBy { kotlin.math.abs(it.serverMsgId) }
    }

    private val optimisticListener: (ChatController.DecryptedMessage) -> Unit = { dm ->
        if (dm.serverMsgId < 0) {
            // Optimistic placeholder — show immediately, will be replaced when
            // the server echoes the real message back.
            pendingOptimistic.add(dm)
            _messages.value = mergeAndSort(_messages.value)
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
        pendingOptimistic.clear()
        chatController.clearHistory()
    }

    override fun onCleared() {
        chatController.removeMessageListener(optimisticListener)
        super.onCleared()
    }
}
