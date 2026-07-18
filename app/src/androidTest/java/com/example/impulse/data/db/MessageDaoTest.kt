package com.example.impulse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.impulse.data.MessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit

/**
 * Instrumented test for the local message store.
 * Validates insert / upsert (dedup) / select / TTL purge behaviour of
 * [MessageDao] and [MessageRepository]. Runs on-device via AndroidJUnit4.
 */
@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: MessageDatabase
    private lateinit var dao: MessageDao
    private lateinit var repo: MessageRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MessageDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.messageDao()
        // Point the repository at our in-memory DB by building it directly.
        repo = MessageRepository(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsert_replacesOptimisticNegativeIdWithRealId() = runBlocking {
        val server = "srv_1"
        // Optimistic local copy under a negative temp id.
        dao.upsert(MessageEntity(serverId = server, serverMsgId = -123L,
            sender = "me", ciphertext = byteArrayOf(1), iv = byteArrayOf(), timestamp = 1000L))
        // Server echo arrives with the real (positive) id → must REPLACE, not duplicate.
        dao.upsert(MessageEntity(serverId = server, serverMsgId = 42L,
            sender = "me", ciphertext = byteArrayOf(2), iv = byteArrayOf(), timestamp = 1000L))

        val all = dao.loadForServer(server)
        assertEquals("optimistic + real must collapse to one row", 1, all.size)
        assertEquals(42L, all.first().serverMsgId)
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

        val removed = repo.purgeExpired()
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
}
