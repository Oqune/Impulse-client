package com.example.impulse.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedKeyExchangeTest {

    // ── 1. build → parse roundtrip ──────────────────────────────────────────────
    @Test
    fun `combined key exchange roundtrip preserves both keys`() {
        val kemPub = ByteArray(1184) { (it * 37 % 256).toByte() } // ML-KEM-768 pubkey size
        val dsaPub = ByteArray(1952) { (it * 73 % 256).toByte() } // ML-DSA-65 pubkey size

        val frame = Protocol.buildCombinedKeyExchange(kemPub, dsaPub)

        val parsed = Protocol.parseCombinedKeyExchange(Protocol.Reader(frame, 1))
        assertArrayEquals("KEM key must survive roundtrip", kemPub, parsed.kemPublicKey)
        assertArrayEquals("DSA key must survive roundtrip", dsaPub, parsed.dsaPublicKey)
    }

    // ── 2. Wire format: exact byte layout ───────────────────────────────────────
    @Test
    fun `wire format matches expected layout`() {
        val kemPub = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val dsaPub = byteArrayOf(0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte())

        val frame = Protocol.buildCombinedKeyExchange(kemPub, dsaPub)

        // Byte 0: opcode
        assertEquals(Protocol.OP_KEY_EXCHANGE_KEM_DSA, frame[0])

        // Bytes 1-4: outer u32 total length = (4+3) + (4+4) = 15
        val outerLen = frame[1].toInt() and 0xFF or
            ((frame[2].toInt() and 0xFF) shl 8) or
            ((frame[3].toInt() and 0xFF) shl 16) or
            ((frame[4].toInt() and 0xFF) shl 24)
        assertEquals(15, outerLen)

        // Bytes 5-8: inner u32 kem_len = 3
        val kemLen = frame[5].toInt() and 0xFF or
            ((frame[6].toInt() and 0xFF) shl 8) or
            ((frame[7].toInt() and 0xFF) shl 16) or
            ((frame[8].toInt() and 0xFF) shl 24)
        assertEquals(3, kemLen)

        // Bytes 9-11: kem bytes
        assertEquals(0xAA.toByte(), frame[9])
        assertEquals(0xBB.toByte(), frame[10])
        assertEquals(0xCC.toByte(), frame[11])

        // Bytes 12-15: inner u32 dsa_len = 4
        val dsaLen = frame[12].toInt() and 0xFF or
            ((frame[13].toInt() and 0xFF) shl 8) or
            ((frame[14].toInt() and 0xFF) shl 16) or
            ((frame[15].toInt() and 0xFF) shl 24)
        assertEquals(4, dsaLen)

        // Bytes 16-19: dsa bytes
        assertEquals(0x11.toByte(), frame[16])
        assertEquals(0x22.toByte(), frame[17])
        assertEquals(0x33.toByte(), frame[18])
        assertEquals(0x44.toByte(), frame[19])

        // Total: 1 + 4 + 15 = 20
        assertEquals(20, frame.size)
    }

    // ── 3. frameLength matches actual size ──────────────────────────────────────
    @Test
    fun `frameLength equals frame size for various key sizes`() {
        val testCases = listOf(
            ByteArray(0) to ByteArray(0),
            ByteArray(1) { it.toByte() } to ByteArray(2) { (it + 10).toByte() },
            ByteArray(1184) { (it % 256).toByte() } to ByteArray(1952) { (it % 128).toByte() },
            ByteArray(32) { 0xFF.toByte() } to ByteArray(64) { 0xAA.toByte() }
        )

        for ((kem, dsa) in testCases) {
            val frame = Protocol.buildCombinedKeyExchange(kem, dsa)
            val computed = Protocol.frameLength(frame)
            assertEquals(
                "frameLength must equal actual size (kem=${kem.size}, dsa=${dsa.size})",
                frame.size, computed
            )
        }
    }

    // ── 4. Server relay simulation ──────────────────────────────────────────────
    @Test
    fun `server relay re-wraps correctly and client can parse`() {
        val kemPub = ByteArray(1184) { (it * 13 % 256).toByte() }
        val dsaPub = ByteArray(1952) { (it * 29 % 256).toByte() }

        // Client builds the original frame: [0x0C][u32:total_len][inner blob]
        val frame = Protocol.buildCombinedKeyExchange(kemPub, dsaPub)

        // Server reads opcode, then remaining() = everything after opcode byte.
        // Server relay: prepend opcode byte to remaining → same format as original.
        val serverPayload = frame.copyOfRange(1, frame.size) // strip opcode byte
        val relayed = byteArrayOf(frame[0]) + serverPayload // [0x0C] + remaining

        // Receiving client parses the relayed frame
        val parsed = Protocol.parseCombinedKeyExchange(Protocol.Reader(relayed, 1))
        assertArrayEquals("Relayed KEM key must match", kemPub, parsed.kemPublicKey)
        assertArrayEquals("Relayed DSA key must match", dsaPub, parsed.dsaPublicKey)
    }

    // ── 5. Server try_read_packet: outer u32 covers full inner blob ─────────────
    @Test
    fun `outer u32 length covers both inner keys`() {
        val kemPub = ByteArray(100) { it.toByte() }
        val dsaPub = ByteArray(200) { (it * 3).toByte() }

        val frame = Protocol.buildCombinedKeyExchange(kemPub, dsaPub)

        // Outer u32 (bytes 1-4) must equal total inner bytes: (4+100) + (4+200) = 308
        val outerLen = frame[1].toInt() and 0xFF or
            ((frame[2].toInt() and 0xFF) shl 8) or
            ((frame[3].toInt() and 0xFF) shl 16) or
            ((frame[4].toInt() and 0xFF) shl 24)
        assertEquals(4 + kemPub.size + 4 + dsaPub.size, outerLen)

        // Total frame = 1 (opcode) + 4 (outer len) + outerLen
        assertEquals(1 + 4 + outerLen, frame.size)
    }

    // ── 6. Two frames with different keys produce different wire bytes ──────────
    @Test
    fun `different key pairs produce different frames`() {
        val kemPub1 = ByteArray(32) { it.toByte() }
        val dsaPub1 = ByteArray(32) { (it + 10).toByte() }
        val kemPub2 = ByteArray(32) { (it + 50).toByte() }
        val dsaPub2 = ByteArray(32) { (it + 100).toByte() }

        val frame1 = Protocol.buildCombinedKeyExchange(kemPub1, dsaPub1)
        val frame2 = Protocol.buildCombinedKeyExchange(kemPub2, dsaPub2)

        assertTrue("Different keys must produce different frames", !frame1.contentEquals(frame2))
    }

    // ── 7. Empty keys (edge case) ──────────────────────────────────────────────
    @Test
    fun `empty keys produce valid frame`() {
        val frame = Protocol.buildCombinedKeyExchange(ByteArray(0), ByteArray(0))
        assertEquals(Protocol.OP_KEY_EXCHANGE_KEM_DSA, frame[0])

        val parsed = Protocol.parseCombinedKeyExchange(Protocol.Reader(frame, 1))
        assertEquals(0, parsed.kemPublicKey.size)
        assertEquals(0, parsed.dsaPublicKey.size)
    }

    // ── 8. Large keys (edge case — real ML-KEM + ML-DSA sizes) ─────────────────
    @Test
    fun `realistic ML-KEM-768 and ML-DSA-65 key sizes roundtrip`() {
        val kemPub = ByteArray(1184) { (it * 7 % 256).toByte() }
        val dsaPub = ByteArray(1952) { (it * 11 % 256).toByte() }

        val frame = Protocol.buildCombinedKeyExchange(kemPub, dsaPub)
        val parsed = Protocol.parseCombinedKeyExchange(Protocol.Reader(frame, 1))

        assertArrayEquals(kemPub, parsed.kemPublicKey)
        assertArrayEquals(dsaPub, parsed.dsaPublicKey)

        // Verify total frame size: 1 + 4 + (4+1184) + (4+1952) = 1 + 4 + 1188 + 1956 = 3149
        assertEquals(3149, frame.size)
    }

    // ── 9. Simulate the FULL flow: client build, server parse, relay, client parse ──
    @Test
    fun full_flow_build_relay_parse() {
        val kemPub = ByteArray(1184) { (it * 17 % 256).toByte() }
        val dsaPub = ByteArray(1952) { (it * 31 % 256).toByte() }

        // Step 1: Client builds frame
        val clientFrame = Protocol.buildCombinedKeyExchange(kemPub, dsaPub)

        // Step 2: Server reads opcode, remaining() = everything after opcode
        val serverReader = Protocol.Reader(clientFrame, 1) // skip opcode
        val remaining = serverReader.readBytes(serverReader.remaining())

        // Step 3: Server relay — prepend opcode byte to remaining (no extra wrapping)
        val relayedFrame = byteArrayOf(clientFrame[0]) + remaining

        // Step 4: Receiving client parses the relayed frame
        val parsed = Protocol.parseCombinedKeyExchange(Protocol.Reader(relayedFrame, 1))

        assertArrayEquals(kemPub, parsed.kemPublicKey)
        assertArrayEquals(dsaPub, parsed.dsaPublicKey)
    }

    // ── 10. Server try_read_packet would read full packet (simulate length check) ──
    @Test
    fun `server try_read_packet would return correct total length`() {
        val kemPub = ByteArray(1184) { (it * 5 % 256).toByte() }
        val dsaPub = ByteArray(1952) { (it * 9 % 256).toByte() }

        val frame = Protocol.buildCombinedKeyExchange(kemPub, dsaPub)

        // Simulate what try_read_packet does:
        // It reads u32 at bytes[1..5] as payload_len
        val payloadLen = frame[1].toInt() and 0xFF or
            ((frame[2].toInt() and 0xFF) shl 8) or
            ((frame[3].toInt() and 0xFF) shl 16) or
            ((frame[4].toInt() and 0xFF) shl 24)
        val totalPacketLen = 1 + 4 + payloadLen

        assertEquals("try_read_packet must return exact frame size", frame.size, totalPacketLen)
    }

    // ── 11. Orphan bytes test — what was happening BEFORE the fix ───────────────
    @Test
    fun `old broken format would have orphaned DSA bytes`() {
        // This simulates the OLD broken format: [0x0C][u32:kem_len][kem][u32:dsa_len][dsa]
        // without an outer wrapper. try_read_packet would read kem_len as total, orphaning DSA.
        val kemPub = ByteArray(100) { it.toByte() }
        val dsaPub = ByteArray(200) { (it * 2).toByte() }

        val brokenFrame = Protocol.Writer().apply {
            u8(Protocol.OP_KEY_EXCHANGE_KEM_DSA.toInt())
            bytes(kemPub)   // [u32:100][kem bytes]
            bytes(dsaPub)   // [u32:200][dsa bytes] — orphaned by try_read_packet!
        }.toByteArray()

        // try_read_packet reads u32 at offset 1 → gets 100 (kem_len)
        val brokenPayloadLen = brokenFrame[1].toInt() and 0xFF or
            ((brokenFrame[2].toInt() and 0xFF) shl 8) or
            ((brokenFrame[3].toInt() and 0xFF) shl 16) or
            ((brokenFrame[4].toInt() and 0xFF) shl 24)
        val brokenTotalLen = 1 + 4 + brokenPayloadLen

        // The actual frame is bigger — DSA bytes are orphaned!
        assertTrue(
            "Old format would have orphaned bytes (frame=${brokenFrame.size}, try_read=$brokenTotalLen)",
            brokenTotalLen < brokenFrame.size
        )

        // Now with the fixed format, everything fits
        val fixedFrame = Protocol.buildCombinedKeyExchange(kemPub, dsaPub)
        val fixedPayloadLen = fixedFrame[1].toInt() and 0xFF or
            ((fixedFrame[2].toInt() and 0xFF) shl 8) or
            ((fixedFrame[3].toInt() and 0xFF) shl 16) or
            ((fixedFrame[4].toInt() and 0xFF) shl 24)
        val fixedTotalLen = 1 + 4 + fixedPayloadLen

        assertEquals("Fixed format: try_read_packet covers full frame", fixedFrame.size, fixedTotalLen)
    }
}
