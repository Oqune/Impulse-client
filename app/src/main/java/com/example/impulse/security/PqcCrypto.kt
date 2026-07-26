package com.example.impulse.security

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation
import org.bouncycastle.jcajce.spec.KEMExtractSpec
import org.bouncycastle.jcajce.spec.KEMGenerateSpec
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import org.bouncycastle.pqc.jcajce.spec.DilithiumParameterSpec
import org.bouncycastle.pqc.jcajce.spec.KyberParameterSpec
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Post-quantum end-to-end encryption layer.
 *
 *  - ML-KEM-768 (the NIST-standardised Kyber parameter set) key generation,
 *    provided by BouncyCastle's **PQC** provider (`BouncyCastlePQCProvider`).
 *
 *    IMPORTANT: BouncyCastle ships its PQC algorithms (Kyber / ML-KEM) only in
 *    the Java 9+ multi-release entries of the `bcprov` jar. On Android's ART
 *    runtime (Java 17 desugaring) the algorithms are reachable **only** through
 *    the dedicated `BouncyCastlePQCProvider` instance — the generic
 *    `BouncyCastleProvider` does NOT register them. We therefore add
 *    `BouncyCastlePQCProvider` explicitly and always pass it as the provider.
 *
 *    Key generation uses algorithm **"Kyber"** with [KyberParameterSpec.kyber768].
 *
 *  - Each recipient gets a unique shared secret via ML-KEM encapsulation;
 *    the server only relays pubkeys, there is no shared group key.
 *
 *  - Sender authentication: every message is signed with **ML-DSA-65**
 *    (Dilithium3, NIST-standardised post-quantum signature) so receivers can
 *    verify the sender with PQ security.
 *
 *  - Message encryption: AES-256-GCM (authenticated, randomised IV per message).
 */
object PqcCrypto {

    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12 // bytes
    private const val GCM_TAG_LENGTH = 128 // bits
    private const val PQC_PROVIDER = "BCPQC"

    init {
        if (Security.getProvider(PQC_PROVIDER) == null) {
            Security.addProvider(BouncyCastlePQCProvider())
        }
    }

    /** A freshly generated ML-KEM-768 (Kyber-768) key pair, encoded for transport. */
    data class KeyPair(
        /** PKCS8-encoded private key (keep secret on device). */
        val privateEncoded: ByteArray,
        /** X509-encoded public key (safe to send to the server). */
        val publicEncoded: ByteArray
    )

    /** Generates a new ML-KEM-768 key pair. */
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Kyber", PQC_PROVIDER)
        kpg.initialize(KyberParameterSpec.kyber768, SecureRandom())
        val pair = kpg.generateKeyPair()
        return KeyPair(
            privateEncoded = pair.private.encoded,
            publicEncoded = pair.public.encoded
        )
    }

    /**
     * ML-KEM-768 encapsulation: generates a random shared secret and wraps it
     * for the recipient's public key. Returns (encapsulatedKey, sharedSecret).
     *
     * Uses the JCA KeyGenerator with KEMGenerateSpec per BouncyCastle 1.79 API.
     * The sharedSecret is 32 bytes, suitable as an AES-256-GCM key.
     */
    fun encapsulateKem(recipientPubKey: ByteArray): Pair<ByteArray, ByteArray> {
        val kf = KeyFactory.getInstance("Kyber", PQC_PROVIDER)
        val pub = kf.generatePublic(X509EncodedKeySpec(recipientPubKey))
        val kg = KeyGenerator.getInstance("Kyber", PQC_PROVIDER)
        kg.init(KEMGenerateSpec(pub, "AES"), SecureRandom())
        val sk = kg.generateKey() as SecretKeyWithEncapsulation
        val sharedSecret = sk.encoded.copyOf()
        val encapsulation = sk.encapsulation
        sk.encoded.fill(0)
        return Pair(encapsulation, sharedSecret)
    }

    /**
     * ML-KEM-768 decapsulation: unwraps the encapsulated key using our private key.
     * Returns the same 32-byte shared secret that the sender's encapsulate produced.
     */
    fun decapsulateKem(encapsulatedKey: ByteArray, privateEncoded: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("Kyber", PQC_PROVIDER)
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privateEncoded))
        val kg = KeyGenerator.getInstance("Kyber", PQC_PROVIDER)
        kg.init(KEMExtractSpec(priv, encapsulatedKey, "AES"), SecureRandom())
        val sk = kg.generateKey() as SecretKeyWithEncapsulation
        val sharedSecret = sk.encoded.copyOf()
        sk.encoded.fill(0)
        return sharedSecret
    }

    // ------------------------------------------------------------------
    // ML-DSA-65 (Dilithium3) signatures — NIST-standard post-quantum
    // sender authentication of the inner envelope
    // ------------------------------------------------------------------

    data class MlDsa65KeyPair(
        /** PKCS8-encoded private key (keep secret on device). */
        val privateEncoded: ByteArray,
        /** X509-encoded public key (sent via OP_KEY_EXCHANGE). */
        val publicEncoded: ByteArray
    )

    /** Generates an ML-DSA-65 signing key pair via BouncyCastle PQC. */
    fun generateMlDsa65KeyPair(): MlDsa65KeyPair {
        val kpg = KeyPairGenerator.getInstance("Dilithium", PQC_PROVIDER)
        kpg.initialize(DilithiumParameterSpec.dilithium3, SecureRandom())
        val pair = kpg.generateKeyPair()
        return MlDsa65KeyPair(pair.private.encoded, pair.public.encoded)
    }

    /** Signs [data] with the ML-DSA-65 private key (PKCS8-encoded). */
    fun signMlDsa65(privateEncoded: ByteArray, data: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("Dilithium", PQC_PROVIDER)
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privateEncoded))
        val sig = Signature.getInstance("Dilithium", PQC_PROVIDER)
        sig.initSign(priv)
        sig.update(data)
        return sig.sign()
    }

    /** Verifies an ML-DSA-65 [signature] over [data] using the public key (X509). */
    fun verifyMlDsa65(publicEncoded: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val kf = KeyFactory.getInstance("Dilithium", PQC_PROVIDER)
            val pub = kf.generatePublic(X509EncodedKeySpec(publicEncoded))
            val sig = Signature.getInstance("Dilithium", PQC_PROVIDER)
            sig.initVerify(pub)
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // AES-256-GCM message encryption
    // ------------------------------------------------------------------

    /** Encrypts [plaintext] with the 32-byte [key]; output = IV(12) || ciphertext. */
    fun aesEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val secretKey = toAesKey(key)
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    /** Decrypts data produced by [aesEncrypt]. */
    fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size > GCM_IV_LENGTH) { "Ciphertext too short" }
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val ct = data.copyOfRange(GCM_IV_LENGTH, data.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, toAesKey(key), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ct)
    }

    private fun toAesKey(key: ByteArray): SecretKey {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val k = digest.digest(key) // normalise to exactly 32 bytes
        return SecretKeySpec(k, "AES")
    }

}
