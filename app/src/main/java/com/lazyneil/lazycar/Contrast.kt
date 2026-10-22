package com.lazyneil.lazycar

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG contrast math on ARGB ints. Pure Kotlin, no Android, so it runs in JVM tests. */
object Contrast {
    private fun ch(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }

    fun luminance(argb: Int) = 0.2126 * ch(argb shr 16 and 255) + 0.7152 * ch(argb shr 8 and 255) + 0.0722 * ch(argb and 255)

    fun ratio(a: Int, b: Int): Double { val x = luminance(a); val y = luminance(b); return (max(x, y) + 0.05) / (min(x, y) + 0.05) }

    /** Black or white, whichever contrasts more with [fill]. Black is the warm near-black token. */
    fun onColor(fill: Int): Int { val dark = 0xFF140600.toInt(); val light = 0xFFFFFFFF.toInt()
        return if (ratio(fill, dark) >= ratio(fill, light)) dark else light }
}
