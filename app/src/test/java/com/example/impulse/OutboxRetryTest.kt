package com.example.impulse

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxRetryTest {

    private data class StoredFrame(
        override val serverId: String,
        override val frame: ByteArray,
        override val queuedAt: Long
    ) : PersistedOutboxFrame

    @Test
    fun restoredFramesRemainPersistedAndRepeatedRestoreDoesNotDuplicate() {
        val persisted = listOf(StoredFrame("server-a", byteArrayOf(1, 2, 3), 10L))
        val inMemory = mutableListOf<StoredFrame>()

        inMemory.replaceWith(mergeRestoredOutbox(inMemory, persisted) { s, f, t -> StoredFrame(s, f, t) })
        // A second READY/reconnect restore sees the same persisted preference.
        inMemory.replaceWith(mergeRestoredOutbox(inMemory, persisted) { s, f, t -> StoredFrame(s, f, t) })

        assertEquals(1, inMemory.size)
        assertEquals(1, persisted.size) // restoration itself never consumes durable state
        assertTrue(inMemory.single().frame.contentEquals(persisted.single().frame))
    }

    @Test
    fun restoreMergeKeepsOtherServersAndDeduplicatesByFrameBytes() {
        val existing = listOf(StoredFrame("server-b", byteArrayOf(9), 20L))
        val restored = listOf(
            StoredFrame("server-a", byteArrayOf(1, 2), 10L),
            StoredFrame("server-b", byteArrayOf(9), 20L),
        )

        val merged = mergeRestoredOutbox(existing, restored) { s, f, t -> StoredFrame(s, f, t) }

        assertEquals(listOf("server-b", "server-a"), merged.map { it.serverId })
        assertEquals(2, merged.size)
    }

    @Test
    fun repeatedSendFailuresRetainFrameUntilLaterSuccessfulPass() = runBlocking {
        val frame = byteArrayOf(0x32, 1, 2, 3)
        val queue = mutableListOf(frame)
        var persistedQueue = queue.toList()
        var failureCount = 0

        repeat(4) {
            flushOutboxBatch(
                entries = queue.toList(),
                canContinue = { true },
                send = { false },
                onDelivered = { delivered ->
                    queue.remove(delivered)
                    persistedQueue = queue.toList()
                },
                onFailure = {
                    failureCount++
                    persistedQueue = queue.toList()
                },
            )
        }

        assertEquals(4, failureCount)
        assertEquals(1, queue.size)
        assertEquals(1, persistedQueue.size)
        assertTrue(queue.single().contentEquals(frame))

        flushOutboxBatch(
            entries = queue.toList(),
            canContinue = { true },
            send = { true },
            onDelivered = { delivered ->
                queue.remove(delivered)
                persistedQueue = queue.toList()
            },
            onFailure = { persistedQueue = queue.toList() },
        )

        assertTrue(queue.isEmpty())
        assertTrue(persistedQueue.isEmpty())
    }

    @Test
    fun disconnectedPassDoesNotAttemptOrRemoveFrames() = runBlocking {
        val queue = mutableListOf("encrypted-frame")
        var sendAttempts = 0

        flushOutboxBatch(
            entries = queue.toList(),
            canContinue = { false },
            send = {
                sendAttempts++
                true
            },
            onDelivered = { queue.remove(it) },
            onFailure = {},
        )

        assertEquals(0, sendAttempts)
        assertEquals(listOf("encrypted-frame"), queue)
    }

    private fun <T> MutableList<T>.replaceWith(values: List<T>) {
        clear()
        addAll(values)
    }
}
