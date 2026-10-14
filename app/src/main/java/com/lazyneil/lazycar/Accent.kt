package com.lazyneil.lazycar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils

/** The accent is always taken from the album art; cyan (the token default) when there is no art. */
object Accent {
    private const val CYAN = 0xFF00E5FF.toInt()

    /** Current album art, app wide: PhoneMedia sets it on the tablet, MediaListener and the home screen on the phone. */
    @Volatile var art: Bitmap? = null
    /** The phone's accent for the current track, sent with Now Playing, so both devices show the same color. */
    @Volatile var fromPhone: Int? = null

    /** The one live accent every screen paints with. */
    fun now(ctx: Context) = forArt(ctx, art)

    /** Places without art (widgets, Debug). */
    fun color(ctx: Context) = now(ctx)

    /** Accent for [art]: the phone's value when it sent one, else extracted here, else cyan. */
    @Suppress("UNUSED_PARAMETER")
    fun forArt(ctx: Context, art: Bitmap?) = fromPhone ?: Ui.artColor(art) ?: CYAN

    /** Readable foreground for a given accent: near-black on light accents, white on dark ones. */
    fun onColor(argb: Int): Int =
        if (ColorUtils.calculateLuminance(argb) > 0.18) 0xFF140600.toInt() else Color.WHITE
}
