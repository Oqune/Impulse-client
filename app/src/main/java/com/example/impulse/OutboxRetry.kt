package com.example.impulse

internal interface PersistedOutboxFrame {
    val serverId: String
    val frame: ByteArray
    val queuedAt: Long
}

/** Merges persisted frames idempotently while a restored frame awaits delivery. */
internal fun <T : PersistedOutboxFrame> mergeRestoredOutbox(
    existing: List<T>,
    restored: List<T>,
    create: (serverId: String, frame: ByteArray, queuedAt: Long) -> T
): List<T> {
    val merged = existing.toMutableList()
    for (candidate in restored) {
        val duplicate = merged.any {
            it.serverId == candidate.serverId && it.frame.contentEquals(candidate.frame)
        }
        if (!duplicate) merged.add(create(candidate.serverId, candidate.frame, candidate.queuedAt))
    }
    return merged
}

/**
 * Sends one snapshot of queued frames. A failed write stops this pass so the
 * caller can retain the frame and retry after the next connection-ready event.
 */
internal suspend fun <T> flushOutboxBatch(
    entries: List<T>,
    canContinue: () -> Boolean,
    send: suspend (T) -> Boolean,
    onDelivered: (T) -> Unit,
    onFailure: (T) -> Unit,
) {
    for (entry in entries) {
        if (!canContinue()) return
        if (!send(entry)) {
            onFailure(entry)
            return
        }
        onDelivered(entry)
    }
}
