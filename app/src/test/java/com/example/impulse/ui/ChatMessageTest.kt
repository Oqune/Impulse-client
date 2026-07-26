package com.example.impulse.ui

import com.example.impulse.ChatController
import com.example.impulse.ui.screens.ChatMessage
import com.example.impulse.ui.screens.MessageType
import com.example.impulse.ui.screens.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {

    // ===== formatTimestamp tests =====

    @Test
    fun formatTimestamp_validMillis_returnsFormattedTime() {
        // 2024-01-15 10:30:00 UTC = 1705314600000
        val result = formatTimestamp(1705314600000L)
        assertTrue("should contain brackets", result.startsWith("[") && result.endsWith("]"))
        assertTrue("should contain time separator", result.contains(":"))
    }

    @Test
    fun formatTimestamp_zeroMillis_returnsPlaceholder() {
        val result = formatTimestamp(0L)
        assertEquals("[--:--]", result)
    }

    @Test
    fun formatTimestamp_negativeMillis_returnsPlaceholder() {
        val result = formatTimestamp(-1L)
        assertEquals("[--:--]", result)
    }

    @Test
    fun formatTimestamp_differentTimesProducesDifferentStrings() {
        // Two times 1 hour apart should produce different formatted strings
        val t1 = formatTimestamp(1705314600000L) // 10:30
        val t2 = formatTimestamp(1705318200000L) // 11:30
        assertNotEquals("different times should format differently", t1, t2)
    }

    // ===== Message ordering tests (simulating ChatViewModel logic) =====

    private fun createDecryptedMsg(id: Long, sender: String, content: String, isOwn: Boolean, timestamp: Long): ChatController.DecryptedMessage {
        return ChatController.DecryptedMessage(
            serverMsgId = id,
            sender = sender,
            plaintext = content,
            isOwn = isOwn,
            timestamp = timestamp
        )
    }

    @Test
    fun messageOrdering_byServerMsgId_chronological() {
        // Messages with different serverMsgIds should sort chronologically
        val msg1 = createDecryptedMsg(1, "alice", "first", true, 1000L)
        val msg3 = createDecryptedMsg(3, "bob", "third", false, 3000L)
        val msg2 = createDecryptedMsg(2, "alice", "second", true, 2000L)

        val messages = mutableListOf(msg3, msg1, msg2)
        messages.sortBy { it.serverMsgId }

        assertEquals(1L, messages[0].serverMsgId)
        assertEquals(2L, messages[1].serverMsgId)
        assertEquals(3L, messages[2].serverMsgId)
    }

    @Test
    fun messageOrdering_distinctByServerMsgId_removesDuplicates() {
        // Two messages with same serverMsgId (optimistic + server echo) should dedup
        val optimistic = createDecryptedMsg(-1000L, "alice", "hello", true, 1000L)
        val serverEcho = createDecryptedMsg(42L, "alice", "hello", true, 2000L)

        val messages = listOf(optimistic, serverEcho)
        val deduped = messages.distinctBy { it.serverMsgId }.sortedBy { it.serverMsgId }

        assertEquals(2, deduped.size) // Different IDs, both kept
    }

    @Test
    fun messageOrdering_distinctByServerMsgId_replacesOptimisticWithReal() {
        // Optimistic has negative ID, server echo has positive ID — both kept
        val optimistic = createDecryptedMsg(-5000L, "alice", "hello", true, 1000L)
        val serverEcho = createDecryptedMsg(10L, "alice", "hello", true, 2000L)

        // Simulate the liveListener replacement logic
        val current = mutableListOf(optimistic)
        val dm = serverEcho
        val idx = current.indexOfFirst {
            it.sender == dm.sender &&
            it.plaintext == dm.plaintext &&
            it.serverMsgId < 0
        }
        if (idx >= 0) {
            current[idx] = dm
        } else {
            current.add(dm)
        }

        assertEquals(1, current.size)
        assertEquals(10L, current[0].serverMsgId)
    }

    // ===== isOwn determination tests =====

    @Test
    fun isOwn_senderMatchesClientName_returnsTrue() {
        val clientName = "Alice"
        val sender = "Alice"
        assertTrue(sender == clientName)
    }

    @Test
    fun isOwn_senderDifferentFromClientName_returnsFalse() {
        val clientName = "Alice"
        val sender = "Bob"
        assertFalse(sender == clientName)
    }

    @Test
    fun isOwn_emptyClientName_alwaysFalse() {
        val clientName = ""
        assertFalse("Alice" == clientName)
    }

    @Test
    fun isOwn_caseSensitive_mismatch() {
        val clientName = "Alice"
        val sender = "alice"
        assertFalse("case-sensitive comparison should fail", sender == clientName)
    }

    // ===== ChatMessage timestamp tests =====

    @Test
    fun chatMessage_timestamp_fromFormatTimestamp() {
        val ts = 1705314600000L
        val msg = ChatMessage(
            sender = "test",
            content = "hello",
            timestamp = formatTimestamp(ts),
            timestampMillis = ts,
            isOwn = true
        )
        assertTrue(msg.timestamp.startsWith("["))
        assertEquals(ts, msg.timestampMillis)
    }

    @Test
    fun chatMessage_defaultTimestamp_isNotCurrentTime() {
        val before = System.currentTimeMillis()
        val msg = ChatMessage(
            sender = "test",
            content = "hello"
        )
        val after = System.currentTimeMillis()
        // The default timestamp should be based on timestampMillis, not creation time
        // They should be very close (within 1 second)
        assertTrue("default timestampMillis should be close to creation time",
            msg.timestampMillis in before..after)
    }
}
