package com.example.impulse.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Instrumented test for [TrustedCertManager]. Runs on-device via AndroidJUnit4
 * (the manager relies on the Android Keystore / EncryptedSharedPreferences).
 */
@RunWith(AndroidJUnit4::class)
class TrustedCertManagerTest {

    private lateinit var context: Context
    private lateinit var manager: TrustedCertManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = TrustedCertManager(context)
    }

    @Test
    fun trustHash_storesAndReportsTrusted() {
        val id = "server_test_1"
        assertFalse(manager.isTrusted(id))
        manager.trustHash(id, "ABCDEF0123456789")
        assertTrue(manager.isTrusted(id))
        assertEquals(listOf("abcdef0123456789"), manager.getHashes(id))
    }

    @Test
    fun trustHash_keepsMaxTwoHashes() {
        val id = "server_test_2"
        manager.trustHash(id, "AAAA")
        manager.trustHash(id, "BBBB")
        manager.trustHash(id, "CCCC") // should evict AAAA
        val hashes = manager.getHashes(id)
        assertEquals(2, hashes.size)
        assertEquals("bbbb", hashes[0])
        assertEquals("cccc", hashes[1])
    }

    @Test
    fun trustHash_doesNotDuplicate() {
        val id = "server_test_3"
        manager.trustHash(id, "XYZ")
        manager.trustHash(id, "XYZ")
        assertEquals(1, manager.getHashes(id).size)
    }

    @Test
    fun rotateHash_addsSecondSlot() {
        val id = "server_test_4"
        manager.trustHash(id, "CURR")
        manager.rotateHash(id, "NEXT")
        val hashes = manager.getHashes(id)
        assertEquals(2, hashes.size)
        assertEquals("curr", hashes[0])
        assertEquals("next", hashes[1])
    }

    @Test
    fun forget_clearsHashes() {
        val id = "server_test_5"
        manager.trustHash(id, "ZZZ")
        manager.forget(id)
        assertFalse(manager.isTrusted(id))
    }
}
