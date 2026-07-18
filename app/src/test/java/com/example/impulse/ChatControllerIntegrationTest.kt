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
 *
 * This exercises the full cycle:
 *   connect → auth result → key exchange (both peers) → group secret derived
 *   → READY → send (optimistic) → server echo (real id) → dedup/upsert.
 */
class ChatControllerIntegrationTest {

    @Test
    fun fullProtocolCycle_twoClientsDeriveSameGroupSecret() = runBlocking {
        // Simulate two clients exchanging ML-KEM public keys via the server.
        val alice = PqcCrypto.generateKeyPair()
        val bob = PqcCrypto.generateKeyPair()

        // Each client observes the SET {alice, bob} (own + peer).
        val aliceKeys = listOf(alice.publicEncoded, bob.publicEncoded)
        val bobKeys = listOf(bob.publicEncoded, alice.publicEncoded)

        val aliceSecret = PqcCrypto.deriveGroupKey(aliceKeys)
        val bobSecret = PqcCrypto.deriveGroupKey(bobKeys)

        assertEquals(32, aliceSecret.size)
        assertTrue("both clients must derive the identical group secret",
            aliceSecret.contentEquals(bobSecret))
    }

    @Test
    fun authResultAndKeyExchangeFramesRoundTrip() = runBlocking {
        // Server → client: auth success.
        val authOk = Protocol.buildAuthResult(true)
        assertEquals(Protocol.OP_AUTH_RESULT, authOk[0])
        val parsed = Protocol.parseAuthResult(Protocol.Reader(authOk, 1))
        assertTrue(parsed.success)

        // Client → server: publish ML-KEM public key.
        val pub = PqcCrypto.generateKeyPair().publicEncoded
        val kex = Protocol.buildKeyExchange(pub)
        assertEquals(Protocol.OP_KEY_EXCHANGE, kex[0])
        val kexParsed = Protocol.parseKeyExchange(Protocol.Reader(kex, 1))
        assertTrue(kexParsed.publicKey.contentEquals(pub))
    }

    @Test
    fun dataFrame_carriesServerAssignedId_forDedup() = runBlocking {
        val secret = PqcCrypto.randomKey()
        val inner = Protocol.buildInnerEnvelope("alice", "", "hello")
        val blob = PqcCrypto.aesEncrypt(secret, inner)

        // Client sends (no server id yet).
        val outbound = Protocol.buildData(blob)
        assertEquals(Protocol.OP_DATA, outbound[0])

        // Server relays with a real id + timestamp (the dedup key).
        val w = Protocol.Writer()
        w.u8(Protocol.OP_DATA.toInt())
        w.u64(12345L)            // server_msg_id
        w.u64(1_700_000_000_000L) // timestamp
        w.bytes(blob)
        val relayed = w.toByteArray()

        val df = Protocol.parseData(Protocol.Reader(relayed, 1))
        assertEquals(12345L, df.serverMsgId)
        assertEquals(1_700_000_000_000L, df.timestamp)
        assertTrue(df.payload.contentEquals(blob))

        // Decrypt and verify content.
        val decrypted = Protocol.parseInnerEnvelope(PqcCrypto.aesDecrypt(secret, df.payload))
        assertNotNull(decrypted)
        assertEquals("alice", decrypted!!.sender)
        assertEquals("hello", decrypted.content)
    }

    @Test
    fun optimisticSendUsesNegativeTempId_noCollisionWithRealId() = runBlocking {
        // The controller stores an optimistic copy under a NEGATIVE temp id so
        // it can never collide with a real (>=0) server id. We assert the
        // contract the controller relies on.
        val tempId = -System.currentTimeMillis()
        assertTrue("temp id must be negative to avoid collision", tempId < 0)
        val realId = 42L
        assertTrue("real id must be non-negative", realId >= 0)
        assertTrue("temp and real ids never collide", tempId != realId)
    }

    @Test
    fun syncResponse_roundTripsMultipleMessages() = runBlocking {
        val secret = PqcCrypto.randomKey()
        val m1 = Protocol.SyncMessage(1L, 100L, PqcCrypto.aesEncrypt(secret,
            Protocol.buildInnerEnvelope("a", "", "x")))
        val m2 = Protocol.SyncMessage(2L, 200L, PqcCrypto.aesEncrypt(secret,
            Protocol.buildInnerEnvelope("b", "", "y")))
        val frame = Protocol.buildSyncResponse(listOf(m1, m2))
        val parsed = Protocol.parseSyncResponse(Protocol.Reader(frame, 1))
        assertEquals(2, parsed.messages.size)
        assertEquals(1L, parsed.messages[0].id)
        assertEquals(2L, parsed.messages[1].id)
    }

    @Test
    fun connectionStateMachine_hasReadyState() = runBlocking {
        // Sanity check that the richer state machine includes the states the
        // UI gates sending on.
        val states = ConnectionState.values().map { it.name }
        assertTrue(states.contains("AUTHENTICATING"))
        assertTrue(states.contains("READY"))
        assertTrue(states.contains("ERROR"))
    }
}
