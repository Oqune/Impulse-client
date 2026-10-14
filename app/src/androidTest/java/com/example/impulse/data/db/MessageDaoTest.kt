package com.example.impulse.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.UUID
import com.example.impulse.data.ReplayNonce

/**
 * Instrumented test for the local message store.
 * Validates insert / upsert / select / TTL purge behaviour of the isolated
 * in-memory [MessageDao]. Runs on-device via AndroidJUnit4.
 */
@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: MessageDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MessageDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsert_keepsDistinctServerMessageIdsAsDistinctRows() = runBlocking {
        val server = "srv_1"
        // The declared unique key is (server_id, server_msg_id), so a temporary
        // negative ID and the relay's positive ID are separate DAO rows.
        dao.upsert(MessageEntity(serverId = server, serverMsgId = -123L,
            sender = "me", ciphertext = byteArrayOf(1), iv = byteArrayOf(), timestamp = 1000L))
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 42L,
            sender = "me", ciphertext = byteArrayOf(2), iv = byteArrayOf(), timestamp = 1000L))

        val all = dao.loadForServer(server)
        assertEquals(2, all.size)
        assertEquals(listOf(-123L, 42L), all.map { it.serverMsgId })
    }

    @Test
    fun upsert_replacesRowWithSameServerMessageId() = runBlocking {
        val server = "srv_replace"
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 42L, sender = "me",
            ciphertext = byteArrayOf(1), iv = byteArrayOf(), timestamp = 1000L))
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 42L, sender = "me",
            ciphertext = byteArrayOf(2), iv = byteArrayOf(), timestamp = 2000L))

        val all = dao.loadForServer(server)
        assertEquals(1, all.size)
        assertEquals(42L, all.single().serverMsgId)
        assertTrue(all.single().ciphertext.contentEquals(byteArrayOf(2)))
    }

    @Test
    fun observeForServer_returnsOrderedById() = runBlocking {
        val server = "srv_2"
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 10L, sender = "a",
            ciphertext = byteArrayOf(1), iv = byteArrayOf(), timestamp = 1L))
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 5L, sender = "b",
            ciphertext = byteArrayOf(2), iv = byteArrayOf(), timestamp = 2L))
        val all = dao.loadForServer(server)
        assertEquals(listOf(5L, 10L), all.map { it.serverMsgId })
    }

    @Test
    fun purgeExpired_removesOnlyOldRows() = runBlocking {
        val server = "srv_3"
        val now = System.currentTimeMillis()
        val oldTs = now - TimeUnit.HOURS.toMillis(73) // > 72h TTL
        val newTs = now - TimeUnit.HOURS.toMillis(1)
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 1L, sender = "a",
            ciphertext = byteArrayOf(1), iv = byteArrayOf(), timestamp = oldTs))
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 2L, sender = "b",
            ciphertext = byteArrayOf(2), iv = byteArrayOf(), timestamp = newTs))

        val removed = dao.deleteOlderThan(now - TimeUnit.HOURS.toMillis(72L))
        assertEquals("exactly one row should be purged", 1, removed)
        val remaining = dao.loadForServer(server)
        assertEquals(1, remaining.size)
        assertEquals(2L, remaining.first().serverMsgId)
    }

    @Test
    fun maxServerMsgId_returnsHighest() = runBlocking {
        val server = "srv_4"
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 3L, sender = "a",
            ciphertext = byteArrayOf(1), iv = byteArrayOf(), timestamp = 1L))
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 9L, sender = "b",
            ciphertext = byteArrayOf(2), iv = byteArrayOf(), timestamp = 2L))
        assertEquals(9L, dao.maxServerMsgId(server))
    }

    @Test
    fun clearServer_removesAllForServer() = runBlocking {
        val server = "srv_5"
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 1L, sender = "a",
            ciphertext = byteArrayOf(1), iv = byteArrayOf(), timestamp = 1L))
        dao.clearServer(server)
        assertTrue(dao.loadForServer(server).isEmpty())
    }

    @Test
    fun replayJournalRejectsSameSenderNonceAcrossServerAndMessageIds() = runBlocking {
        val now = System.currentTimeMillis()
        val firstMarker = ReplayNonce.marker("sender-fp", "nonce-a", now, now)!!
        val first = dao.upsertWithReplayGuard(message("server-a", 10L), firstMarker, now, 250_000)
        val replay = dao.upsertWithReplayGuard(
            message("server-b", 99L), firstMarker.copy(expiresAt = now + ReplayNonce.FRESHNESS_WINDOW_MS), now, 250_000
        )
        val distinct = dao.upsertWithReplayGuard(
            message("server-b", 100L), ReplayNonce.marker("sender-fp", "nonce-b", now, now)!!, now, 250_000
        )

        assertEquals(ReplayPersistenceResult.ACCEPTED, first)
        assertEquals(ReplayPersistenceResult.DUPLICATE, replay)
        assertEquals(ReplayPersistenceResult.ACCEPTED, distinct)
        assertEquals(listOf(10L), dao.loadForServer("server-a").map { it.serverMsgId })
        assertEquals(listOf(100L), dao.loadForServer("server-b").map { it.serverMsgId })
        dao.clearServer("server-a")
        assertTrue(dao.hasReplayMarker(firstMarker.senderFingerprint, firstMarker.nonceHash))
    }

    @Test
    fun expiredMarkerCanBeAcceptedAgain() = runBlocking {
        val now = System.currentTimeMillis()
        val marker = ReplayNonce.marker("sender-fp", "nonce-expire", now, now)!!
        dao.insertReplayMarker(marker.copy(expiresAt = now - 1))

        assertEquals(ReplayPersistenceResult.ACCEPTED,
            dao.upsertWithReplayGuard(message("server", 1), marker, now, 250_000))
        assertTrue(dao.hasReplayMarker(marker.senderFingerprint, marker.nonceHash))
    }

    @Test
    fun journalCapacityFailsClosedWithoutReplacingExistingMarker() = runBlocking {
        val now = System.currentTimeMillis()
        val existing = ReplayNonce.marker("sender", "existing", now, now)!!
        dao.insertReplayMarker(existing)
        val rejected = dao.upsertWithReplayGuard(
            message("server", 1), ReplayNonce.marker("sender", "new", now, now)!!, now, 1
        )
        assertEquals(ReplayPersistenceResult.CAPACITY_REACHED, rejected)
        assertTrue(dao.hasReplayMarker(existing.senderFingerprint, existing.nonceHash))
        assertEquals(0, dao.loadForServer("server").size)
    }

    @Test
    fun messageWriteFailureRollsBackReplayMarker() = runBlocking {
        val now = System.currentTimeMillis()
        val marker = ReplayNonce.marker("sender", "rollback", now, now)!!
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_message_insert BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )

        assertThrows(Exception::class.java) {
            runBlocking { dao.upsertWithReplayGuard(message("server", 1), marker, now, 250_000) }
        }
        assertFalse(dao.hasReplayMarker(marker.senderFingerprint, marker.nonceHash))
        assertTrue(dao.loadForServer("server").isEmpty())
    }

    @Test
    fun migrationFromV5PreservesRowsAndReplayMarkerSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "replay-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        createV5Fixture(file)

        val migrated = Room.databaseBuilder(context, MessageDatabase::class.java, name)
            .addMigrations(MessageDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        val migratedDao = migrated.messageDao()
        assertEquals(listOf(77L), migratedDao.loadForServer("legacy-server").map { it.serverMsgId })
        val now = System.currentTimeMillis()
        val marker = ReplayNonce.marker("legacy-sender", "after-migration", now, now)!!
        assertEquals(ReplayPersistenceResult.ACCEPTED,
            migratedDao.upsertWithReplayGuard(message("new-server", 1), marker, now, 250_000))
        migrated.close()

        val reopened = Room.databaseBuilder(context, MessageDatabase::class.java, name)
            .addMigrations(MessageDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(listOf(77L), reopened.messageDao().loadForServer("legacy-server").map { it.serverMsgId })
            assertEquals(ReplayPersistenceResult.DUPLICATE,
                reopened.messageDao().upsertWithReplayGuard(message("other-server", 8), marker, now, 250_000))
            assertTrue(reopened.messageDao().loadForServer("other-server").isEmpty())
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    private fun message(server: String, id: Long) = MessageEntity(
        serverId = server, serverMsgId = id, sender = "sender", ciphertext = byteArrayOf(1),
        iv = byteArrayOf(), timestamp = System.currentTimeMillis()
    )

    private fun createV5Fixture(file: File) {
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        legacy.execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `server_id` TEXT NOT NULL, `server_msg_id` INTEGER NOT NULL, `sender` TEXT NOT NULL, `ciphertext` BLOB NOT NULL, `iv` BLOB NOT NULL, `timestamp` INTEGER NOT NULL, `is_own` INTEGER NOT NULL DEFAULT 0, `conversation_id` TEXT NOT NULL DEFAULT 'group')")
        legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_server_id` ON `messages` (`server_id`)")
        legacy.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_server_id_server_msg_id` ON `messages` (`server_id`, `server_msg_id`)")
        legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_server_id_conversation_id` ON `messages` (`server_id`, `conversation_id`)")
        legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_timestamp` ON `messages` (`timestamp`)")
        legacy.execSQL("CREATE TABLE IF NOT EXISTS `public_keys` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `server_id` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `kem_public_key` BLOB, `dsa_public_key` BLOB, `first_seen` INTEGER NOT NULL, `last_seen` INTEGER NOT NULL)")
        legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_public_keys_server_id` ON `public_keys` (`server_id`)")
        legacy.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_public_keys_server_id_fingerprint` ON `public_keys` (`server_id`, `fingerprint`)")
        legacy.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        legacy.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '8cf29e28850571745a956484933a1ec3')")
        legacy.execSQL("INSERT INTO messages (server_id, server_msg_id, sender, ciphertext, iv, timestamp, is_own, conversation_id) VALUES ('legacy-server', 77, 'legacy', X'01', X'', 1234, 0, 'group')")
        legacy.version = 5
        legacy.close()
    }
}
