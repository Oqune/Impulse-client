package com.example.impulse.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Metadata-only replay marker. The raw signed nonce is never stored. */
@Entity(
    tableName = "seen_nonces",
    primaryKeys = ["sender_fingerprint", "nonce_hash"],
    indices = [Index(value = ["expires_at"])]
)
data class SeenMessageNonceEntity(
    @ColumnInfo(name = "sender_fingerprint") val senderFingerprint: String,
    @ColumnInfo(name = "nonce_hash") val nonceHash: String,
    @ColumnInfo(name = "expires_at") val expiresAt: Long
)
