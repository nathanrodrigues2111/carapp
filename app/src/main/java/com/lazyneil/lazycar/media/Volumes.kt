package com.lazyneil.lazycar.media

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.lazyneil.lazycar.Prefs
import com.lazyneil.lazycar.R
import com.lazyneil.lazycar.Role
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Two live sliders. "JBL (Bluetooth)" = STREAM_MUSIC of the device that plays (phone, or the tablet in Tablet mode).
 * "Tablet speaker" = tablet STREAM_MUSIC, or in Tablet mode the gain of CarApp's speaker copy. Volume never touches sync.
 */
object Volumes {
    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)
    /** Tablet, Tablet mode: STREAM_MUSIC is the JBL, so the speaker copy has its own gain. */
    private fun copy(ctx: Context) = Role.isTablet(ctx) && com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(ctx)
    /** The other device holds the JBL: tablet in Phone mode, phone in Tablet mode. */
    fun jblRemote(ctx: Context) = Role.isTablet(ctx) != com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(ctx)
    fun localPct(ctx: Context) = if (copy(ctx)) tabletSaved(ctx) else am(ctx).let { it.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(1, it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) }
    private fun setLocal(ctx: Context, pct: Int) = am(ctx).let {
        it.setStreamVolume(AudioManager.STREAM_MUSIC, (pct * it.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100f).roundToInt(), 0)
    }

    /** Tablet: last level, restored when a stream starts. Default full. */
    fun tabletSaved(ctx: Context) = ctx.getSharedPreferences("speaker", Context.MODE_PRIVATE).getInt("tvol", 100)
    fun saveTablet(ctx: Context, pct: Int) = ctx.getSharedPreferences("speaker", Context.MODE_PRIVATE).edit().putInt("tvol", pct).apply()
    fun applyTablet(ctx: Context, pct: Int) { saveTablet(ctx, pct); if (copy(ctx)) com.lazyneil.lazycar.audio.StreamSender.speakerGain(pct) else setLocal(ctx, pct) }

    /** Phone JBL level lives on STREAM_ACCESSIBILITY while CarApp plays the JBL itself (shared mode). */
    fun jblStream() = if (com.lazyneil.lazycar.audio.StreamSender.shared) AudioManager.STREAM_ACCESSIBILITY else AudioManager.STREAM_MUSIC
    fun jblPct(ctx: Context) = am(ctx).let { it.getStreamVolume(jblStream()) * 100 / maxOf(1, it.getStreamMaxVolume(jblStream())) }

    /** Phone: the JBL level is also the GO start volume, so GO restores it. */
    fun setJblLocal(ctx: Context, pct: Int) {
        am(ctx).let { it.setStreamVolume(jblStream(), (pct * it.getStreamMaxVolume(jblStream()) / 100f).roundToInt(), 0) }
        Prefs(ctx).vol = pct
    }

    fun jblFromNp(np: JSONObject) = if (np.optInt("volMax") > 0) np.optInt("vol") * 100 / np.optInt("volMax") else -1

    /** Adds both sliders to [parent] at [index] and keeps them live. */
    fun attach(a: Activity, parent: ViewGroup, index: Int) {
        val tablet = Role.isTablet(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val sliders = mutableListOf<() -> Unit>()
        fun row(name: String, get: () -> Int, set: (Int) -> Unit) {
            val label = TextView(a).apply { textSize = 15f; setTextColor(a.getColor(R.color.secondaryText)) }
            var touching = false
            val s = Slider(a).apply {
                valueFrom = 0f; valueTo = 100f; stepSize = 1f; labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(s: Slider) { touching = true }
                    override fun onStopTrackingTouch(s: Slider) { touching = false; set(s.value.toInt()) }
                })
                addOnChangeListener { _, v, user -> if (user) label.text = "$name ${v.toInt()}%" }
            }
            box.addView(label); box.addView(s)
            sliders += {
                val v = get()
                s.isEnabled = v >= 0
                if (!touching) { if (v >= 0) s.value = v.coerceIn(0, 100).toFloat(); label.text = if (v >= 0) "$name $v%" else "$name, not connected" }
            }
        }
        if (jblRemote(a)) row("JBL (Bluetooth)", { if (PhoneMedia.alive) jblFromNp(PhoneMedia.np) else -1 }) { v -> PhoneMedia.cmd("volSet") { put("pct", v) } }
        else row("JBL (Bluetooth)", { jblPct(a) }) { v -> setJblLocal(a, v) }
        if (tablet) row("Tablet speaker", { localPct(a) }) { v -> applyTablet(a, v) }
        else {
            row("Tablet speaker", { if (MediaListener.clients > 0) MediaListener.tabletPct else -1 }) { v -> MediaListener.setTablet(v) }
        }
        parent.addView(box, index)
        val tick = object : Runnable { override fun run() { sliders.forEach { it() }; if (!a.isFinishing) box.postDelayed(this, 500) } }
        box.post(tick)
    }
}
