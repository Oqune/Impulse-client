package com.example.impulse.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Self-contained encrypted key/value store backed by Android [KeyStore] and
 * AES-256-GCM. It replaces AndroidX `EncryptedSharedPreferences` (which pulls in
 * the Tink + Gson transitive dependencies) so the project can build fully
 * offline while keeping the same small, security-critical surface:
 *
 *  - Trusted server certificate hashes (TOFU / key-pinning).
 *  - The post-quantum ML-KEM key pair (private key never leaves the device).
 *
 * Design:
 *  - A master [SecretKey] is generated once in the Android Keystore
 *    (`AndroidKeyStore`) and never exported.
 *  - Each value is encrypted with a fresh random 12-byte IV using
 *    `AES/GCM/NoPadding` (128-bit tag). Stored format: `base64(iv || ciphertext)`.
 *  - Keys themselves are stored in plaintext inside the backing
 *    SharedPreferences file. For this app the keys are fixed, non-secret
 *    identifiers (see [KEY_PQ_PRIVATE], etc.), so this is acceptable; only the
 *    *values* (cert hashes, key material) are sensitive.
 */
class SecureStorage(context: Context) {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val masterKey = getOrCreateMasterKey()

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateMasterKey(): SecretKey {
        val alias = MASTER_KEY_ALIAS
        keyStore.getKey(alias, null)?.let { return it as SecretKey }
        val kg = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    fun putString(key: String, value: String) {
        val ct = encrypt(value.toByteArray(StandardCharsets.UTF_8))
        prefs.edit().putString(key, ct).apply()
    }

    fun getString(key: String, default: String = ""): String {
        val s = prefs.getString(key, null) ?: return default
        return try {
            String(decrypt(s), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            default
        }
    }

    fun putBytes(key: String, value: ByteArray) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    fun getBytes(key: String): ByteArray? {
        val s = prefs.getString(key, null) ?: return null
        return try {
            decrypt(s)
        } catch (e: Exception) {
            null
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** Encrypts [plain] -> base64(IV(12) || ciphertext). */
    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(AES_GCM)
        // Let the AndroidKeyStore generate the IV (required on API 31+, where
        // caller-provided IVs are rejected with "Caller-provided IV not permitted"
        // unless randomized encryption is explicitly disabled). The generated IV
        // is read back from cipher.iv and prepended to the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    /** Decrypts base64(IV(12) || ciphertext) -> plain. */
    private fun decrypt(b64: String): ByteArray {
        val data = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val iv = data.copyOfRange(0, GCM_IV_LEN)
        val ct = data.copyOfRange(GCM_IV_LEN, data.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    companion object {
        private const val FILE_NAME = "impulse_secure_storage"
        private const val MASTER_KEY_ALIAS = "impulse_secure_master_key"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128

        // Key names used across the app.
        const val KEY_PQ_PRIVATE = "pq_kem_private_key"
        const val KEY_PQ_PUBLIC = "pq_kem_public_key"
        const val KEY_MLDSA65_PRIVATE = "mldsa65_private_key"
        const val KEY_MLDSA65_PUBLIC = "mldsa65_public_key"

        // Aliases for SecureKeyManager (same underlying keys, clearer naming)
        const val KEY_KEM_PRIVATE = KEY_PQ_PRIVATE
        const val KEY_KEM_PUBLIC = KEY_PQ_PUBLIC
        const val KEY_DSA_PRIVATE = KEY_MLDSA65_PRIVATE
        const val KEY_DSA_PUBLIC = KEY_MLDSA65_PUBLIC
    }
}
