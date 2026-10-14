package com.lazyneil.lazycar

import android.content.Context
import android.graphics.Bitmap

/** The accent is always taken from the album art; neutral near-white when there is no art. */
object Accent {
    private const val CYAN = 0xFFF2F2F2.toInt()
    private const val BG = 0xFF000000.toInt()

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
    fun forArt(ctx: Context, art: Bitmap?) = Contrast.lift(fromPhone ?: Ui.artColor(art) ?: CYAN, BG)   // >= 3:1 on the black background

    private var pushed = 0
    /** Call after art or fromPhone changes: home screen widgets follow the accent. Main thread or not. */
    fun publish(ctx: Context) {
        val c = now(ctx); if (c == pushed) return; pushed = c
        android.os.Handler(android.os.Looper.getMainLooper()).post { GoWidget.refresh(ctx); DriveWidget.refresh(ctx) }
    }

    /** Content on an accent fill: black or white, whichever has the higher WCAG contrast. */
    fun onColor(argb: Int): Int = Contrast.onColor(argb)
}
