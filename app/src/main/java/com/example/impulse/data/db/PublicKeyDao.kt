package com.example.impulse.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PublicKeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: PublicKeyEntity): Long

    @Query("SELECT * FROM public_keys WHERE server_id = :serverId AND fingerprint = :fingerprint")
    suspend fun getByFingerprint(serverId: String, fingerprint: String): PublicKeyEntity?

    @Query("SELECT kem_public_key FROM public_keys WHERE server_id = :serverId AND kem_public_key IS NOT NULL")
    suspend fun getAllKemKeys(serverId: String): List<ByteArray>

    @Query("SELECT dsa_public_key FROM public_keys WHERE server_id = :serverId AND fingerprint = :fingerprint AND dsa_public_key IS NOT NULL")
    suspend fun getDsaKey(serverId: String, fingerprint: String): ByteArray?

    @Query("SELECT * FROM public_keys WHERE server_id = :serverId AND kem_public_key IS NOT NULL AND dsa_public_key IS NULL ORDER BY last_seen DESC LIMIT 1")
    suspend fun getKemOnlyEntry(serverId: String): PublicKeyEntity?
}
