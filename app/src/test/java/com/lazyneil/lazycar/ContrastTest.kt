package com.lazyneil.lazycar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private val bg = 0xFF000000.toInt()
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

    @Test fun liftOnDark() {
        for (c in intArrayOf(0xFF000000.toInt(), 0xFF1A2A6C.toInt(), 0xFF3B2314.toInt(), 0xFF808080.toInt()))
            assertTrue("%08X".format(c), Contrast.ratio(Contrast.lift(c, bg), bg) >= 3.0)
        // Already fine: unchanged.
        for (c in intArrayOf(white, 0xFFFBBF24.toInt(), 0xFFF8D7E8.toInt())) assertEquals(c, Contrast.lift(c, bg))
        // Navy keeps its hue: blue stays the strongest channel.
        val n = Contrast.lift(0xFF1A2A6C.toInt(), bg)
        assertTrue((n and 255) > (n shr 16 and 255) && (n and 255) > (n shr 8 and 255))
    }
}
