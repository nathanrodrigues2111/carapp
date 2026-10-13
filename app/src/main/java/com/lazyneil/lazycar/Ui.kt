package com.lazyneil.lazycar

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.AnimatedVectorDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.lazyneil.lazycar.audio.StreamReceiver
import com.lazyneil.lazycar.audio.StreamSender
import com.lazyneil.lazycar.audio.TabletSpeaker
import com.lazyneil.lazycar.media.MediaListener
import com.lazyneil.lazycar.media.PhoneMedia
import com.lazyneil.lazycar.media.Volumes

/** Themes, layouts, art accent, motion and the shared Outputs block for the home screens. */
object Ui {
    /** Before super.onCreate. One theme: Midnight AMOLED, accent from the album art. */
    fun apply(a: AppCompatActivity) {
        a.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        a.theme.applyStyle(R.style.CarTheme_Midnight, true)
    }

    private fun sp(c: Context) = c.getSharedPreferences("lazycar", Context.MODE_PRIVATE)
    fun openOnBoot(c: Context) = sp(c).getBoolean("openOnBoot", true)
    fun openOnGo(c: Context) = sp(c).getBoolean("openOnGo", true)
    fun openOnLaunch(c: Context) = sp(c).getBoolean("openOnLaunch", true)
    /** Tablet player style: Modern (default) or Classic, the earlier layout. */
    fun classic(c: Context) = sp(c).getBoolean("classicPlayer", false)
    fun setOpen(c: Context, key: String, on: Boolean) = sp(c).edit().putBoolean(key, on).apply()

    fun attr(c: Context, id: Int): Int {
        val ta = c.obtainStyledAttributes(intArrayOf(id)); val v = ta.getColor(0, Color.WHITE); ta.recycle(); return v
    }
    fun themeAccent(c: Context) = attr(c, R.attr.carAccent)

    /** Vivid accent from album art: the most saturated hue bucket, pushed bright. Null for grey art. */
    fun artColor(b: Bitmap?): Int? = try {
        b?.let { src ->
            val s = Bitmap.createScaledBitmap(src, 48, 48, true)
            val hsv = FloatArray(3); val w = FloatArray(12)
            for (x in 0 until 48) for (y in 0 until 48) {
                Color.colorToHSV(s.getPixel(x, y), hsv)
                if (hsv[1] >= 0.25f && hsv[2] >= 0.3f) w[(hsv[0] / 30).toInt() % 12] += hsv[1] * hsv[2]
            }
            val best = w.indices.maxBy { w[it] }
            if (w[best] < 2f) null else Color.HSVToColor(floatArrayOf(best * 30f + 15f, 0.7f, 1f))
        }
    } catch (_: Exception) { null }

    /** Accent on the seek bar, sliders, play button (with glow) and meter, wherever they exist in [root]. */
    fun paintAccent(root: View, c: Int) {
        val csl = ColorStateList.valueOf(c)
        val on = ColorStateList.valueOf(Accent.onColor(c))
        intArrayOf(R.id.outJblSlider, R.id.outTabSlider).forEach {
            root.findViewById<Slider>(it)?.apply { trackActiveTintList = csl; thumbTintList = csl }
        }
        intArrayOf(R.id.npPlay).forEach {
            root.findViewById<ImageButton>(it)?.apply {
                backgroundTintList = csl; imageTintList = on; outlineSpotShadowColor = c; outlineAmbientShadowColor = c
            }
        }
    }

    /** Segmented pill: one rounded track, the picked option a soft accent pill with accent text. */
    fun segmented(row: android.widget.LinearLayout, options: List<String>, selected: Int, pick: (Int) -> Unit) {
        val c = row.context; val d = c.resources.displayMetrics.density
        val accent = attr(c, R.attr.carAccent); val dim = attr(c, R.attr.carTextDim)
        fun pill(color: Int) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 999f * d; setColor(color) }
        row.removeAllViews(); row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.background = pill(attr(c, R.attr.carSurfaceHigh)); row.setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        var cur = selected
        fun paint() = (0 until row.childCount).forEach { i -> (row.getChildAt(i) as TextView).apply {
            background = if (i == cur) pill(ColorUtils.setAlphaComponent(accent, 0x33)) else null
            setTextColor(if (i == cur) accent else dim) } }
        options.forEachIndexed { i, label ->
            row.addView(TextView(c).apply {
                text = label; setTextAppearance(R.style.T_Body); paint.isFakeBoldText = true; gravity = android.view.Gravity.CENTER
                setOnClickListener { if (cur != i) { cur = i; paint(); pick(i) } }
            }, android.widget.LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f))
        }
        paint()
    }

    fun blur(iv: ImageView?) { iv?.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP)) }

    /** Crossfade to a new bitmap. */
    fun swapArt(iv: ImageView?, b: Bitmap?) {
        iv ?: return
        iv.animate().cancel()
        iv.animate().alpha(0f).setDuration(140).withEndAction {
            iv.setImageBitmap(b); iv.animate().alpha(1f).setDuration(260).start()
        }.start()
    }

    fun swapText(tv: TextView?, t: CharSequence) {
        if (tv == null || tv.text.toString() == t.toString()) return
        tv.animate().cancel()
        tv.animate().alpha(0f).translationY(8f).setDuration(120).withEndAction {
            tv.text = t; tv.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }.start()
    }

    /** Play/pause icon morph. First call just sets the icon. */
    fun morph(b: ImageButton?, playing: Boolean, animate: Boolean) {
        b ?: return
        if (!animate) { b.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play); return }
        b.setImageResource(if (playing) R.drawable.avd_play_to_pause else R.drawable.avd_pause_to_play)
        (b.drawable as? AnimatedVectorDrawable)?.start()
    }

    /** Spring press: shrink on touch, overshoot back, tick haptic. Never consumes the touch. */
    fun press(vararg views: View?) = views.filterNotNull().forEach { v ->
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(260).start()
            }
            false
        }
    }

    /** Pulsing live dot. ponytail: animator lives as long as the view, fine for home screens. */
    fun dot(v: View?, color: Int, live: Boolean) {
        v ?: return
        v.backgroundTintList = ColorStateList.valueOf(color)
        val a = v.getTag(R.id.stGpsDot) as? ObjectAnimator
        if (live && a == null) v.setTag(R.id.stGpsDot, ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.3f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start() })
        if (!live && a != null) { a.cancel(); v.alpha = 1f; v.setTag(R.id.stGpsDot, null) }
    }

    val GREEN = 0xFF34D399.toInt()   // tokens: success
    val AMBER = 0xFFFBBF24.toInt()   // tokens: warning

    /** Both output sliders, chips, Wi-Fi switch and level meter, live every 500 ms. Uses the Volumes API. */
    fun bindOutputs(a: Activity, root: View) {
        val jbl = root.findViewById<Slider>(R.id.outJblSlider) ?: return
        val tab = root.findViewById<Slider>(R.id.outTabSlider)
        val jblPct = root.findViewById<TextView>(R.id.outJblPct); val tabPct = root.findViewById<TextView>(R.id.outTabPct)
        val jblChip = root.findViewById<TextView>(R.id.outJblChip); val tabChip = root.findViewById<TextView>(R.id.outTabChip)
        val jblDot = root.findViewById<View>(R.id.outJblDot); val tabDot = root.findViewById<View>(R.id.outTabDot)
        val tablet = Role.isTablet(a)
        val off = attr(a, R.attr.carTextDim)
        val am = a.getSystemService(AudioManager::class.java)

        val touching = BooleanArray(2)
        fun wire(i: Int, s: Slider, pct: TextView, set: (Int) -> Unit) {
            s.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(x: Slider) { touching[i] = true }
                override fun onStopTrackingTouch(x: Slider) { touching[i] = false; set(x.value.toInt()) }
            })
            s.addOnChangeListener { _, v, user -> if (user) pct.text = "${v.toInt()}%" }
        }
        wire(0, jbl, jblPct) { v -> if (tablet) PhoneMedia.cmd("volSet") { put("pct", v) } else Volumes.setJblLocal(a, v) }
        wire(1, tab, tabPct) { v -> if (tablet) Volumes.applyTablet(a, v) else MediaListener.setTablet(v) }

        fun show(i: Int, s: Slider, pct: TextView, v: Int) {
            s.isEnabled = v >= 0
            if (touching[i]) return
            if (v >= 0) s.value = v.coerceIn(0, 100).toFloat()
            pct.text = if (v >= 0) "$v%" else ""
        }
        val tick = object : Runnable {
            override fun run() {
                if (a.isFinishing || !root.isAttachedToWindow) return
                show(0, jbl, jblPct, if (tablet) (if (PhoneMedia.connected) Volumes.jblFromNp(PhoneMedia.np) else -1) else Volumes.localPct(a))
                show(1, tab, tabPct, if (tablet) Volumes.localPct(a) else if (MediaListener.clients > 0) MediaListener.tabletPct else -1)

                val bt = if (tablet) PhoneMedia.connected
                    else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                val dev = (jblChip?.tag as? String).orEmpty()
                jblChip?.text = (if (bt) "Connected" else "Not connected") + if (dev.isNotEmpty()) ", $dev" else ""
                dot(jblDot, if (bt) GREEN else off, bt)

                val on = TabletSpeaker.enabled(a)
                val live: Boolean; val waiting: Boolean
                if (tablet) {
                    live = StreamReceiver.running && SystemClock.elapsedRealtime() - StreamReceiver.lastAt <= 2000
                    waiting = StreamReceiver.running
                } else {
                    live = StreamSender.running && StreamSender.tablet.isNotEmpty()
                    waiting = StreamSender.running
                }
                tabChip?.text = when { !on -> "Off"; live -> "Connected"; waiting -> "Waiting"; else -> "Starts with GO" }
                dot(tabDot, when { live -> GREEN; on && waiting -> AMBER; else -> off }, live)
                root.postDelayed(this, 500)
            }
        }
        root.post(tick)
    }
}
