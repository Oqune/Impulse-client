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
 *
 * Thread-safe: all mutations are synchronized on the instance.
 */
data class CertInfo(
    val sha256Hex: String,
    val issuedAt: Long
)

class TrustedCertManager(context: Context) {

    private val storage = SecureStorage(context)

    fun getCertInfos(serverId: String): List<CertInfo> = synchronized(this) {
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
            result.filter { it.sha256Hex.matches(HASH_PATTERN) }
        } catch (e: Exception) {
            LogManager.w(TAG, "getCertInfos: failed to parse for server=$serverId: ${e.message}")
            emptyList()
        }
    }

    fun getHashes(serverId: String): List<String> = synchronized(this) {
        getCertInfos(serverId).map { it.sha256Hex }
    }

    fun isTrusted(serverId: String): Boolean = synchronized(this) {
        getCertInfos(serverId).isNotEmpty()
    }

    /** Trust a fingerprint obtained out-of-band (QR scan / manual entry). */
    fun trustHash(serverId: String, hash: String) = synchronized(this) {
        val normalized = hash.lowercase().trim()
        if (!normalized.matches(HASH_PATTERN)) {
            LogManager.w(TAG, "trustHash: malformed hash for server=$serverId")
            return@synchronized
        }
        val current = getCertInfos(serverId).toMutableList()
        if (current.any { it.sha256Hex == normalized }) {
            LogManager.i(TAG, "trustHash: hash already trusted for server=$serverId")
            return@synchronized
        }
        current.add(CertInfo(sha256Hex = normalized, issuedAt = System.currentTimeMillis()))
        while (current.size > MAX_HASHES) current.removeAt(0)
        persist(serverId, current)
        LogManager.i(TAG, "trustHash: stored hash for server=$serverId (total=${current.size})")
    }

    /**
     * Handle server-pushed cert hash rotation (OP_NEW_CERT_HASH, 0x07).
     *
     * The push arrives over an already-authenticated QUIC channel pinned to a
     * trusted cert, so the announced next hash is trusted immediately (kept
     * alongside the old one for the overlap window). This is what makes the
     * server's 2-day rotation overlap actually work on the client — previously
     * the new hash sat in PENDING forever and every rotation locked the client
     * out until a manual QR re-scan (Bug: "cert rotation reconnect lockout").
     *
     * Guard: only trust the push if the server already has at least one trusted
     * hash — a fresh, never-trusted server cannot be self-announced into trust,
     * so a MITM that merely relays a rogue 0x07 gains nothing.
     */
    fun rotateHash(serverId: String, nextHash: String) = synchronized(this) {
        val normalized = nextHash.lowercase().trim()
        if (!normalized.matches(HASH_PATTERN)) {
            LogManager.w(TAG, "rotateHash: malformed hash for server=$serverId")
            return@synchronized
        }
        val current = getCertInfos(serverId).toMutableList()
        if (current.isEmpty()) {
            LogManager.w(TAG, "rotateHash: no trusted baseline for server=$serverId — refusing to trust push")
            return@synchronized
        }
        if (current.any { it.sha256Hex == normalized }) {
            LogManager.d(TAG, "rotateHash: hash already trusted for server=$serverId, ignoring")
            return@synchronized
        }
        // Trusted baseline exists, so this push came over an authenticated
        // channel: trust the new hash now, keeping the old one for overlap.
        current.add(CertInfo(sha256Hex = normalized, issuedAt = System.currentTimeMillis()))
        while (current.size > MAX_HASHES) current.removeAt(0)
        persist(serverId, current)
        clearPending(serverId)
        LogManager.i(TAG, "rotateHash: trusted rotated hash for server=$serverId (total=${current.size})")
    }

    /** Promote pending hashes to trusted after user confirms via QR scan. */
    fun confirmPendingHashes(serverId: String) = synchronized(this) {
        val pending = loadPending(serverId)
        if (pending.isEmpty()) return@synchronized
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

    fun forget(serverId: String) = synchronized(this) {
        LogManager.i(TAG, "forget: removing cert for server=$serverId")
        storage.remove(keyFor(serverId))
        clearPending(serverId)
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
            result.filter { it.sha256Hex.matches(HASH_PATTERN) }
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
        private val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
