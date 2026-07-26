package com.example.impulse.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "public_keys",
    indices = [
        Index(value = ["server_id"]),
        Index(value = ["server_id", "fingerprint"], unique = true)
    ]
)
data class PublicKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "fingerprint") val fingerprint: String,
    @ColumnInfo(name = "kem_public_key") val kemPublicKey: ByteArray? = null,
    @ColumnInfo(name = "dsa_public_key") val dsaPublicKey: ByteArray? = null,
    @ColumnInfo(name = "first_seen") val firstSeen: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_seen") val lastSeen: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PublicKeyEntity
        return id == other.id && serverId == other.serverId && fingerprint == other.fingerprint
    }

    override fun hashCode(): Int = 31 * id.hashCode() + serverId.hashCode() + fingerprint.hashCode()
}
