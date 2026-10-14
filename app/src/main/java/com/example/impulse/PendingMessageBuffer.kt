package com.example.impulse

/** Bounded in-memory holding area for frames awaiting an unknown sender key. */
internal class PendingMessageBuffer<T>(
    private val maxItems: Int,
    private val maxItemsPerKey: Int,
    private val maxBytes: Long,
    private val ttlMillis: Long,
    private val keyOf: (T) -> String,
    private val queuedAtOf: (T) -> Long,
    private val bytesOf: (T) -> Long,
    private val onExpired: (T) -> Unit = {}
) {
    companion object {
        const val MAX_ITEMS = 128
        const val MAX_ITEMS_PER_KEY = 16
        const val MAX_BYTES = 8L * 1024L * 1024L
        const val TTL_MILLIS = 30_000L
    }

    internal enum class OfferResult { ACCEPTED, EXPIRED, LIMIT_REACHED }

    private val itemsByKey = mutableMapOf<String, MutableList<T>>()
    private var itemCount = 0
    private var retainedBytes = 0L

    @Synchronized
    fun offer(item: T, now: Long = System.currentTimeMillis()): OfferResult {
        purgeExpired(now)
        if (isExpired(item, now)) return OfferResult.EXPIRED
        val key = keyOf(item)
        val size = bytesOf(item).coerceAtLeast(0L)
        if (itemCount >= maxItems || itemsByKey[key].orEmpty().size >= maxItemsPerKey ||
            size > maxBytes || retainedBytes > maxBytes - size) {
            return OfferResult.LIMIT_REACHED
        }
        itemsByKey.getOrPut(key) { mutableListOf() }.add(item)
        itemCount++
        retainedBytes += size
        return OfferResult.ACCEPTED
    }

    @Synchronized
    fun take(key: String, now: Long = System.currentTimeMillis()): List<T> {
        purgeExpired(now)
        val items = itemsByKey.remove(key).orEmpty()
        for (item in items) {
            itemCount--
            retainedBytes -= bytesOf(item).coerceAtLeast(0L)
        }
        return items
    }

    @Synchronized
    fun clear() {
        itemsByKey.clear()
        itemCount = 0
        retainedBytes = 0L
    }

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): Snapshot {
        purgeExpired(now)
        return Snapshot(itemCount, retainedBytes, itemsByKey.mapValues { it.value.size })
    }

    private fun purgeExpired(now: Long) {
        val iterator = itemsByKey.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val expired = entry.value.filter { isExpired(it, now) }
            for (item in expired) {
                entry.value.remove(item)
                itemCount--
                retainedBytes -= bytesOf(item).coerceAtLeast(0L)
                onExpired(item)
            }
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    private fun isExpired(item: T, now: Long): Boolean {
        val queuedAt = queuedAtOf(item)
        return queuedAt <= now && now - queuedAt >= ttlMillis
    }

    internal data class Snapshot(val itemCount: Int, val retainedBytes: Long, val itemsPerKey: Map<String, Int>)
}
