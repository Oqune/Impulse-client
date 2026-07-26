package com.example.impulse.security

import org.junit.Assert.assertArrayEquals
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
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "Hello post-quantum world!".toByteArray(Charsets.UTF_8)
        val ct = PqcCrypto.aesEncrypt(key, plaintext)
        assertTrue(ct.size > plaintext.size)
        val pt = PqcCrypto.aesDecrypt(key, ct)
        assertArrayEquals(plaintext, pt)
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

    private fun assertFalse(message: String, value: Boolean) {
        org.junit.Assert.assertFalse(message, value)
    }
}
