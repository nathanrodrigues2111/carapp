package com.lazyneil.lazycar

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils

/** Accent colour. Pref stores ARGB; 0 means Auto: taken from the album art, cyan without art. */
object Accent {
    const val AUTO = 0
    private const val CYAN = 0xFF00E5FF.toInt()

    /** The 8 fixed swatches, index-matched to the AccentOverlay styles. */
    val SWATCHES = listOf("Cyan" to CYAN, "Orange" to 0xFFFF7A45.toInt(), "Blue" to 0xFF7C9CFF.toInt(),
        "Purple" to 0xFFB388FF.toInt(), "Green" to 0xFF34D399.toInt(), "Yellow" to 0xFFFBBF24.toInt(),
        "Red" to 0xFFF87171.toInt(), "White" to 0xFFF5F5F7.toInt())
    private val OVERLAYS = intArrayOf(R.style.AccentOverlay_Cyan, R.style.AccentOverlay_Orange, R.style.AccentOverlay_Blue,
        R.style.AccentOverlay_Purple, R.style.AccentOverlay_Green, R.style.AccentOverlay_Yellow,
        R.style.AccentOverlay_Red, R.style.AccentOverlay_White)

    /** Current album art, app wide: PhoneMedia sets it on the tablet, MediaListener and the home screen on the phone. */
    @Volatile var art: Bitmap? = null

    /** The one live accent every screen paints with. */
    fun now(ctx: Context) = forArt(ctx, art)

    /** Fixed accent for places without art (widgets, Debug). */
    fun color(ctx: Context) = Prefs(ctx).accent.takeIf { it != AUTO } ?: CYAN

    /** Accent for a screen showing [art]: the chosen swatch, else the art colour, else cyan. */
    fun forArt(ctx: Context, art: Bitmap?) = Prefs(ctx).accent.takeIf { it != AUTO } ?: Ui.artColor(art) ?: CYAN

    /** colorPrimary follows a fixed swatch so Material widgets match. Call before setContentView. */
    fun apply(act: Activity) {
        val i = SWATCHES.indexOfFirst { it.second == Prefs(act).accent }
        if (i >= 0) act.theme.applyStyle(OVERLAYS[i], true)
    }

    /** Readable foreground for a given accent: near-black on light accents, white on dark ones. */
    fun onColor(argb: Int): Int =
        if (ColorUtils.calculateLuminance(argb) > 0.18) 0xFF140600.toInt() else Color.WHITE
}
