package com.example.impulse.data

import com.example.impulse.data.db.SeenMessageNonceEntity
import java.security.MessageDigest

internal object ReplayNonce {
    const val FRESHNESS_WINDOW_MS = 96L * 60L * 60L * 1000L
    const val MAX_LIVE_MARKERS = 250_000

    fun isTimestampFresh(
        clientTimestamp: Long,
        now: Long,
        freshnessWindowMs: Long = FRESHNESS_WINDOW_MS
    ): Boolean =
        clientTimestamp == 0L ||
            (clientTimestamp >= now - freshnessWindowMs && clientTimestamp <= now + freshnessWindowMs)

    fun marker(senderFingerprint: String, nonce: String, clientTimestamp: Long, acceptedAt: Long): SeenMessageNonceEntity? {
        if (nonce.isEmpty()) return null
        val retainedFrom = maxOf(acceptedAt, clientTimestamp.takeIf { it != 0L } ?: acceptedAt)
        // Freshness includes the exact endpoint; retain its marker through that
        // millisecond because cleanup deletes markers at expiresAt <= now.
        val retentionMs = FRESHNESS_WINDOW_MS + 1L
        val expiresAt = if (retainedFrom > Long.MAX_VALUE - retentionMs) Long.MAX_VALUE
        else retainedFrom + retentionMs
        return SeenMessageNonceEntity(senderFingerprint, hash(nonce), expiresAt)
    }

    fun hash(nonce: String): String = MessageDigest.getInstance("SHA-256")
        .digest(nonce.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
