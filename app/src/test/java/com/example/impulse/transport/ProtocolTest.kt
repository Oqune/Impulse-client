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

    // ── C3 (HMAC-only auth): the raw password MUST NOT travel on the wire ──────

    @Test
    fun auth_sendsNoRawPassword() {
        val pw = "yourpassword"
        val nonce = ByteArray(16) { 0x42 }
        val frame = Protocol.buildAuth(pw, nonce)
        assertEquals(Protocol.OP_AUTH, frame[0])
        // Wire: [0x12] [u32 hmac_len=32] [32 hmac] — exactly 37 bytes, no password.
        assertEquals(37, frame.size)
        // The raw password must NOT appear anywhere on the wire.
        val pwBytes = pw.toByteArray(Charsets.UTF_8)
        var found = false
        for (i in 0..frame.size - pwBytes.size) {
            if (frame.copyOfRange(i, i + pwBytes.size).contentEquals(pwBytes)) {
                found = true
                break
            }
        }
        assertFalse("Raw password must not travel on the wire (C3)", found)
    }

    @Test
    fun auth_hmacChallengeResponse_verifiableByClientKey() {
        val password = "s3cret_p@ss!"
        val nonce = ByteArray(16) { it.toByte() }

        val frame = Protocol.buildAuth(password, nonce)
        assertEquals(Protocol.OP_AUTH, frame[0])

        // Extract the HMAC response (length-prefixed 32 bytes).
        val reader = Protocol.Reader(frame, 1)
        val hmacResponse = reader.bytes()
        assertEquals(32, hmacResponse.size)

        // Recompute HMAC using the same Argon2-derived key the client used.
        val key = Protocol.argon2DeriveKey(password)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretSpec)
        mac.update(nonce)
        val expected = mac.doFinal()

        assertTrue("HMAC response should match client-key computation", hmacResponse.contentEquals(expected))
    }

    @Test
    fun auth_hmac_wrongNonce_failsVerification() {
        val password = "test123"
        val nonce = ByteArray(16) { (it + 1).toByte() }
        val wrongNonce = ByteArray(16) { (it + 100).toByte() }

        val frame = Protocol.buildAuth(password, nonce)
        val reader = Protocol.Reader(frame, 1)
        val hmacResponse = reader.bytes()

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
        val hmacResponse = reader.bytes()

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

        // Build expected wire format: [0x12][u32 hmac_len=32][32 hmac].
        val expected = byteArrayOf(
            Protocol.OP_AUTH,                               // OP_AUTH (0x12)
            0x20, 0x00, 0x00, 0x00                          // u32 LE hmac length = 32
        ) + expectedHmac                                     // 32 bytes HMAC

        assertArrayEquals("Wire format must match server spec (C3 HMAC-only)", expected, frame)
        assertEquals("Total length = 1 + 4 + 32 = 37", 37, frame.size)
    }

    @Test
    fun auth_emptyPassword() {
        val nonce = ByteArray(16) { 0x01 }
        val frame = Protocol.buildAuth("", nonce)

        assertEquals(Protocol.OP_AUTH, frame[0])
        assertEquals(37, frame.size)
        val reader = Protocol.Reader(frame, 1)
        val hmacResponse = reader.bytes()
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
        val nonce = ByteArray(16) { 0xAA.toByte() }
        val frame = Protocol.buildAuth(password, nonce)

        // C3: no password field — frame is always 37 bytes regardless of password.
        assertEquals(37, frame.size)

        // And the HMAC is still verifiable for the unicode password.
        val reader = Protocol.Reader(frame, 1)
        val hmacResponse = reader.bytes()
        val key = Protocol.argon2DeriveKey(password)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretSpec)
        val expected = mac.doFinal(nonce)
        assertTrue("HMAC should match for unicode password", hmacResponse.contentEquals(expected))
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
    fun frameLength_unknownOpcode_throws() {
        val badOpcodes = byteArrayOf(0x00, 0xFF.toByte())
        for (op in badOpcodes) {
            val data = byteArrayOf(op, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            assertThrows(Protocol.ProtocolException::class.java) {
                Protocol.frameLength(data)
            }
        }
    }

    @Test
    fun frameLength_truncatedSync_returns9() {
        // OP_SYNC is fixed-size: 1 + 8 = 9. frameLength returns 9 even with truncated data.
        val truncated = byteArrayOf(Protocol.OP_SYNC, 0x01, 0x02)
        assertEquals(9, Protocol.frameLength(truncated))
    }

    @Test
    fun parseAuthChallenge_wrongSize_throws() {
        // parseAuthChallenge expects at least 16 nonce bytes, give it 15.
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

        for ((idx, pair) in frames.withIndex()) {
            val frame = pair.second
            assertEquals("Client $idx: opcode", Protocol.OP_AUTH, frame[0])
            assertEquals("Client $idx: frameLength", Protocol.frameLength(frame), frame.size)
            assertEquals("Client $idx: HMAC-only frame is 37 bytes", 37, frame.size)
        }

        // Verify each client's HMAC independently with its own Argon2 key.
        for ((idx, client) in clients.withIndex()) {
            val pw = client.password
            val nonce = client.nonce
            val frame = frames[idx].second
            val reader = Protocol.Reader(frame, 1)
            val hmacResponse = reader.bytes()

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
        val hmac0 = reader0.bytes()

        val key1 = Protocol.argon2DeriveKey(clients[1].password)
        val mac1 = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec1 = javax.crypto.spec.SecretKeySpec(key1, "HmacSHA256")
        mac1.init(secretSpec1)
        mac1.update(clients[0].nonce)
        val wrongExpected = mac1.doFinal()

        assertFalse("Client 0's HMAC must not verify with client 1 key",
            hmac0.contentEquals(wrongExpected))
    }

    @Test
    fun opcode_hierarchy_valuesMatchDomainCategorization() {
        // Auth Domain (0x1_)
        assertEquals(0x11.toByte(), Protocol.Op.Auth.CHALLENGE)
        assertEquals(0x12.toByte(), Protocol.Op.Auth.RESPONSE)
        assertEquals(0x13.toByte(), Protocol.Op.Auth.RESULT)
        assertEquals(Protocol.Op.Auth.CHALLENGE, Protocol.OP_AUTH_CHALLENGE)
        assertEquals(Protocol.Op.Auth.RESPONSE, Protocol.OP_AUTH)
        assertEquals(Protocol.Op.Auth.RESULT, Protocol.OP_AUTH_RESULT)

        // Session Control Domain (0x2_)
        assertEquals(0x21.toByte(), Protocol.Op.Session.HEARTBEAT)
        assertEquals(0x22.toByte(), Protocol.Op.Session.NEW_CERT_HASH)
        assertEquals(0x23.toByte(), Protocol.Op.Session.DISCONNECT)
        assertEquals(Protocol.Op.Session.HEARTBEAT, Protocol.OP_HEARTBEAT)
        assertEquals(Protocol.Op.Session.NEW_CERT_HASH, Protocol.OP_NEW_CERT_HASH)
        assertEquals(Protocol.Op.Session.DISCONNECT, Protocol.OP_DISCONNECT)

        // Data & Relay Domain (0x3_)
        assertEquals(0x31.toByte(), Protocol.Op.Data.KEY_EXCHANGE)
        assertEquals(0x32.toByte(), Protocol.Op.Data.DATA)
        assertEquals(0x33.toByte(), Protocol.Op.Data.SYNC)
        assertEquals(0x34.toByte(), Protocol.Op.Data.SYNC_RESPONSE)
        assertEquals(Protocol.Op.Data.KEY_EXCHANGE, Protocol.OP_KEY_EXCHANGE_KEM_DSA)
        assertEquals(Protocol.Op.Data.DATA, Protocol.OP_DATA)
        assertEquals(Protocol.Op.Data.SYNC, Protocol.OP_SYNC)
        assertEquals(Protocol.Op.Data.SYNC_RESPONSE, Protocol.OP_SYNC_RESPONSE)
    }
}
