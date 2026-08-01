package com.example.impulse.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPreviewPickerTest {

    private val sizes = listOf(
        3840 to 2160, // 4K
        1920 to 1080, // 1080p
        1280 to 720,  // 720p
        640 to 360,
    )

    @Test
    fun capsToMaxDimensionOnLongSide() {
        val pick = pickPreviewSize(sizes, 16f / 9f)!!
        assertEquals(1280, pick.first)
        assertEquals(720, pick.second)
    }

    @Test
    fun allOversizedFallsBackToLargest() {
        val pick = pickPreviewSize(listOf(3840 to 2160, 2560 to 1440), 16f / 9f)!!
        assertEquals(3840, pick.first)
    }

    @Test
    fun ratioMismatchStillPicksLargestCapped() {
        val pick = pickPreviewSize(sizes, 4f / 3f)!!
        assertTrue(pick.first <= 1280)
        assertNotNull(pick)
    }

    @Test
    fun emptyInputReturnsNull() {
        assertEquals(null, pickPreviewSize(emptyList(), 16f / 9f))
    }
}
