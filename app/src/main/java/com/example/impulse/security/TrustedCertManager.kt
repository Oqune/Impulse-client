package com.example.impulse.security

import android.content.Context
import com.example.impulse.util.LogManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * TOFU trust store for server certificate fingerprints (SHA-256 of the DER
 * leaf certificate, hex-encoded). Up to [MAX_HASHES] hashes are kept per
 * server so a just-rotated cert and its predecessor are both accepted during
 * the overlap window.
 */
data class CertInfo(
    val sha256Hex: String,
    val issuedAt: Long
)

class TrustedCertManager(context: Context) {

    private val storage = SecureStorage(context)

    fun getHashes(serverId: String): List<String> {
        return getCertInfos(serverId).map { it.sha256Hex }
    }

    fun getCertInfos(serverId: String): List<CertInfo> {
        val raw = storage.getString(keyFor(serverId))
        if (raw.isEmpty()) {
            LogManager.d(TAG, "getCertInfos: no stored data for server=$serverId")
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<CertInfo>()
            for (i in 0 until arr.length()) {
                when (val item = arr.get(i)) {
                    is String -> result.add(
                        CertInfo(sha256Hex = item.lowercase(), issuedAt = System.currentTimeMillis())
                    )
                    is JSONObject -> result.add(
                        CertInfo(
                            sha256Hex = item.getString("h").lowercase(),
                            issuedAt = item.optLong("t", System.currentTimeMillis())
                        )
                    )
                }
            }
            result.filter { it.sha256Hex.matches(Regex("^[0-9a-f]{64}$")) }
        } catch (e: Exception) {
            LogManager.w(TAG, "getCertInfos: failed to parse for server=$serverId: ${e.message}")
            emptyList()
        }
    }

    fun isTrusted(serverId: String): Boolean = getCertInfos(serverId).isNotEmpty()

    /** Trust a fingerprint obtained out-of-band (QR scan / manual entry). */
    fun trustHash(serverId: String, hash: String) {
        val normalized = hash.lowercase().trim()
        val current = getCertInfos(serverId).toMutableList()
        if (current.any { it.sha256Hex == normalized }) {
            LogManager.i(TAG, "trustHash: hash already trusted for server=$serverId")
            return
        }
        current.add(CertInfo(sha256Hex = normalized, issuedAt = System.currentTimeMillis()))
        while (current.size > MAX_HASHES) current.removeAt(0)
        persist(serverId, current)
        LogManager.i(TAG, "trustHash: stored hash for server=$serverId (total=${current.size})")
    }

    /** Trust the next fingerprint announced by the server (rotation push, 0x07). */
    fun rotateHash(serverId: String, nextHash: String) {
        val normalized = nextHash.lowercase().trim()
        val current = getCertInfos(serverId).toMutableList()
        if (current.any { it.sha256Hex == normalized }) return
        current.add(CertInfo(sha256Hex = normalized, issuedAt = System.currentTimeMillis()))
        while (current.size > MAX_HASHES) current.removeAt(0)
        persist(serverId, current)
        LogManager.i(TAG, "rotateHash: stored rotated hash for server=$serverId (total=${current.size})")
    }

    fun forget(serverId: String) {
        LogManager.i(TAG, "forget: removing cert for server=$serverId")
        storage.remove(keyFor(serverId))
    }

    private fun persist(serverId: String, infos: List<CertInfo>) {
        val arr = JSONArray()
        infos.forEach { info ->
            val obj = JSONObject()
            obj.put("h", info.sha256Hex)
            obj.put("t", info.issuedAt)
            arr.put(obj)
        }
        storage.putString(keyFor(serverId), arr.toString())
    }

    private fun keyFor(serverId: String) = "cert_hashes::$serverId"

    companion object {
        private const val TAG = "TrustedCertManager"
        const val MAX_HASHES = 2
    }
}
