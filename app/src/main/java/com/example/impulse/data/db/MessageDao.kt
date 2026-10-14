package com.example.impulse.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * Inserts or replaces a message keyed by (server_id, server_msg_id).
     * A negative local ID and a positive relay ID are distinct rows; callers
     * explicitly remove optimistic placeholders after accepting a relay echo.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReplayMarker(marker: SeenMessageNonceEntity): Long

    @Query("DELETE FROM seen_nonces WHERE expires_at <= :now")
    suspend fun deleteExpiredReplayMarkers(now: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM seen_nonces WHERE sender_fingerprint = :senderFingerprint AND nonce_hash = :nonceHash)")
    suspend fun hasReplayMarker(senderFingerprint: String, nonceHash: String): Boolean

    @Query("SELECT COUNT(*) FROM seen_nonces WHERE expires_at > :now")
    suspend fun countLiveReplayMarkers(now: Long): Int

    @Query("DELETE FROM seen_nonces WHERE expires_at <= :now")
    suspend fun purgeExpiredReplayMarkers(now: Long): Int

    /** Atomically claims the durable replay key and writes its encrypted message. */
    @Transaction
    suspend fun upsertWithReplayGuard(
        message: MessageEntity,
        marker: SeenMessageNonceEntity,
        now: Long,
        maxLiveMarkers: Int
    ): ReplayPersistenceResult {
        deleteExpiredReplayMarkers(now)
        if (hasReplayMarker(marker.senderFingerprint, marker.nonceHash)) {
            return ReplayPersistenceResult.DUPLICATE
        }
        if (countLiveReplayMarkers(now) >= maxLiveMarkers) {
            return ReplayPersistenceResult.CAPACITY_REACHED
        }
        if (insertReplayMarker(marker) == -1L) {
            return ReplayPersistenceResult.DUPLICATE
        }
        upsert(message)
        return ReplayPersistenceResult.ACCEPTED
    }

    /** All messages for a server, ordered oldest → newest. */
    @Query("SELECT * FROM messages WHERE server_id = :serverId ORDER BY server_msg_id ASC")
    fun observeForServer(serverId: String): Flow<List<MessageEntity>>

    /** Messages for one conversation (group or a DM), oldest → newest. */
    @Query("SELECT * FROM messages WHERE server_id = :serverId AND conversation_id = :conversationId ORDER BY server_msg_id ASC")
    fun observeForConversation(serverId: String, conversationId: String): Flow<List<MessageEntity>>

    /** One-off load (used during history backfill). */
    @Query("SELECT * FROM messages WHERE server_id = :serverId ORDER BY server_msg_id ASC")
    suspend fun loadForServer(serverId: String): List<MessageEntity>

    /** One-off load for one conversation. */
    @Query("SELECT * FROM messages WHERE server_id = :serverId AND conversation_id = :conversationId ORDER BY server_msg_id ASC")
    suspend fun loadForConversation(serverId: String, conversationId: String): List<MessageEntity>

    /** All distinct conversations for a server. */
    @Query("SELECT DISTINCT conversation_id FROM messages WHERE server_id = :serverId")
    suspend fun conversationsForServer(serverId: String): List<String>

    /** Live version of [conversationsForServer]: emits whenever any row changes,
     *  so a newly-arrived DM appears in the conversation list without leaving it. */
    @Query("SELECT DISTINCT conversation_id FROM messages WHERE server_id = :serverId")
    fun observeConversationsForServer(serverId: String): Flow<List<String>>

    /** Highest server_msg_id we already have for a server (for last_seen_id sync). */
    @Query("SELECT COALESCE(MAX(server_msg_id), 0) FROM messages WHERE server_id = :serverId")
    suspend fun maxServerMsgId(serverId: String): Long

    /** Deletes messages older than [olderThanMillis] (TTL cleanup). */
    @Query("DELETE FROM messages WHERE timestamp < :olderThanMillis")
    suspend fun deleteOlderThan(olderThanMillis: Long): Int

    /** Removes all messages for a single server. */
    @Query("DELETE FROM messages WHERE server_id = :serverId")
    suspend fun clearServer(serverId: String)

    /** Removes optimistic placeholder rows (negative server_msg_id). */
    @Query("DELETE FROM messages WHERE server_id = :serverId AND server_msg_id < 0")
    suspend fun clearTempMessages(serverId: String): Int
}
