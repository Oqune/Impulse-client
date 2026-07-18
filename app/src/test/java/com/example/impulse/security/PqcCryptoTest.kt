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
    fun ed25519_signVerify_roundTrip() {
        val kp = PqcCrypto.generateEd25519KeyPair()
        val msg = "authenticated message payload".toByteArray(Charsets.UTF_8)
        val sig = PqcCrypto.sign(kp.privateEncoded, msg)
        assertTrue("signature must be 64 bytes", sig.size == 64)
        assertTrue("valid signature verifies", PqcCrypto.verify(kp.publicEncoded, msg, sig))
    }

    @Test
    fun ed25519_verifyRejectsTamperedMessage() {
        val kp = PqcCrypto.generateEd25519KeyPair()
        val msg = "original".toByteArray(Charsets.UTF_8)
        val sig = PqcCrypto.sign(kp.privateEncoded, msg)
        val tampered = "tampered".toByteArray(Charsets.UTF_8)
        assertFalse("tampered message must fail verification", PqcCrypto.verify(kp.publicEncoded, tampered, sig))
    }

    @Test
    fun ed25519_verifyRejectsWrongKey() {
        val alice = PqcCrypto.generateEd25519KeyPair()
        val bob = PqcCrypto.generateEd25519KeyPair()
        val msg = "hello".toByteArray(Charsets.UTF_8)
        val sig = PqcCrypto.sign(alice.privateEncoded, msg)
        assertFalse("signature from alice must not verify under bob's key",
            PqcCrypto.verify(bob.publicEncoded, msg, sig))
    }
}
