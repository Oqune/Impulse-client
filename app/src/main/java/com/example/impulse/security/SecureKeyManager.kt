package com.example.impulse.security

import android.content.Context
import java.security.MessageDigest

object SecureKeyManager {

    private var kemKeyPair: PqcCrypto.KeyPair? = null
    private var dsaKeyPair: PqcCrypto.MlDsa65KeyPair? = null

    fun ensureKeyPair(context: Context) {
        val secure = SecureStorage(context)
        val kemPriv = secure.getBytes(SecureStorage.KEY_KEM_PRIVATE)
        val kemPub = secure.getBytes(SecureStorage.KEY_KEM_PUBLIC)
        if (kemPriv != null && kemPub != null) {
            kemKeyPair = PqcCrypto.KeyPair(kemPriv, kemPub)
        } else {
            val kp = PqcCrypto.generateKeyPair()
            kemKeyPair = kp
            secure.putBytes(SecureStorage.KEY_KEM_PRIVATE, kp.privateEncoded)
            secure.putBytes(SecureStorage.KEY_KEM_PUBLIC, kp.publicEncoded)
        }
        val dsaPriv = secure.getBytes(SecureStorage.KEY_DSA_PRIVATE)
        val dsaPub = secure.getBytes(SecureStorage.KEY_DSA_PUBLIC)
        if (dsaPriv != null && dsaPub != null) {
            dsaKeyPair = PqcCrypto.MlDsa65KeyPair(dsaPriv, dsaPub)
        } else {
            val dsa = PqcCrypto.generateMlDsa65KeyPair()
            dsaKeyPair = dsa
            secure.putBytes(SecureStorage.KEY_DSA_PRIVATE, dsa.privateEncoded)
            secure.putBytes(SecureStorage.KEY_DSA_PUBLIC, dsa.publicEncoded)
        }
    }

    fun getKemPublicKey(): ByteArray = kemKeyPair?.publicEncoded
        ?: throw IllegalStateException("Key pair not generated")

    fun getKemPrivateKey(): ByteArray = kemKeyPair?.privateEncoded
        ?: throw IllegalStateException("Key pair not generated")

    fun getDsaPublicKey(): ByteArray = dsaKeyPair?.publicEncoded
        ?: throw IllegalStateException("DSA key pair not generated")

    fun getFingerprint(): String {
        val pub = getKemPublicKey()
        return fingerprintForBytes(pub)
    }

    fun fingerprintForBytes(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun encapsulateKem(recipientPubKey: ByteArray): Pair<ByteArray, ByteArray> =
        PqcCrypto.encapsulateKem(recipientPubKey)

    fun decapsulateKem(encapsulatedKey: ByteArray): ByteArray =
        PqcCrypto.decapsulateKem(encapsulatedKey, getKemPrivateKey())

    fun signDsa(data: ByteArray): ByteArray {
        val priv = dsaKeyPair?.privateEncoded
            ?: throw IllegalStateException("DSA key pair not generated")
        return PqcCrypto.signMlDsa65(priv, data)
    }

    fun verifyDsa(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        PqcCrypto.verifyMlDsa65(publicKey, data, signature)

    fun reset() {
        kemKeyPair = null
        dsaKeyPair = null
    }
}
