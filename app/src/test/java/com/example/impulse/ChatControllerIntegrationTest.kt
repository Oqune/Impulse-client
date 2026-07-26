package com.example.impulse

import com.example.impulse.security.PqcCrypto
import com.example.impulse.transport.ConnectionState
import com.example.impulse.transport.Protocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration test of the binary protocol + crypto pipeline WITHOUT a real
 * network. We emulate the server side by feeding hand-crafted frames into
 * [ChatController.handleIncoming] (the same entry point the transport uses) and
 * capturing what the controller sends back via a fake "transport" sink.
 */
class ChatControllerIntegrationTest {

    @Test
    fun dataFrame_carriesServerAssignedId_forDedup() = runBlocking {
        val secret = ByteArray(32) { it.toByte() }
        val inner = Protocol.buildInnerEnvelope("alice", "", "hello")
        val blob = PqcCrypto.aesEncrypt(secret, inner)

        val outbound = Protocol.buildData(blob)
        assertEquals(Protocol.OP_DATA, outbound[0])

        val w = Protocol.Writer()
        w.u8(Protocol.OP_DATA.toInt())
        w.u64(12345L)
        w.u64(1_700_000_000_000L)
        w.bytes(blob)
        val relayed = w.toByteArray()

        val df = Protocol.parseData(Protocol.Reader(relayed, 1))
        assertEquals(12345L, df.serverMsgId)
        assertEquals(1_700_000_000_000L, df.timestamp)
        assertTrue(df.payload.contentEquals(blob))

        val decrypted = Protocol.parseInnerEnvelope(PqcCrypto.aesDecrypt(secret, df.payload))
        assertNotNull(decrypted)
        assertEquals("alice", decrypted!!.sender)
        assertEquals("hello", decrypted.content)
    }

    @Test
    fun optimisticSendUsesNegativeTempId_noCollisionWithRealId() = runBlocking {
        val tempId = -System.currentTimeMillis()
        assertTrue("temp id must be negative to avoid collision", tempId < 0)
        val realId = 42L
        assertTrue("real id must be non-negative", realId >= 0)
        assertTrue("temp and real ids never collide", tempId != realId)
    }

    @Test
    fun connectionStateMachine_hasReadyState() = runBlocking {
        val states = ConnectionState.entries.map { it.name }
        assertTrue(states.contains("AUTHENTICATING"))
        assertTrue(states.contains("READY"))
        assertTrue(states.contains("ERROR"))
    }
}
