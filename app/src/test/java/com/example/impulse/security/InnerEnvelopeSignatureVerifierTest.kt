package com.example.impulse.security

import com.example.impulse.transport.Protocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class InnerEnvelopeSignatureVerifierTest {
    private val keyPair = PqcCrypto.generateMlDsa65KeyPair()

    private fun envelope(
        sender: String = "alice",
        content: String = "hello",
        clientTs: Long = 1234L,
        nonce: String = "nonce-1",
        dm: String = "peer-fingerprint",
        signature: ByteArray = sign(sender, content, clientTs, nonce, dm)
    ) = Protocol.InnerEnvelope(sender, Protocol.base64Encode(signature), content, clientTs, nonce, dm)

    @Test
    fun acceptsEnvelopeSignedByRealMlDsaKey() {
        val valid = InnerEnvelopeSignatureVerifier.verify(envelope(), keyPair.publicEncoded) { key, message, sig ->
            PqcCrypto.verifyMlDsa65(key, message, sig)
        }

        assertTrue(valid)
    }

    @Test
    fun rejectsMissingMalformedAndInvalidSignatures() {
        assertFalse(InnerEnvelopeSignatureVerifier.verify(envelope(signature = byteArrayOf()), keyPair.publicEncoded) { key, message, sig ->
            PqcCrypto.verifyMlDsa65(key, message, sig)
        })
        assertFalse(InnerEnvelopeSignatureVerifier.verify(envelope().copy(signature = "!"), keyPair.publicEncoded) { key, message, sig ->
            PqcCrypto.verifyMlDsa65(key, message, sig)
        })
        assertFalse(InnerEnvelopeSignatureVerifier.verify(envelope(signature = byteArrayOf(1, 2, 3)), keyPair.publicEncoded) { key, message, sig ->
            PqcCrypto.verifyMlDsa65(key, message, sig)
        })
    }

    @Test
    fun alterationToAnySignedFieldChangesCanonicalBytes() {
        val original = envelope()
        val altered = listOf(
            original.copy(sender = "mallory"),
            original.copy(content = "changed"),
            original.copy(clientTs = original.clientTs + 1),
            original.copy(nonce = "different"),
            original.copy(dm = "another-peer")
        )

        altered.forEach { changed ->
            assertFalse(
                "alteration must invalidate signature: $changed",
                InnerEnvelopeSignatureVerifier.verify(changed, keyPair.publicEncoded) { key, message, sig ->
                    PqcCrypto.verifyMlDsa65(key, message, sig)
                }
            )
        }
    }

    @Test
    fun legacyEnvelopeUsesLegacyCanonicalForm() {
        val legacy = envelope(clientTs = 0L, nonce = "", dm = "")

        assertTrue(InnerEnvelopeSignatureVerifier.verify(legacy, keyPair.publicEncoded) { key, message, sig ->
            PqcCrypto.verifyMlDsa65(key, message, sig)
        })
    }

    @Test
    fun invalidSignatureNeverRunsDurableAcceptanceCallback() = runBlocking {
        val original = envelope()
        var persistenceCalls = 0
        val result = InnerEnvelopeSignatureVerifier.verifyFreshAndPersist(
            envelope = original.copy(content = "altered"),
            publicKey = keyPair.publicEncoded,
            now = original.clientTs,
            freshnessWindowMs = 96L * 60L * 60L * 1000L,
            verifySignature = { key, message, sig -> PqcCrypto.verifyMlDsa65(key, message, sig) },
            persist = { persistenceCalls++; "accepted" }
        )
        assertNull(result)
        assertEquals(0, persistenceCalls)
    }

    @Test
    fun signedTimestampLongMinCannotPassFreshnessCheck() = runBlocking {
        val timestamp = Long.MIN_VALUE
        val canonical = Protocol.buildSignedInnerEnvelope("alice", "", "hello", timestamp, "nonce-1", "peer-fingerprint")
        val signed = Protocol.InnerEnvelope(
            "alice", Protocol.base64Encode(PqcCrypto.signMlDsa65(keyPair.privateEncoded, canonical)),
            "hello", timestamp, "nonce-1", "peer-fingerprint"
        )
        var persistenceCalls = 0
        val result = InnerEnvelopeSignatureVerifier.verifyFreshAndPersist(
            signed, keyPair.publicEncoded, System.currentTimeMillis(), 96L * 60L * 60L * 1000L,
            { key, message, sig -> PqcCrypto.verifyMlDsa65(key, message, sig) },
            { persistenceCalls++; "accepted" }
        )
        assertNull(result)
        assertEquals(0, persistenceCalls)
    }

    private fun sign(sender: String, content: String, clientTs: Long, nonce: String, dm: String): ByteArray {
        val canonical = if (clientTs != 0L || nonce.isNotEmpty()) {
            Protocol.buildSignedInnerEnvelope(sender, "", content, clientTs, nonce, dm)
        } else {
            Protocol.buildInnerEnvelope(sender, "", content)
        }
        return PqcCrypto.signMlDsa65(keyPair.privateEncoded, canonical)
    }
}
