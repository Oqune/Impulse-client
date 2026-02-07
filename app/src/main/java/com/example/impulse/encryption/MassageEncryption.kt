package com.example.impulse.encryption

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

class MassageEncryption {
    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"

        // Хэшируем ключ для получения 128-битного AES ключа
        private fun generateKey(key: String): SecretKeySpec {
            val sha = MessageDigest.getInstance("SHA-256")
            val keyBytes = sha.digest(key.toByteArray(Charsets.UTF_8))
            // Используем только первые 16 байтов для AES-128
            val secretKey = keyBytes.copyOf(16)
            return SecretKeySpec(secretKey, ALGORITHM)
        }

        fun encrypt(message: String, key: String): String {
            if (key.isEmpty()) return message

            try {
                val secretKey = generateKey(key)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val encryptedBytes = cipher.doFinal(message.toByteArray(Charsets.UTF_8))
                return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            } catch (e: Exception) {
                e.printStackTrace()
                return message // Возвращаем оригинальное сообщение в случае ошибки
            }
        }

        fun decrypt(encryptedMessage: String, key: String): String {
            if (key.isEmpty()) return encryptedMessage

            try {
                val secretKey = generateKey(key)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey)
                val decodedBytes = Base64.decode(encryptedMessage, Base64.NO_WRAP)
                val decryptedBytes = cipher.doFinal(decodedBytes)
                return String(decryptedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
                return encryptedMessage // Возвращаем зашифрованное сообщение в случае ошибки
            }
        }
    }
}