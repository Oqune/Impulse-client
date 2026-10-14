package com.example.impulse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
@Database(entities = [MessageEntity::class, PublicKeyEntity::class, SeenMessageNonceEntity::class], version = 6, exportSchema = false)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun publicKeyDao(): PublicKeyDao

    companion object {
        private const val DB_NAME = "impulse_messages.db"

        @Volatile
        private var INSTANCE: MessageDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN is_own INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `public_keys` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `server_id` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `kem_public_key` BLOB,
                        `dsa_public_key` BLOB,
                        `first_seen` INTEGER NOT NULL DEFAULT 0,
                        `last_seen` INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_public_keys_server_id` ON `public_keys` (`server_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_public_keys_server_id_fingerprint` ON `public_keys` (`server_id`, `fingerprint`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Conversation discriminator for DM isolation. All existing rows
                // become group messages. NOTE: no extra index here — Room
                // validates the table schema exactly; an index not declared in
                // MessageEntity would make the migration check fail.
                db.execSQL("ALTER TABLE messages ADD COLUMN conversation_id TEXT NOT NULL DEFAULT 'group'")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_server_id_conversation_id` ON `messages` (`server_id`, `conversation_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_timestamp` ON `messages` (`timestamp`)")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `seen_nonces` (`sender_fingerprint` TEXT NOT NULL, `nonce_hash` TEXT NOT NULL, `expires_at` INTEGER NOT NULL, PRIMARY KEY(`sender_fingerprint`, `nonce_hash`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_seen_nonces_expires_at` ON `seen_nonces` (`expires_at`)")
            }
        }

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
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
        }
    }
}
