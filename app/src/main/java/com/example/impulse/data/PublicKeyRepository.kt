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
        val entity = if (existing != null) {
            existing.copy(
                kemPublicKey = kemPub ?: existing.kemPublicKey,
                dsaPublicKey = dsaPub ?: existing.dsaPublicKey,
                lastSeen = System.currentTimeMillis()
            )
        } else {
            PublicKeyEntity(
                serverId = serverId,
                fingerprint = fingerprint,
                kemPublicKey = kemPub,
                dsaPublicKey = dsaPub
            )
        }
        dao.upsert(entity)
        LogManager.d("PublicKeyRepository", "Cached key: fp=$fingerprint server=$serverId")
    }

    suspend fun cacheDsaKey(serverId: String, dsaPub: ByteArray): String? {
        val dsaFingerprint = fingerprintForBytes(dsaPub)
        val kemOnlyEntry = dao.getKemOnlyEntry(serverId)
        if (kemOnlyEntry != null) {
            val merged = kemOnlyEntry.copy(
                dsaPublicKey = dsaPub,
                lastSeen = System.currentTimeMillis()
            )
            dao.upsert(merged)
            LogManager.d("PublicKeyRepository", "Merged DSA into KEM entry: kem_fp=${kemOnlyEntry.fingerprint} dsa_fp=$dsaFingerprint server=$serverId")
            return kemOnlyEntry.fingerprint
        } else {
            val entity = PublicKeyEntity(
                serverId = serverId,
                fingerprint = dsaFingerprint,
                dsaPublicKey = dsaPub
            )
            dao.upsert(entity)
            LogManager.d("PublicKeyRepository", "Cached DSA key standalone: fp=$dsaFingerprint server=$serverId")
            return null
        }
    }

    suspend fun getAllKemPublicKeys(serverId: String): List<ByteArray> =
        dao.getAllKemKeys(serverId)

    suspend fun getDsaPublicKey(serverId: String, fingerprint: String): ByteArray? =
        dao.getDsaKey(serverId, fingerprint)

    private fun fingerprintForBytes(data: ByteArray): String {
        return SecureKeyManager.fingerprintForBytes(data)
    }
}
