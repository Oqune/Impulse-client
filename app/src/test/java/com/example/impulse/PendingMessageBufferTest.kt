package com.example.impulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMessageBufferTest {
    private data class Item(val key: String, val queuedAt: Long, val bytes: Long)

    @Test
    fun productionLimitsMatchApprovedBounds() {
        assertEquals(128, PendingMessageBuffer.MAX_ITEMS)
        assertEquals(16, PendingMessageBuffer.MAX_ITEMS_PER_KEY)
        assertEquals(8L * 1024 * 1024, PendingMessageBuffer.MAX_BYTES)
        assertEquals(30_000L, PendingMessageBuffer.TTL_MILLIS)
    }

    @Test
    fun enforcesGlobalPerKeyAndByteCapsWithoutExceedingAccounting() {
        val buffer = buffer(maxItems = 3, maxItemsPerKey = 2, maxBytes = 10)
        assertEquals(PendingMessageBuffer.OfferResult.ACCEPTED, buffer.offer(Item("a", 0, 4), 0))
        assertEquals(PendingMessageBuffer.OfferResult.ACCEPTED, buffer.offer(Item("a", 0, 4), 0))
        assertEquals(PendingMessageBuffer.OfferResult.LIMIT_REACHED, buffer.offer(Item("a", 0, 1), 0))
        assertEquals(PendingMessageBuffer.OfferResult.ACCEPTED, buffer.offer(Item("b", 0, 2), 0))
        assertEquals(PendingMessageBuffer.OfferResult.LIMIT_REACHED, buffer.offer(Item("c", 0, 1), 0))

        val snapshot = buffer.snapshot(0)
        assertEquals(3, snapshot.itemCount)
        assertEquals(10L, snapshot.retainedBytes)
        assertEquals(mapOf("a" to 2, "b" to 1), snapshot.itemsPerKey)
    }

    @Test
    fun expiryDequeueClearAndRequeueKeepByteAccountingConsistent() {
        val expiredIds = mutableListOf<Int>()
        data class Tracked(val id: Int, val key: String, val queuedAt: Long, val bytes: Long)
        val buffer = PendingMessageBuffer<Tracked>(
            maxItems = 4,
            maxItemsPerKey = 3,
            maxBytes = 20,
            ttlMillis = 10,
            keyOf = { it.key },
            queuedAtOf = { it.queuedAt },
            bytesOf = { it.bytes },
            onExpired = { expiredIds.add(it.id) }
        )
        val old = Tracked(1, "peer", 0, 5)
        assertEquals(PendingMessageBuffer.OfferResult.ACCEPTED, buffer.offer(old, 0))
        assertEquals(PendingMessageBuffer.OfferResult.ACCEPTED, buffer.offer(Tracked(2, "peer", 5, 7), 5))

        val dequeued = buffer.take("peer", 10)
        assertEquals(listOf(Tracked(2, "peer", 5, 7)), dequeued)
        assertEquals(listOf(1), expiredIds)
        assertEquals(0L, buffer.snapshot(10).retainedBytes)

        assertEquals(PendingMessageBuffer.OfferResult.ACCEPTED, buffer.offer(dequeued.single(), 10))
        assertEquals(7L, buffer.snapshot(10).retainedBytes)
        buffer.clear()
        assertEquals(0, buffer.snapshot(10).itemCount)
        assertEquals(0L, buffer.snapshot(10).retainedBytes)
    }

    @Test
    fun expiredNewItemAndOversizedItemAreRejected() {
        val buffer = buffer(maxItems = 1, maxItemsPerKey = 1, maxBytes = 5, ttlMillis = 10)
        assertEquals(PendingMessageBuffer.OfferResult.EXPIRED, buffer.offer(Item("a", 0, 1), 10))
        assertEquals(PendingMessageBuffer.OfferResult.LIMIT_REACHED, buffer.offer(Item("a", 10, 6), 10))
        assertTrue(buffer.snapshot(10).itemCount == 0)
    }

    private fun buffer(maxItems: Int, maxItemsPerKey: Int, maxBytes: Long, ttlMillis: Long = 30_000) =
        PendingMessageBuffer<Item>(
            maxItems = maxItems,
            maxItemsPerKey = maxItemsPerKey,
            maxBytes = maxBytes,
            ttlMillis = ttlMillis,
            keyOf = { it.key },
            queuedAtOf = { it.queuedAt },
            bytesOf = { it.bytes }
        )
}
