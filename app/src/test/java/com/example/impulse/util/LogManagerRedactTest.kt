package com.example.impulse.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the defensive secret-redaction applied to every log record, so a
 * careless call-site can never persist a private key, password, ciphertext, or
 * full certificate hash to disk / logcat.
 */
class LogManagerRedactTest {

    @Test
    fun longHexRun_isMasked() {
        val hex = "a1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef12"
        assertTrue(hex.length >= 32)
        val out = LogManager.redactSecrets("ciphertext=$hex")
        assertFalse("raw hex must not appear in redacted output", out.contains(hex))
        // Either the hex-run mask or the key=value pair mask must have fired.
        assertTrue(
            "secret must be masked",
            out.contains("<hex-redacted>") || out.contains("<redacted>")
        )
    }

    @Test
    fun password_keyword_isMasked() {
        val out = LogManager.redactSecrets("login with password=hunter2 and token=abc123def456")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun privateKey_keyword_isMasked() {
        val out = LogManager.redactSecrets("private_key=-----BEGIN KEY-----....")
        assertFalse(out.contains("-----BEGIN KEY-----"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun shortHash_isNotMasked() {
        // 8-char cert hash fragment (as used by LogManager.shortHash) is safe and
        // must survive redaction for correlation.
        val out = LogManager.redactSecrets("cert short=a1b2c3d4 ok")
        assertTrue(out.contains("a1b2c3d4"))
        assertFalse(out.contains("<hex-redacted>"))
    }

    @Test
    fun benignMessage_unchanged() {
        val msg = "session ready, peers=3"
        assertEquals(msg, LogManager.redactSecrets(msg))
    }

    @Test
    fun ciphertext_keyword_isMasked() {
        val out = LogManager.redactSecrets("decrypt failed ciphertext=longbase64==")
        assertTrue(out.contains("<redacted>"))
    }
}
