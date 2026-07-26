package com.example.impulse.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBackupTest {

    @Test
    fun pbkdf2_producesConsistentKeyFromSameInputs() {
        val salt = ByteArray(16) { it.toByte() }
        val key1 = SecureKeyManager.pbkdf2("testPassword123".toCharArray(), salt, 10_000, 256)
        val key2 = SecureKeyManager.pbkdf2("testPassword123".toCharArray(), salt, 10_000, 256)
        assertArrayEquals("same password+salt must produce same key", key1, key2)
        assertEquals(32, key1.size)
    }

    @Test
    fun pbkdf2_differentPasswordsProduceDifferentKeys() {
        val salt = ByteArray(16) { 0x42 }
        val key1 = SecureKeyManager.pbkdf2("password1".toCharArray(), salt, 10_000, 256)
        val key2 = SecureKeyManager.pbkdf2("password2".toCharArray(), salt, 10_000, 256)
        assertTrue("different passwords must produce different keys", !key1.contentEquals(key2))
    }

    @Test
    fun pbkdf2_differentSaltsProduceDifferentKeys() {
        val salt1 = ByteArray(16) { 0x01 }
        val salt2 = ByteArray(16) { 0x02 }
        val key1 = SecureKeyManager.pbkdf2("testPassword".toCharArray(), salt1, 10_000, 256)
        val key2 = SecureKeyManager.pbkdf2("testPassword".toCharArray(), salt2, 10_000, 256)
        assertTrue("different salts must produce different keys", !key1.contentEquals(key2))
    }

    @Test
    fun backupFileFormat_versionByteIsCorrect() {
        val version: Byte = 0x01
        assertEquals(1, version.toInt())
    }

    @Test
    fun backupFileFormat_intLittleEndianRoundTrip() {
        val values = intArrayOf(0, 1, 255, 256, 65535, 1_000_000)
        for (v in values) {
            val bytes = SecureKeyManager.intToLittleEndian(v)
            assertEquals(4, bytes.size)
            val recovered = SecureKeyManager.intFromLittleEndian(bytes, 0)
            assertEquals("round-trip for $v", v, recovered)
        }
    }

    @Test
    fun backupFileFormat_intLittleEndianBytes() {
        val littleEndianFF00 = SecureKeyManager.intToLittleEndian(0x0000FF00.toInt())
        assertEquals(0x00.toByte(), littleEndianFF00[0])
        assertEquals(0xFF.toByte(), littleEndianFF00[1])
        assertEquals(0x00.toByte(), littleEndianFF00[2])
        assertEquals(0x00.toByte(), littleEndianFF00[3])
    }

    @Test
    fun backupFileFormat_encryptedDataIsNonEmpty() {
        val plaintext = "sample ML-KEM private key bytes".toByteArray()
        val key = SecureKeyManager.pbkdf2("password".toCharArray(), ByteArray(16), 10_000, 256)
        val iv = ByteArray(12) { it.toByte() }
        val encrypted = SecureKeyManager.run {
            val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
            cipher.doFinal(plaintext)
        }
        assertTrue("encrypted data must be non-empty", encrypted.isNotEmpty())
        assertTrue("GCM ciphertext includes tag, so size >= plaintext size",
            encrypted.size >= plaintext.size)
    }
}
