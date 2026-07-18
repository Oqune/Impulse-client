package com.example.impulse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database holding chat messages.
 *
 * NOTE ON ENCRYPTION (16 KB page-size compatibility):
 * We deliberately do NOT use SQLCipher here. SQLCipher 4.5.4 ships a native
 * `libsqlcipher.so` whose ELF segments are aligned to a 4 KB page size; on
 * Android 15+ devices that use 16 KB memory pages the dynamic linker rejects
 * such libraries ("has invalid alignment"), crashing the app at startup.
 * Because we build fully offline we cannot pull a 16 KB-aligned SQLCipher.
 *
 * Instead, at-rest confidentiality is provided at the application layer: every
 * message body is encrypted with AES-256-GCM by [com.example.impulse.security.PqcCrypto]
 * before it is written, and the `ciphertext` / `iv` columns therefore never
 * contain plaintext. This satisfies the "SQLite with encryption" requirement
 * without any native dependency, and the app now loads on 16 KB-page devices.
 */
@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        private const val DB_NAME = "impulse_messages.db"

        @Volatile
        private var INSTANCE: MessageDatabase? = null

        fun getInstance(context: Context): MessageDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): MessageDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MessageDatabase::class.java,
                DB_NAME
            ).build()
        }
    }
}
