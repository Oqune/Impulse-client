package com.example.impulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayNonceTest {
    @Test
    fun storesOnlyFixedSizeDigestAndRetainsFutureSignedWindow() {
        val now = 1_000_000L
        val signedTimestamp = now + ReplayNonce.FRESHNESS_WINDOW_MS
        val marker = ReplayNonce.marker("sender", "nonce", signedTimestamp, now)!!

        assertEquals(64, marker.nonceHash.length)
        assertFalse(marker.nonceHash.contains("nonce"))
        assertEquals(signedTimestamp + ReplayNonce.FRESHNESS_WINDOW_MS + 1L, marker.expiresAt)
        assertTrue(marker.expiresAt > signedTimestamp + ReplayNonce.FRESHNESS_WINDOW_MS)
    }

    @Test
    fun emptyNonceHasNoReplayMarkerAndLongMinTimestampIsStale() {
        val now = System.currentTimeMillis()
        assertEquals(null, ReplayNonce.marker("sender", "", now, now))
        assertFalse(ReplayNonce.isTimestampFresh(Long.MIN_VALUE, now))
        assertTrue(ReplayNonce.isTimestampFresh(0L, now))
    }
}
