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

    /** Raise lightness, same hue and saturation, until [c] reaches [min] contrast on [bg]. */
    fun lift(c: Int, bg: Int, min: Double = 3.0): Int {
        val hsl = toHsl(c); var out = c
        while (ratio(out, bg) < min && hsl[2] < 1f) { hsl[2] = minOf(1f, hsl[2] + 0.02f); out = fromHsl(hsl) }
        return out
    }

    private fun toHsl(c: Int): FloatArray {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2f; val d = mx - mn
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - kotlin.math.abs(2f * l - 1f))
        val h = when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        return floatArrayOf((h + 360f) % 360f, s, l)
    }

    private fun fromHsl(hsl: FloatArray): Int {
        val (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s; val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f)); val m = l - c / 2f
        val (r, g, b) = when ((h / 60f).toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        fun v(f: Float) = ((f + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (v(r) shl 16) or (v(g) shl 8) or v(b)
    }
}
