package com.example.impulse.data

import com.example.impulse.data.db.PublicKeyEntity
import org.junit.Assert.*
import org.junit.Test

class PublicKeyCacheTest {

    @Test
    fun `PublicKeyEntity equality by id and serverId and fingerprint`() {
        val e1 = PublicKeyEntity(id = 1, serverId = "s1", fingerprint = "fp1")
        val e2 = PublicKeyEntity(id = 1, serverId = "s1", fingerprint = "fp1")
        val e3 = PublicKeyEntity(id = 2, serverId = "s1", fingerprint = "fp1")
        assertEquals(e1, e2)
        assertNotEquals(e1, e3)
    }

    @Test
    fun `PublicKeyEntity fingerprint is unique identifier`() {
        val e1 = PublicKeyEntity(serverId = "s1", fingerprint = "abc123")
        val e2 = PublicKeyEntity(serverId = "s1", fingerprint = "abc123")
        val e3 = PublicKeyEntity(serverId = "s1", fingerprint = "def456")
        assertEquals(e1.fingerprint, e2.fingerprint)
        assertNotEquals(e1.fingerprint, e3.fingerprint)
    }

    @Test
    fun `ByteArray keys are stored correctly`() {
        val kemKey = byteArrayOf(1, 2, 3, 4, 5)
        val dsaKey = byteArrayOf(6, 7, 8, 9, 10)
        val entity = PublicKeyEntity(
            serverId = "s1",
            fingerprint = "fp1",
            kemPublicKey = kemKey,
            dsaPublicKey = dsaKey
        )
        assertArrayEquals(kemKey, entity.kemPublicKey)
        assertArrayEquals(dsaKey, entity.dsaPublicKey)
    }
}
