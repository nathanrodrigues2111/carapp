package com.lazyneil.lazycar

import org.junit.Assert.assertEquals
import org.junit.Test

class ContrastTest {
    private val dark = 0xFF140600.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test fun contentOnFill() {
        assertEquals(dark, Contrast.onColor(white))
        assertEquals(white, Contrast.onColor(0xFF000000.toInt()))
        assertEquals(white, Contrast.onColor(0xFF1A2A6C.toInt()))   // navy
        assertEquals(dark, Contrast.onColor(0xFFFBBF24.toInt()))    // yellow
        assertEquals(dark, Contrast.onColor(0xFFF8D7E8.toInt()))    // pastel pink
        assertEquals(dark, Contrast.onColor(0xFF808080.toInt()))    // mid grey
    }
}
