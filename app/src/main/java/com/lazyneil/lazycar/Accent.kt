package com.lazyneil.lazycar

import android.content.Context

/** One accent, neutral near-white on the black background. */
object Accent {
    private const val C = 0xFFF2F2F2.toInt()
    @Suppress("UNUSED_PARAMETER") fun now(ctx: Context) = C
    @Suppress("UNUSED_PARAMETER") fun color(ctx: Context) = C
    /** Content on an accent fill: black or white, whichever has the higher WCAG contrast. */
    fun onColor(argb: Int): Int = Contrast.onColor(argb)
}
