package com.example.impulse.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PqcCryptoTest {

    @Test
    fun keyPair_generatesValidLengths() {
        val kp = PqcCrypto.generateKeyPair()
        assertTrue("public key should be non-empty", kp.publicEncoded.isNotEmpty())
        assertTrue("private key should be non-empty", kp.privateEncoded.isNotEmpty())
    }

    @Test
    fun aesGcm_roundTrip() {
        val key = PqcCrypto.randomKey()
        val plaintext = "Привет, пост-квантовый мир!".toByteArray(Charsets.UTF_8)
        val ct = PqcCrypto.aesEncrypt(key, plaintext)
        // ciphertext must be longer than plaintext (IV + tag).
        assertTrue(ct.size > plaintext.size)
        val pt = PqcCrypto.aesDecrypt(key, ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test
    fun deriveGroupKey_isDeterministicAcrossClients() {
        // Simulate three clients each observing the same SET of ML-KEM pubkeys
        // (their own + the two peers). Because deriveGroupKey sorts the set
        // before hashing, every client must derive the identical 32-byte key.
        val a = PqcCrypto.generateKeyPair()
        val b = PqcCrypto.generateKeyPair()
        val c = PqcCrypto.generateKeyPair()

        val keysA = listOf(a.publicEncoded, b.publicEncoded, c.publicEncoded)
        val keysB = listOf(c.publicEncoded, a.publicEncoded, b.publicEncoded)
        val keysC = listOf(b.publicEncoded, c.publicEncoded, a.publicEncoded)

        val sA = PqcCrypto.deriveGroupKey(keysA)
        val sB = PqcCrypto.deriveGroupKey(keysB)
        val sC = PqcCrypto.deriveGroupKey(keysC)

        assertEquals(32, sA.size)
        assertArrayEquals("group secret must match across clients", sA, sB)
        assertArrayEquals("group secret must match across clients", sA, sC)
    }

    @Test
    fun deriveGroupKey_differsForDifferentInputs() {
        val a = PqcCrypto.generateKeyPair().publicEncoded
        val b = PqcCrypto.generateKeyPair().publicEncoded
        val s1 = PqcCrypto.deriveGroupKey(listOf(a))
        val s2 = PqcCrypto.deriveGroupKey(listOf(b))
        assertFalse("different key sets should yield different secrets", s1.contentEquals(s2))
    }

    @Test
    fun mldsa65_signVerify_roundTrip() {
        val kp = PqcCrypto.generateMlDsa65KeyPair()
        val msg = "authenticated message payload".toByteArray(Charsets.UTF_8)
        val sig = PqcCrypto.signMlDsa65(kp.privateEncoded, msg)
        assertTrue("signature should be non-empty", sig.isNotEmpty())
        assertTrue("valid signature verifies", PqcCrypto.verifyMlDsa65(kp.publicEncoded, msg, sig))
    }

    @Test
    fun mldsa65_verifyRejectsTamperedMessage() {
        val kp = PqcCrypto.generateMlDsa65KeyPair()
        val msg = "original".toByteArray(Charsets.UTF_8)
        val sig = PqcCrypto.signMlDsa65(kp.privateEncoded, msg)
        val tampered = "tampered".toByteArray(Charsets.UTF_8)
        assertFalse("tampered message must fail verification", PqcCrypto.verifyMlDsa65(kp.publicEncoded, tampered, sig))
    }

    @Test
    fun mldsa65_verifyRejectsWrongKey() {
        val alice = PqcCrypto.generateMlDsa65KeyPair()
        val bob = PqcCrypto.generateMlDsa65KeyPair()
        val msg = "hello".toByteArray(Charsets.UTF_8)
        val sig = PqcCrypto.signMlDsa65(alice.privateEncoded, msg)
        assertFalse("signature from alice must not verify under bob's key",
            PqcCrypto.verifyMlDsa65(bob.publicEncoded, msg, sig))
    }
}
