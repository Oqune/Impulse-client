package com.example.impulse.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Instrumented test for [TrustedCertManager]. Runs on-device via AndroidJUnit4
 * (the manager relies on the Android Keystore-backed SecureStorage).
 */
@RunWith(AndroidJUnit4::class)
class TrustedCertManagerTest {

    private lateinit var context: Context
    private lateinit var manager: TrustedCertManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = TrustedCertManager(context)
        TEST_SERVER_IDS.forEach(manager::forget)
    }

    @After
    fun tearDown() {
        TEST_SERVER_IDS.forEach(manager::forget)
    }

    @Test
    fun trustHash_storesAndReportsTrusted() {
        val id = "server_test_1"
        assertFalse(manager.isTrusted(id))
        manager.trustHash(id, HASH_A.uppercase())
        assertTrue(manager.isTrusted(id))
        assertEquals(listOf(HASH_A), manager.getHashes(id))
    }

    @Test
    fun trustHash_keepsMaxTwoHashes() {
        val id = "server_test_2"
        manager.trustHash(id, HASH_A)
        manager.trustHash(id, HASH_B)
        manager.trustHash(id, HASH_C) // should evict HASH_A
        val hashes = manager.getHashes(id)
        assertEquals(2, hashes.size)
        assertEquals(HASH_B, hashes[0])
        assertEquals(HASH_C, hashes[1])
    }

    @Test
    fun trustHash_doesNotDuplicate() {
        val id = "server_test_3"
        manager.trustHash(id, HASH_A)
        manager.trustHash(id, HASH_A)
        assertEquals(1, manager.getHashes(id).size)
    }

    @Test
    fun rotateHash_addsSecondSlot() {
        val id = "server_test_4"
        manager.trustHash(id, HASH_A)
        manager.rotateHash(id, HASH_B)
        val hashes = manager.getHashes(id)
        assertEquals(2, hashes.size)
        assertEquals(HASH_A, hashes[0])
        assertEquals(HASH_B, hashes[1])
    }

    @Test
    fun forget_clearsHashes() {
        val id = "server_test_5"
        manager.trustHash(id, HASH_A)
        manager.forget(id)
        assertFalse(manager.isTrusted(id))
    }

    companion object {
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private val TEST_SERVER_IDS = listOf(
            "server_test_1", "server_test_2", "server_test_3", "server_test_4", "server_test_5"
        )
    }
}
