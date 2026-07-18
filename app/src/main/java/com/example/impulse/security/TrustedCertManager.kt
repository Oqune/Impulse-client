package com.example.impulse.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trust-On-First-Use (TOFU) manager for server certificate hashes.
 *
 * Behaviour required by the target architecture:
 *  - On first connection to a server there is no trusted hash. The caller must
 *    obtain the hash out-of-band (QR scan) and call [trustHash].
 *  - When connecting, the transport layer asks [getHashes] and pins them via
 *    WebTransport's `serverCertificateHashes`.
 *  - After a successful connection the server may push a *new* hash (a
 *    "rollover" / next-certificate). We store at most two hashes: the current
 *    one and the next one, so a seamless certificate rotation is possible
 *    without breaking existing clients.
 *
 * Hashes are stored as hex-encoded SHA-256 of the raw certificate (the same
 * format WebTransport expects). They live in [SecureStorage] (encrypted).
 */
class TrustedCertManager(context: Context) {

    private val storage = SecureStorage(context)

    /** Returns the list of trusted hashes (current + next) for a server. */
    fun getHashes(serverId: String): List<String> {
        val raw = storage.getString(keyFor(serverId))
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** True when at least one hash is already trusted for this server. */
    fun isTrusted(serverId: String): Boolean = getHashes(serverId).isNotEmpty()

    /**
     * Trusts [hash] for [serverId]. Implements the "max 2 hashes" rule:
     *  - If the hash is already present, nothing changes.
     *  - Otherwise it is appended. If we already had 2, the oldest is dropped.
     */
    fun trustHash(serverId: String, hash: String) {
        val normalized = hash.lowercase().trim()
        val current = getHashes(serverId).toMutableList()
        if (current.contains(normalized)) return
        current.add(normalized)
        while (current.size > MAX_HASHES) current.removeAt(0)
        persist(serverId, current)
    }

    /**
     * Called when the server advertises a *next* certificate hash. Keeps the
     * existing current hash and stores the new one as the second slot.
     */
    fun rotateHash(serverId: String, nextHash: String) {
        val normalized = nextHash.lowercase().trim()
        val current = getHashes(serverId).toMutableList()
        if (current.contains(normalized)) return
        // current stays first, next becomes second
        val updated = mutableListOf<String>()
        updated.add(current.firstOrNull() ?: normalized)
        if (current.firstOrNull() != null && current.firstOrNull() != normalized) {
            updated.add(normalized)
        }
        while (updated.size > MAX_HASHES) updated.removeAt(0)
        persist(serverId, updated)
    }

    /** Removes all trusted hashes for a server (e.g. "forget server"). */
    fun forget(serverId: String) {
        storage.remove(keyFor(serverId))
    }

    private fun persist(serverId: String, hashes: List<String>) {
        val arr = JSONArray()
        hashes.forEach { arr.put(it) }
        storage.putString(keyFor(serverId), arr.toString())
    }

    private fun keyFor(serverId: String) = "cert_hashes::$serverId"

    companion object {
        const val MAX_HASHES = 2
    }
}
