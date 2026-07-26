package com.example.impulse.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun auth_sendsSha256Hex() {
        val pw = "yourpassword"
        val frame = Protocol.buildAuth(pw)
        assertEquals(Protocol.OP_AUTH, frame[0])
        assertEquals(Protocol.sha256Hex(pw).length, 64)
    }
}
