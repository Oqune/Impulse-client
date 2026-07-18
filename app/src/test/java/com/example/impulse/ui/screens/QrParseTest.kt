package com.example.impulse.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [parseCertHash] — the TOFU QR payload parser.
 *
 * The client accepts two strict forms (see [parseCertHash] docs):
 *   - `impulse-cert:<64-hex>`          (primary pinning form)
 *   - `impulse-tofu|<64-hex>|<ts>`     (server TUI form; timestamp ignored)
 *
 * In all accepted cases the returned value is exactly the 64-char lowercase
 * hex fingerprint, so downstream cert storage/comparison is identical.
 */
class QrParseTest {

    // Exactly 64 hex characters (32 bytes).
    private val fp = "a1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef"

    @Test
    fun impulseCert_prefix_accepted() {
        assertEquals(fp, parseCertHash("impulse-cert:$fp"))
    }

    @Test
    fun impulseCert_caseInsensitivePrefix_accepted() {
        assertEquals(fp, parseCertHash("IMPULSE-CERT:$fp"))
        assertEquals(fp, parseCertHash("Impulse-Cert:$fp"))
    }

    @Test
    fun impulseCert_surroundingWhitespace_trimmed() {
        assertEquals(fp, parseCertHash("  impulse-cert:$fp  \n"))
    }

    @Test
    fun impulseTofu_form_accepted_andTimestampIgnored() {
        val qr = "impulse-tofu|$fp|1720000000"
        assertEquals(fp, parseCertHash(qr))
    }

    @Test
    fun impulseTofu_caseInsensitivePrefix_accepted() {
        val qr = "IMPULSE-TOFU|$fp|1720000000"
        assertEquals(fp, parseCertHash(qr))
    }

    @Test
    fun impulseTofu_uppercaseHex_normalizedToLowercase() {
        val upper = fp.uppercase()
        val qr = "impulse-tofu|$upper|1720000000"
        assertEquals(fp, parseCertHash(qr))
    }

    @Test
    fun bare64Hex_rejected() {
        // Security audit: a bare 64-hex token with no recognized prefix is NOT
        // accepted, otherwise a malformed/malicious QR could pin the wrong cert.
        assertNull(parseCertHash(fp))
    }

    @Test
    fun wrongLengthHex_rejected() {
        val short = fp.substring(0, 60)
        assertNull(parseCertHash("impulse-cert:$short"))
        assertNull(parseCertHash("impulse-tofu|$short|1720000000"))
    }

    @Test
    fun nonHexChars_rejected() {
        val bad = "z1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef12"
        assertNull(parseCertHash("impulse-cert:$bad"))
        assertNull(parseCertHash("impulse-tofu|$bad|1720000000"))
    }

    @Test
    fun unknownPrefix_rejected() {
        assertNull(parseCertHash("impulse-x:$fp"))
        assertNull(parseCertHash("https://example.com/$fp"))
    }

    @Test
    fun emptyAndGarbage_rejected() {
        assertNull(parseCertHash(""))
        assertNull(parseCertHash("hello"))
        assertNull(parseCertHash("impulse-tofu|nothex|abc"))
    }
}
