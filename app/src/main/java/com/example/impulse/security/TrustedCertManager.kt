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

    /**
     * Handle server-pushed cert hash rotation (OP_NEW_CERT_HASH, 0x07).
     * Stores as PENDING — not trusted until the user re-scans the QR code.
     * A MITM attacker who pushes a rogue hash will not gain trust.
     */
    fun rotateHash(serverId: String, nextHash: String) {
        val normalized = nextHash.lowercase().trim()
        if (!normalized.matches(Regex("^[0-9a-f]{64}$"))) {
            LogManager.w(TAG, "rotateHash: malformed hash for server=$serverId")
            return
        }
        val current = getCertInfos(serverId).toMutableList()
        if (current.any { it.sha256Hex == normalized }) {
            LogManager.d(TAG, "rotateHash: hash already known for server=$serverId, ignoring")
            return
        }
        // Store as pending — require explicit user approval (QR re-scan) to trust.
        val pending = current.toMutableList()
        pending.add(CertInfo(sha256Hex = normalized, issuedAt = System.currentTimeMillis()))
        while (pending.size > MAX_HASHES + MAX_PENDING_HASHES) pending.removeAt(0)
        persistPending(serverId, pending)
        LogManager.i(TAG, "rotateHash: stored PENDING hash for server=$serverId (user must re-scan QR to trust)")
    }

    /** Promote pending hashes to trusted after user confirms via QR scan. */
    fun confirmPendingHashes(serverId: String) {
        val pending = loadPending(serverId)
        if (pending.isEmpty()) return
        val current = getCertInfos(serverId).toMutableList()
        for (info in pending) {
            if (current.any { it.sha256Hex == info.sha256Hex }) continue
            current.add(info)
        }
        while (current.size > MAX_HASHES) current.removeAt(0)
        persist(serverId, current)
        clearPending(serverId)
        LogManager.i(TAG, "confirmPendingHashes: promoted ${pending.size} pending hashes for server=$serverId")
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
    private fun pendingKeyFor(serverId: String) = "cert_hashes_pending::$serverId"

    private fun persistPending(serverId: String, infos: List<CertInfo>) {
        val arr = JSONArray()
        infos.forEach { info ->
            val obj = JSONObject()
            obj.put("h", info.sha256Hex)
            obj.put("t", info.issuedAt)
            arr.put(obj)
        }
        storage.putString(pendingKeyFor(serverId), arr.toString())
    }

    private fun loadPending(serverId: String): List<CertInfo> {
        val raw = storage.getString(pendingKeyFor(serverId))
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<CertInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(CertInfo(
                    sha256Hex = obj.getString("h").lowercase(),
                    issuedAt = obj.optLong("t", System.currentTimeMillis())
                ))
            }
            result.filter { it.sha256Hex.matches(Regex("^[0-9a-f]{64}$")) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun clearPending(serverId: String) {
        storage.remove(pendingKeyFor(serverId))
    }

    companion object {
        private const val TAG = "TrustedCertManager"
        const val MAX_HASHES = 2
        const val MAX_PENDING_HASHES = 2
    }
}
