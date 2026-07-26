package com.example.impulse.data

import android.content.Context
import com.example.impulse.data.db.MessageDatabase
import com.example.impulse.data.db.PublicKeyEntity
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

    suspend fun getAllKemPublicKeys(serverId: String): List<ByteArray> =
        dao.getAllKemKeys(serverId)

    suspend fun getDsaPublicKey(serverId: String, fingerprint: String): ByteArray? =
        dao.getDsaKey(serverId, fingerprint)
}
