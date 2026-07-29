package com.example.impulse.data

import android.content.Context
import com.example.impulse.data.db.MessageDatabase
import com.example.impulse.data.db.PublicKeyEntity
import com.example.impulse.security.SecureKeyManager
import com.example.impulse.util.LogManager

class PublicKeyRepository(context: Context) {

    private val dao = MessageDatabase.getInstance(context).publicKeyDao()

    suspend fun cacheKey(serverId: String, fingerprint: String, kemPub: ByteArray?, dsaPub: ByteArray?) {
        val existing = dao.getByFingerprint(serverId, fingerprint)
        if (existing != null) {
            val merged = existing.copy(
                kemPublicKey = kemPub ?: existing.kemPublicKey,
                dsaPublicKey = dsaPub ?: existing.dsaPublicKey,
                lastSeen = System.currentTimeMillis()
            )
            dao.upsert(merged)
            LogManager.d("PublicKeyRepository", "Cached key: fp=$fingerprint server=$serverId")
            return
        }

        val entity = PublicKeyEntity(
            serverId = serverId,
            fingerprint = fingerprint,
            kemPublicKey = kemPub,
            dsaPublicKey = dsaPub
        )
        dao.upsert(entity)
        LogManager.d("PublicKeyRepository", "Cached key: fp=$fingerprint server=$serverId")
    }

    suspend fun cacheDsaKey(serverId: String, dsaPub: ByteArray) {
        val dsaFingerprint = fingerprintForBytes(dsaPub)
        val entity = PublicKeyEntity(
            serverId = serverId,
            fingerprint = dsaFingerprint,
            dsaPublicKey = dsaPub
        )
        dao.upsert(entity)
        LogManager.d("PublicKeyRepository", "Cached DSA key standalone: fp=$dsaFingerprint server=$serverId")
    }

    suspend fun getAllKemPublicKeys(serverId: String): List<ByteArray> =
        dao.getAllKemKeys(serverId)

    suspend fun getDsaPublicKey(serverId: String, fingerprint: String): ByteArray? =
        dao.getDsaKey(serverId, fingerprint)

    private fun fingerprintForBytes(data: ByteArray): String {
        return SecureKeyManager.fingerprintForBytes(data)
    }
}
