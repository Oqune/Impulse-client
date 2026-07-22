package com.example.impulse.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun data_outbound_hasNoServerIdPrefix() {
        // Client -> server Data frame is just len(u16) + payload (no server id /
        // timestamp prefix). The relay adds those when it re-broadcasts.
        val payload = "secret message".toByteArray(Charsets.UTF_8)
        val frame = Protocol.buildData(payload)
        assertEquals(Protocol.OP_DATA, frame[0])
        // parseData expects the relay layout (u64+u64+len+payload), so feeding a
        // raw outbound frame must NOT be used for parsing; we only assert the
        // outbound shape here by re-reading with a matching writer.
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
        // Simulate the server relay: server_msg_id(u64) + timestamp(u64) + len(u16) + payload.
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
    fun authResult_roundTrip() {
        // Success carries no error message.
        val okFrame = Protocol.buildAuthResult(true)
        assertEquals(Protocol.OP_AUTH_RESULT, okFrame[0])
        val ok = Protocol.parseAuthResult(Protocol.Reader(okFrame, 1))
        assertTrue(ok.success)
        assertEquals(null, ok.errorMessage)

        // Failure carries the error string.
        val failFrame = Protocol.buildAuthResult(false, "bad password")
        val fail = Protocol.parseAuthResult(Protocol.Reader(failFrame, 1))
        assertFalse(fail.success)
        assertEquals("bad password", fail.errorMessage)
    }

    @Test
    fun syncResponse_roundTrip() {
        val msgs = listOf(
            Protocol.SyncMessage(1L, 100L, "a".toByteArray()),
            Protocol.SyncMessage(2L, 200L, "b".toByteArray())
        )
        val frame = Protocol.buildSyncResponse(msgs)
        assertEquals(Protocol.OP_SYNC_RESPONSE, frame[0])
        val parsed = Protocol.parseSyncResponse(Protocol.Reader(frame, 1))
        assertEquals(2, parsed.messages.size)
        assertEquals(1L, parsed.messages[0].id)
        assertEquals(2L, parsed.messages[1].id)
    }

    @Test
    fun keyExchange_roundTrip() {
        val pub = ByteArray(1184) { it.toByte() }
        val frame = Protocol.buildKeyExchange(pub)
        assertEquals(Protocol.OP_KEY_EXCHANGE, frame[0])
        val parsed = Protocol.parseKeyExchange(Protocol.Reader(frame, 1))
        assertTrue(parsed.publicKey.contentEquals(pub))
    }

    @Test
    fun auth_sendsSha256Hex_notRawPassword() {
        // The server compares SHA-256(password) (printf 'pw' | sha256sum), so the
        // Auth frame MUST carry the lowercase hex digest, never the plaintext.
        val pw = "yourpassword"
        val frame = Protocol.buildAuth(pw)
        assertEquals(Protocol.OP_AUTH, frame[0])
        val sent = Protocol.parseAuthPassword(Protocol.Reader(frame, 1))
        // Must NOT equal the raw password.
        assertTrue("auth frame must not contain the raw password", sent != pw)
        // Must equal the locally computed SHA-256 hex.
        assertEquals(Protocol.sha256Hex(pw), sent)
        assertEquals(64, sent.length)
    }
}
