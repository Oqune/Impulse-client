package com.example.impulse.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /** Inserts a message, ignoring exact duplicates (same server + server_msg_id). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    /**
     * Inserts or replaces a message keyed by (server_id, server_msg_id). Used to
     * replace an optimistic local copy (negative temp id) with the authoritative
     * server-assigned row once the relay echoes the message.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity): Long

    /** All messages for a server, ordered oldest → newest. */
    @Query("SELECT * FROM messages WHERE server_id = :serverId ORDER BY server_msg_id ASC")
    fun observeForServer(serverId: String): Flow<List<MessageEntity>>

    /** One-off load (used during history backfill). */
    @Query("SELECT * FROM messages WHERE server_id = :serverId ORDER BY server_msg_id ASC")
    suspend fun loadForServer(serverId: String): List<MessageEntity>

    /** Highest server_msg_id we already have for a server (for last_seen_id sync). */
    @Query("SELECT COALESCE(MAX(server_msg_id), 0) FROM messages WHERE server_id = :serverId")
    suspend fun maxServerMsgId(serverId: String): Long

    /** Deletes messages older than [olderThanMillis] (TTL cleanup). */
    @Query("DELETE FROM messages WHERE timestamp < :olderThanMillis")
    suspend fun deleteOlderThan(olderThanMillis: Long): Int

    /** Removes all messages for a single server. */
    @Query("DELETE FROM messages WHERE server_id = :serverId")
    suspend fun clearServer(serverId: String)
}
