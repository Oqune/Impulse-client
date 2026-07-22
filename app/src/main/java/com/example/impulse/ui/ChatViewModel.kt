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

/**
 * MVVM bridge between the chat UI and the data/transport layers.
 *
 * Exposes:
 *  - [messages]: a reactive stream of decrypted messages for the current server,
 *    sourced from [MessageRepository.observe] (Room) and kept in sync with live
 *    inbound messages via [ChatController.addMessageListener].
 *  - [connectionState]: the live transport state.
 *
 * The UI only collects these flows; all crypto, persistence and transport
 * concerns stay behind the controller/repository boundaries.
 */
class ChatViewModel(
    private val chatController: ChatController,
    private val repository: MessageRepository,
    private val server: ServerConfig
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = chatController.state

    private val _messages = MutableStateFlow<List<ChatController.DecryptedMessage>>(emptyList())
    val messages: StateFlow<List<ChatController.DecryptedMessage>> = _messages.asStateFlow()

    private val liveListener: (ChatController.DecryptedMessage) -> Unit = { dm ->
        _messages.value = (_messages.value + dm)
            .distinctBy { it.serverMsgId }
            .sortedBy { it.timestamp }
    }

    init {
        // Seed from the local store, then keep it live.
        viewModelScope.launch {
            repository.observe(server.id)
                .map { entities ->
                    // Only show rows we can currently decrypt (group secret ready).
                    // The controller decrypts on the fly; rows that fail stay hidden
                    // until the secret is established (they re-appear via the listener).
                    entities.mapNotNull { entity -> chatController.decryptEntity(entity) }
                }
                    .catch { e ->
                        // A Room/DB error must never cancel the UI stream. Log it and
                        // keep the last known list so the chat stays usable.
                        LogManager.e("ChatViewModel", "observe failed", e)
                    }
                .collect { _messages.value = it }
        }

        // Live inbound / outbound messages pushed by the controller.
        chatController.addMessageListener(liveListener)
    }

    /** Sends a chat message; returns true if it was accepted by the transport. */
    suspend fun send(text: String): Boolean {
        if (text.isBlank()) return false
        return chatController.sendChat(text)
    }

    override fun onCleared() {
        chatController.removeMessageListener(liveListener)
        super.onCleared()
    }
}
