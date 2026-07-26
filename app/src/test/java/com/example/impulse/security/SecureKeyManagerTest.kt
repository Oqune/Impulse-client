package com.example.impulse.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureKeyManagerTest {

    @Test
    fun keyPair_generatesValidLengths() {
        val kp = PqcCrypto.generateKeyPair()
        assertNotNull(kp.privateEncoded)
        assertNotNull(kp.publicEncoded)
        assertTrue(kp.privateEncoded.isNotEmpty())
        assertTrue(kp.publicEncoded.isNotEmpty())
    }

    @Test
    fun mlDsa65KeyPair_generatesValidLengths() {
        val kp = PqcCrypto.generateMlDsa65KeyPair()
        assertNotNull(kp.privateEncoded)
        assertNotNull(kp.publicEncoded)
        assertTrue(kp.privateEncoded.isNotEmpty())
        assertTrue(kp.publicEncoded.isNotEmpty())
    }

    @Test
    fun encapsulateKem_roundTrip() {
        val recipientKp = PqcCrypto.generateKeyPair()
        val (encKey, sharedSecret) = PqcCrypto.encapsulateKem(recipientKp.publicEncoded)
        assertNotNull(encKey)
        assertNotNull(sharedSecret)
        assertEquals(32, sharedSecret.size)
        assertTrue(encKey.isNotEmpty())
    }

    @Test
    fun encapsulateKem_decapsulateKem_producesMatchingSecret() {
        val recipientKp = PqcCrypto.generateKeyPair()
        val (encKey, senderSecret) = PqcCrypto.encapsulateKem(recipientKp.publicEncoded)
        val recipientSecret = PqcCrypto.decapsulateKem(encKey, recipientKp.privateEncoded)
        assertArrayEquals("shared secrets must match", senderSecret, recipientSecret)
    }

    @Test
    fun mlDsa65_signAndVerify_roundTrip() {
        val kp = PqcCrypto.generateMlDsa65KeyPair()
        val data = "test message".toByteArray()
        val sig = PqcCrypto.signMlDsa65(kp.privateEncoded, data)
        assertTrue(sig.isNotEmpty())
        assertTrue(PqcCrypto.verifyMlDsa65(kp.publicEncoded, data, sig))
    }

    @Test
    fun mlDsa65_verifyRejectsWrongKey() {
        val kp1 = PqcCrypto.generateMlDsa65KeyPair()
        val kp2 = PqcCrypto.generateMlDsa65KeyPair()
        val data = "test message".toByteArray()
        val sig = PqcCrypto.signMlDsa65(kp1.privateEncoded, data)
        assertFalse(PqcCrypto.verifyMlDsa65(kp2.publicEncoded, data, sig))
    }

    @Test
    fun encapsulateKem_encKeyDiffersEachTime() {
        val kp = PqcCrypto.generateKeyPair()
        val (enc1, _) = PqcCrypto.encapsulateKem(kp.publicEncoded)
        val (enc2, _) = PqcCrypto.encapsulateKem(kp.publicEncoded)
        assertFalse("encapsulated keys should differ per invocation", enc1.contentEquals(enc2))
    }

    @Test
    fun fingerprint_isConsistent() {
        val fp1 = SecureKeyManager.fingerprintForBytes(byteArrayOf(1, 2, 3))
        val fp2 = SecureKeyManager.fingerprintForBytes(byteArrayOf(1, 2, 3))
        assertEquals(fp1, fp2)
        assertEquals(32, fp1.length)
    }

    @Test
    fun fingerprint_differsForDifferentInput() {
        val fp1 = SecureKeyManager.fingerprintForBytes(byteArrayOf(1, 2, 3))
        val fp2 = SecureKeyManager.fingerprintForBytes(byteArrayOf(4, 5, 6))
        assertFalse(fp1 == fp2)
    }
}
