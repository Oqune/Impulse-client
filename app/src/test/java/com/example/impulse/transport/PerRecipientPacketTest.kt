package com.example.impulse.transport

import com.example.impulse.security.PqcCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PerRecipientPacketTest {

    @Test
    fun `per-recipient blob round-trip`() {
        val senderKem = PqcCrypto.generateKeyPair()
        val recipientKem = PqcCrypto.generateKeyPair()

        val (encKey, sharedSecret) = PqcCrypto.encapsulateKem(recipientKem.publicEncoded)
        val plaintext = "Hello, world!".toByteArray()
        val ciphertext = PqcCrypto.aesEncrypt(sharedSecret, plaintext)

        val w = Protocol.Writer()
        w.u32(1)
        val recipientId = java.security.MessageDigest.getInstance("SHA-256")
            .digest(recipientKem.publicEncoded).copyOf(32)
        w.bytes(recipientId)
        w.bytes(encKey)
        w.bytes(ciphertext)
        val blob = w.toByteArray()

        val r = Protocol.Reader(blob)
        val count = r.u32().toInt()
        assertEquals(1, count)

        val parsedId = r.bytes()
        assertArrayEquals(recipientId, parsedId)

        val parsedEncKey = r.bytes()
        assertArrayEquals(encKey, parsedEncKey)

        val parsedCiphertext = r.bytes()

        val recoveredSecret = PqcCrypto.decapsulateKem(parsedEncKey, recipientKem.privateEncoded)
        val recoveredPlaintext = PqcCrypto.aesDecrypt(recoveredSecret, parsedCiphertext)
        assertArrayEquals(plaintext, recoveredPlaintext)
    }

    @Test
    fun `multiple recipients get different encKeys but same plaintext`() {
        val senderKem = PqcCrypto.generateKeyPair()
        val recipient1 = PqcCrypto.generateKeyPair()
        val recipient2 = PqcCrypto.generateKeyPair()

        val plaintext = "Test message".toByteArray()

        val (encKey1, secret1) = PqcCrypto.encapsulateKem(recipient1.publicEncoded)
        val (encKey2, secret2) = PqcCrypto.encapsulateKem(recipient2.publicEncoded)

        assertFalse(encKey1.contentEquals(encKey2))

        val ct1 = PqcCrypto.aesEncrypt(secret1, plaintext)
        val ct2 = PqcCrypto.aesEncrypt(secret2, plaintext)

        assertFalse(ct1.contentEquals(ct2))

        val rec1 = PqcCrypto.decapsulateKem(encKey1, recipient1.privateEncoded)
        val rec2 = PqcCrypto.decapsulateKem(encKey2, recipient2.privateEncoded)
        assertArrayEquals(plaintext, PqcCrypto.aesDecrypt(rec1, ct1))
        assertArrayEquals(plaintext, PqcCrypto.aesDecrypt(rec2, ct2))
    }
}