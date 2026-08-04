package com.example.impulse.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.impulse.ChatController
import com.example.impulse.data.MessageRepository
import com.example.impulse.data.ServerConfig

/**
 * Factory for [ChatViewModel]. The controller and repository are singletons /
 * application-scoped, so we pass them explicitly rather than recreating them.
 */
class ChatViewModelFactory(
    private val chatController: ChatController,
    private val repository: MessageRepository,
    private val server: ServerConfig,
    private val conversationId: String = "group"
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == ChatViewModel::class.java) {
            "Unknown ViewModel class: $modelClass"
        }
        return ChatViewModel(chatController, repository, server, conversationId) as T
    }
}
