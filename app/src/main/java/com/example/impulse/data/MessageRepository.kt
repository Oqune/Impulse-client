package com.example.impulse.data

import android.content.Context
import com.example.impulse.data.db.MessageDao
import com.example.impulse.data.db.MessageDatabase
import com.example.impulse.data.db.MessageEntity
import com.example.impulse.util.LogManager
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * High-level access to the encrypted local message store.
 *
 * Responsibilities:
 *  - Persist incoming / outgoing messages (ciphertext + iv).
 *  - Expose a reactive stream of messages for the UI.
 *  - Enforce the 72-hour TTL: a periodic [purgeExpired] deletes anything older
 *    than [TTL_HOURS] hours.
 */
class MessageRepository(context: Context) {

    private val dao: MessageDao = MessageDatabase.getInstance(context).messageDao()

    /**
     * Inserts or replaces a message keyed by (server_id, server_msg_id). Used to
     * replace an optimistic local copy (negative temp id) with the authoritative
     * server-assigned row once the relay echoes the message.
     */
    suspend fun upsert(message: MessageEntity) {
        dao.upsert(message)
    }

    fun observe(serverId: String): Flow<List<MessageEntity>> = dao.observeForServer(serverId)

    suspend fun load(serverId: String): List<MessageEntity> = dao.loadForServer(serverId)

    /** Highest server-assigned message id we already hold (for `last_seen_id`). */
    suspend fun lastSeenId(serverId: String): Long = dao.maxServerMsgId(serverId)

    suspend fun clearServer(serverId: String) = dao.clearServer(serverId)

    /** Removes stale optimistic placeholders (negative server_msg_id). */
    suspend fun clearTempMessages(serverId: String): Int {
        val removed = dao.clearTempMessages(serverId)
        if (removed > 0) LogManager.i("MessageRepository", "Cleaned up $removed temp messages for $serverId")
        return removed
    }

    /**
     * Deletes messages older than the TTL. Returns the number of rows removed.
     * Should be called periodically (e.g. on connect and every few hours).
     */
    suspend fun purgeExpired(): Int {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(TTL_HOURS)
        val removed = dao.deleteOlderThan(cutoff)
        LogManager.i("MessageRepository", "TTL purge: removed $removed messages older than $TTL_HOURS h")
        return removed
    }

    companion object {
        const val TTL_HOURS = 72L
    }
}
