package com.example.impulse.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted chat message.
 *
 * Fields required by the target architecture:
 *  - [serverId]  : which server the message belongs to (per-server isolation).
 *  - [serverMsgId]: server-assigned monotonic id, used for `last_seen_id` sync.
 *  - [sender]    : display name of the sender.
 *  - [ciphertext]: AES-256-GCM output (IV || ciphertext) – never plaintext.
 *  - [iv]        : kept separately for clarity (also embedded in [ciphertext]).
 *  - [timestamp] : epoch millis; used for the 72h TTL cleanup.
 *  - [isOwn]     : whether this message was sent by the local user.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["server_id"]),
        Index(value = ["server_id", "server_msg_id"], unique = true)
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "server_msg_id") val serverMsgId: Long,
    @ColumnInfo(name = "sender") val sender: String,
    @ColumnInfo(name = "ciphertext") val ciphertext: ByteArray,
    @ColumnInfo(name = "iv") val iv: ByteArray,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_own", defaultValue = "0") val isOwn: Boolean = false,
    /**
     * Conversation discriminator: "group" for broadcasts, or the peer
     * fingerprint for a private 1:1 chat ("dm:<fp>"). Used to keep DMs out of
     * the group feed (Bug: "incoming DMs showed in the group chat").
     */
    @ColumnInfo(name = "conversation_id", defaultValue = "group") val conversationId: String = "group"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        return id == other.id && serverId == other.serverId && serverMsgId == other.serverMsgId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + serverId.hashCode()
}
