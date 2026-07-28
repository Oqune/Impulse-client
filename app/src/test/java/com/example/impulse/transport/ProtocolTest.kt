package com.example.impulse.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {

    @Test
    fun data_outbound_hasNoServerIdPrefix() {
        val payload = "secret message".toByteArray(Charsets.UTF_8)
        val frame = Protocol.buildData(payload)
        assertEquals(Protocol.OP_DATA, frame[0])
        val w = Protocol.Writer()
        w.u8(Protocol.OP_DATA.toInt())
        w.u64(0L)
        w.u64(0L)
        w.bytes(payload)
        val relayFrame = w.toByteArray()
        val parsed = Protocol.parseData(Protocol.Reader(relayFrame, 1))
        assertEquals(0L, parsed.serverMsgId)
        assertEquals(0L, parsed.timestamp)
        assertTrue(parsed.payload.contentEquals(payload))
    }

    @Test
    fun data_parse_withServerAssignedId() {
        val payload = "relayed".toByteArray(Charsets.UTF_8)
        val w = Protocol.Writer()
        w.u8(Protocol.OP_DATA.toInt())
        w.u64(42L)
        w.u64(1_700_000_000_000L)
        w.bytes(payload)
        val frame = w.toByteArray()

        val parsed = Protocol.parseData(Protocol.Reader(frame, 1))
        assertEquals(42L, parsed.serverMsgId)
        assertEquals(1_700_000_000_000L, parsed.timestamp)
        assertTrue(parsed.payload.contentEquals(payload))
    }

    @Test
    fun keyExchange_roundTrip_withOpcode() {
        val pub = ByteArray(1184) { it.toByte() }
        val frame = Protocol.buildKeyExchange(pub, Protocol.OP_KEY_EXCHANGE_KEM)
        assertEquals(Protocol.OP_KEY_EXCHANGE_KEM, frame[0])
        val parsed = Protocol.parseKeyExchange(Protocol.Reader(frame, 1))
        assertTrue(parsed.publicKey.contentEquals(pub))
    }

    @Test
    fun auth_sendsRawPassword() {
        val pw = "yourpassword"
        val frame = Protocol.buildAuth(pw)
        assertEquals(Protocol.OP_AUTH, frame[0])
        // Wire: [0x01] [u32 len][raw_password_bytes]
        val reader = Protocol.Reader(frame, 1)
        val rawPassword = reader.bytes()
        assertEquals(pw, String(rawPassword, Charsets.UTF_8))
    }

    @Test
    fun auth_hmacChallengeResponse_verifiableByServer() {
        val password = "s3cret_p@ss!"
        val nonce = ByteArray(16) { it.toByte() }

        // Client builds auth with HMAC response (Argon2 key).
        val frame = Protocol.buildAuth(password, nonce)
        assertEquals(Protocol.OP_AUTH, frame[0])

        // Extract the raw password and HMAC response from the frame.
        // Wire: [0x01] [u32 len][raw_password_bytes] [32 raw bytes: hmac]
        val reader = Protocol.Reader(frame, 1)
        val rawPassword = reader.bytes()
        assertEquals(password, String(rawPassword, Charsets.UTF_8))

        val hmacResponse = reader.readBytes(32)
        assertEquals(32, hmacResponse.size)

        // Server-side: recompute HMAC using Argon2-derived key and nonce.
        val key = Protocol.argon2DeriveKey(password)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretSpec)
        mac.update(nonce)
        val expected = mac.doFinal()

        assertTrue("HMAC response should match server computation", hmacResponse.contentEquals(expected))
    }

    @Test
    fun auth_hmac_wrongNonce_failsVerification() {
        val password = "test123"
        val nonce = ByteArray(16) { (it + 1).toByte() }
        val wrongNonce = ByteArray(16) { (it + 100).toByte() }

        val frame = Protocol.buildAuth(password, nonce)
        val reader = Protocol.Reader(frame, 1)
        reader.bytes() // skip raw password
        val hmacResponse = reader.readBytes(32)

        // Verify with wrong nonce — should fail.
        val key = Protocol.argon2DeriveKey(password)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretSpec)
        mac.update(wrongNonce)
        val expected = mac.doFinal()

        assertTrue("Wrong nonce should not match", !hmacResponse.contentEquals(expected))
    }

    @Test
    fun auth_hmac_wrongPassword_failsVerification() {
        val nonce = ByteArray(16) { 0x42 }
        val frame = Protocol.buildAuth("password_A", nonce)

        val reader = Protocol.Reader(frame, 1)
        reader.bytes() // skip raw password
        val hmacResponse = reader.readBytes(32)

        // Derive key from the WRONG password and verify — should fail.
        val key = Protocol.argon2DeriveKey("password_B")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretSpec)
        mac.update(nonce)
        val expected = mac.doFinal()

        assertFalse("HMAC from password_A should not verify with password_B key",
            hmacResponse.contentEquals(expected))
    }

    @Test
    fun auth_packet_wire_format_matches_server() {
        val password = "test" // 4 bytes UTF-8
        val nonce = ByteArray(16) { 0x42 }
        val frame = Protocol.buildAuth(password, nonce)

        // Manually compute the expected HMAC.
        val key = Protocol.argon2DeriveKey(password)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretSpec)
        val expectedHmac = mac.doFinal(nonce)

        // Build expected wire format.
        val expected = byteArrayOf(
            0x01,                                           // OP_AUTH
            0x04, 0x00, 0x00, 0x00,                         // u32 LE password length = 4
            0x74, 0x65, 0x73, 0x74                          // "test" UTF-8
        ) + expectedHmac                                     // 32 bytes HMAC

        assertArrayEquals("Wire format must match server spec", expected, frame)
        assertEquals("Total length = 1 + 4 + 4 + 32 = 41", 41, frame.size)
    }

    @Test
    fun auth_emptyPassword() {
        val nonce = ByteArray(16) { 0x01 }
        val frame = Protocol.buildAuth("", nonce)

        assertEquals(Protocol.OP_AUTH, frame[0])
        val reader = Protocol.Reader(frame, 1)
        val rawPassword = reader.bytes()
        assertEquals(0, rawPassword.size)
        assertEquals("", String(rawPassword, Charsets.UTF_8))

        val hmacResponse = reader.readBytes(32)
        assertEquals(32, hmacResponse.size)

        // Verify HMAC is correct for empty password.
        val key = Protocol.argon2DeriveKey("")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretSpec)
        val expected = mac.doFinal(nonce)
        assertTrue("HMAC should match for empty password", hmacResponse.contentEquals(expected))
    }

    @Test
    fun auth_unicodePassword() {
        val password = "пароль" // 6 Cyrillic chars × 2 bytes UTF-8 = 12 bytes
        val nonce = ByteArray(16) { 0xAA }
        val frame = Protocol.buildAuth(password, nonce)

        val reader = Protocol.Reader(frame, 1)
        val rawPassword = reader.bytes()
        assertEquals(12, rawPassword.size)
        assertEquals(password, String(rawPassword, Charsets.UTF_8))

        // Verify the u32 length prefix encodes 12.
        val pwdLen = (frame[1].toInt() and 0xFF) or
            ((frame[2].toInt() and 0xFF) shl 8) or
            ((frame[3].toInt() and 0xFF) shl 16) or
            ((frame[4].toInt() and 0xFF) shl 24)
        assertEquals(12, pwdLen)
    }

    @Test
    fun auth_frameLength_matchesActualSize() {
        val nonce = ByteArray(16) { 0x55 }
        val passwords = listOf("", "a", "abcdefghij", "a".repeat(100), "a".repeat(1000))

        for (pw in passwords) {
            val frame = Protocol.buildAuth(pw, nonce)
            val computed = Protocol.frameLength(frame)
            assertEquals("frameLength must equal actual size for password length ${pw.length}",
                frame.size, computed)
        }
    }

    @Test
    fun syncPacket_roundtrip() {
        val frame = Protocol.buildSync(42)
        assertEquals(1 + 8, frame.size) // opcode + u64
        assertEquals(Protocol.OP_SYNC, frame[0])
        assertEquals(9, Protocol.frameLength(frame))

        val reader = Protocol.Reader(frame, 1)
        val lastSeenId = reader.u64()
        assertEquals(42L, lastSeenId)
    }

    @Test
    fun heartbeatPacket_roundtrip() {
        val before = System.currentTimeMillis()
        val frame = Protocol.buildHeartbeat()
        val after = System.currentTimeMillis()

        assertEquals(1 + 8, frame.size) // opcode + u64 timestamp
        assertEquals(Protocol.OP_HEARTBEAT, frame[0])
        assertEquals(9, Protocol.frameLength(frame))

        val reader = Protocol.Reader(frame, 1)
        val ts = reader.u64()
        assertTrue("Timestamp should be >= before", ts >= before)
        assertTrue("Timestamp should be <= after", ts <= after)
    }

    @Test
    fun keyExchangePacket_roundtrip() {
        val pubKey = ByteArray(64) { (it * 3).toByte() }
        val frame = Protocol.buildKeyExchange(pubKey, Protocol.OP_KEY_EXCHANGE)
        assertEquals(Protocol.OP_KEY_EXCHANGE, frame[0])
        assertEquals(Protocol.frameLength(frame), frame.size)

        val parsed = Protocol.parseKeyExchange(Protocol.Reader(frame, 1))
        assertArrayEquals(pubKey, parsed.publicKey)
    }

    @Test
    fun frameLength_unknownOpcode_throws() {
        val badOpcodes = byteArrayOf(0x00, 0x0C, 0xFF.toByte())
        for (op in badOpcodes) {
            val data = byteArrayOf(op, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            assertThrows(Protocol.ProtocolException::class.java) {
                Protocol.frameLength(data)
            }
        }
    }

    @Test
    fun frameLength_truncatedData_throws() {
        // OP_SYNC needs 9 bytes but only 3 are available.
        val truncated = byteArrayOf(Protocol.OP_SYNC, 0x01, 0x02)
        assertThrows(Protocol.ProtocolException::class.java) {
            Protocol.frameLength(truncated)
        }
    }

    @Test
    fun parseAuthChallenge_wrongSize_throws() {
        // parseAuthChallenge expects exactly 16 bytes, give it 15.
        val tooFew = ByteArray(15) { it.toByte() }
        val reader = Protocol.Reader(tooFew)
        assertThrows(Protocol.ProtocolException::class.java) {
            Protocol.parseAuthChallenge(reader)
        }
    }

    @Test
    fun multiClient_authSequence() {
        data class Client(val password: String, val nonce: ByteArray)

        val clients = listOf(
            Client("alice_secret", ByteArray(16) { it.toByte() }),
            Client("bob_pass_2024", ByteArray(16) { (it + 0x10).toByte() }),
            Client("charlie!", ByteArray(16) { (it + 0x20).toByte() })
        )

        val frames = clients.map { (pw, nonce) -> pw to Protocol.buildAuth(pw, nonce) }

        for ((idx, (pw, frame)) in frames.withIndex()) {
            assertEquals("Client $idx: opcode", Protocol.OP_AUTH, frame[0])
            assertEquals("Client $idx: frameLength", Protocol.frameLength(frame), frame.size)

            val reader = Protocol.Reader(frame, 1)
            val rawPassword = reader.bytes()
            assertEquals("Client $idx: password roundtrip", pw, String(rawPassword, Charsets.UTF_8))
        }

        // Verify each client's HMAC independently with its own Argon2 key.
        for ((idx, (pw, nonce)) in clients.withIndex()) {
            val frame = frames[idx].second
            val reader = Protocol.Reader(frame, 1)
            reader.bytes()
            val hmacResponse = reader.readBytes(32)

            val key = Protocol.argon2DeriveKey(pw)
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            val secretSpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
            mac.init(secretSpec)
            mac.update(nonce)
            val expected = mac.doFinal()

            assertTrue("Client $idx: HMAC must match its own key+nonce",
                hmacResponse.contentEquals(expected))
        }

        // Cross-verify: client 0's HMAC must NOT verify with client 1's key.
        val frame0 = frames[0].second
        val reader0 = Protocol.Reader(frame0, 1)
        reader0.bytes()
        val hmac0 = reader0.readBytes(32)

        val key1 = Protocol.argon2DeriveKey(clients[1].password)
        val mac1 = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec1 = javax.crypto.spec.SecretKeySpec(key1, "HmacSHA256")
        mac1.init(secretSpec1)
        mac1.update(clients[0].nonce)
        val wrongExpected = mac1.doFinal()

        assertFalse("Client 0 HMAC must not verify with client 1 key",
            hmac0.contentEquals(wrongExpected))
    }
}
