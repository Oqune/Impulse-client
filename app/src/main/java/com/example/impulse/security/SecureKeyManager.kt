package com.example.impulse.security

import android.content.Context
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecureKeyManager {

    private var kemKeyPair: PqcCrypto.KeyPair? = null
    private var dsaKeyPair: PqcCrypto.MlDsa65KeyPair? = null

    private const val PBKDF2_ITERATIONS = 100_000
    private const val AES_KEY_LENGTH = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 16
    private const val BACKUP_VERSION: Byte = 0x02
    private const val BACKUP_FILENAME = "key_backup.enc"

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
        kemKeyPair?.privateEncoded?.fill(0)
        kemKeyPair?.publicEncoded?.fill(0)
        dsaKeyPair?.privateEncoded?.fill(0)
        dsaKeyPair?.publicEncoded?.fill(0)
        kemKeyPair = null
        dsaKeyPair = null
    }

    fun exportKeyBackup(context: Context): String {
        val password = generatePassword(8)
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }

        val secure = SecureStorage(context)
        val kemPriv = secure.getBytes(SecureStorage.KEY_KEM_PRIVATE)
            ?: throw IllegalStateException("No ML-KEM private key to export")
        val kemPub = secure.getBytes(SecureStorage.KEY_KEM_PUBLIC)
            ?: throw IllegalStateException("No ML-KEM public key to export")

        val aesKey = pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_LENGTH)
        val encrypted = aesGcmEncrypt(aesKey, iv, kemPriv)

        val bytes = ByteArrayOutputStream()
        bytes.write(BACKUP_VERSION.toInt())
        bytes.write(salt)
        bytes.write(iv)
        bytes.write(intToLittleEndian(encrypted.size))
        bytes.write(encrypted)
        bytes.write(intToLittleEndian(kemPub.size))
        bytes.write(kemPub)

        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILENAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create backup file")
        resolver.openOutputStream(uri)?.use { it.write(bytes.toByteArray()) }
            ?: throw IOException("Failed to write backup file")

        kemPriv.fill(0)
        kemPub.fill(0)
        encrypted.fill(0)
        aesKey.fill(0)

        return password
    }

    fun importKeyBackup(context: Context, file: android.net.Uri, password: String): Boolean {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(file)?.use { it.readBytes() }
            ?: throw IOException("Failed to read backup file")

        require(bytes.size > 33) { "Backup file too small" }
        val version = bytes[0]
        require(version == BACKUP_VERSION || version == 0x01.toByte()) {
            "Unsupported backup version: $version"
        }

        val salt = bytes.copyOfRange(1, 1 + SALT_LENGTH)
        val iv = bytes.copyOfRange(1 + SALT_LENGTH, 1 + SALT_LENGTH + GCM_IV_LENGTH)
        val encKeyLenOffset = 1 + SALT_LENGTH + GCM_IV_LENGTH
        val encKeyLen = intFromLittleEndian(bytes, encKeyLenOffset)
        val encKeyDataEnd = encKeyLenOffset + 4 + encKeyLen
        val encrypted = bytes.copyOfRange(encKeyLenOffset + 4, encKeyDataEnd)

        val aesKey = pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_LENGTH)
        val kemPriv = aesGcmDecrypt(aesKey, iv, encrypted)

        val secure = SecureStorage(context)

        val kemPub: ByteArray
        if (version >= 0x02.toByte() && encKeyDataEnd + 4 <= bytes.size) {
            val pubLen = intFromLittleEndian(bytes, encKeyDataEnd)
            kemPub = bytes.copyOfRange(encKeyDataEnd + 4, encKeyDataEnd + 4 + pubLen)
        } else {
            val fresh = PqcCrypto.generateKeyPair()
            kemPub = fresh.publicEncoded
        }

        secure.putBytes(SecureStorage.KEY_KEM_PRIVATE, kemPriv)
        secure.putBytes(SecureStorage.KEY_KEM_PUBLIC, kemPub)
        kemKeyPair = PqcCrypto.KeyPair(kemPriv, kemPub)

        val dsa = PqcCrypto.generateMlDsa65KeyPair()
        secure.putBytes(SecureStorage.KEY_DSA_PRIVATE, dsa.privateEncoded)
        secure.putBytes(SecureStorage.KEY_DSA_PUBLIC, dsa.publicEncoded)
        dsaKeyPair = dsa

        resolver.delete(file, null, null)

        kemPriv.fill(0)
        encrypted.fill(0)
        aesKey.fill(0)

        return true
    }

    private fun generatePassword(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    internal fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBits: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        val secret = factory.generateSecret(spec)
        spec.clearPassword()
        return secret.encoded
    }

    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(data)
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(data)
    }

    internal fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
    )

    internal fun intFromLittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
