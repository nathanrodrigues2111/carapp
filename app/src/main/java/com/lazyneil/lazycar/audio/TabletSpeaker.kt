package com.lazyneil.lazycar.audio

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.lazyneil.lazycar.R
import com.lazyneil.lazycar.Role

/** "Tablet speaker over Wi-Fi": prefs, GO/STOP hooks and the Settings rows. */
object TabletSpeaker {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("speaker", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = sp(ctx).getBoolean("on", true)
    /** Fine trim on top of the automatic sync, positive = later. */
    fun delayMs(ctx: Context) = sp(ctx).getInt("delay", 0)
    fun btToo(ctx: Context) = sp(ctx).getBoolean("bt", false)
    /** Mic measured JBL correction, kept so the next stream starts close. */
    fun acoustic(ctx: Context) = sp(ctx).getInt("acoustic", 0)
    fun saveAcoustic(ctx: Context, v: Int) = sp(ctx).edit().putInt("acoustic", v).apply()

    fun onGo(ctx: Context) { if (enabled(ctx)) StreamSender.start(ctx) }
    fun onStop(ctx: Context) = StreamSender.stop(ctx)

    /** One-word state for the main screen row. */
    fun summary(ctx: Context): String = when {
        !enabled(ctx) -> "off"
        Role.isTablet(ctx) -> if (StreamReceiver.running && SystemClock.elapsedRealtime() - StreamReceiver.lastAt <= 2000) "connected" else "waiting"
        !StreamSender.running -> "on, starts with GO"
        StreamSender.tablet.isEmpty() -> "on, waiting for the tablet"
        else -> "on, connected"
    }

    fun status(ctx: Context): String = if (Role.isTablet(ctx)) {
        val r = StreamReceiver
        when {
            !r.running -> "Off"
            SystemClock.elapsedRealtime() - r.lastAt > 2000 -> "Waiting for the phone"
            else -> "Connected to ${r.phone}, ${Wire.FORMAT}, ${r.kbps} kbps, level ${r.rms}. " +
                "Clock offset locked, round trip ${"%.1f".format(r.rttMs)} ms. JBL latency (phone measured) ${r.phoneOutMs} ms. " +
                "Acoustic correction ${"%+.0f".format(r.acousticMs)} ms, last residual ${if (Acoustic.residualMs.isNaN()) "n/a" else "%+.0f ms".format(Acoustic.residualMs)} (${Acoustic.note}). " +
                "Jitter ${"%.0f".format(r.jitterMs)} ms, buffer target ${r.targetMs} ms" +
                r.outputs.joinToString("") { ". ${it.name}: ${it.aheadMs} ms ahead, sync error ${"%+.1f".format(it.errMs)} ms, resample ${it.ppm} ppm, jitter depth ${it.depthMs} ms, track buffer ${it.bufferMs} ms, underruns ${it.underruns}, concealed ${it.concealed}, dropped ${it.dropped}" } +
                ", lost ${r.lost}"
        }
    } else {
        val s = StreamSender
        when {
            s.error.isNotEmpty() && !s.running -> s.error
            !s.running -> "Starts with GO"
            s.tablet.isEmpty() -> "Capturing, waiting for the tablet"
            else -> "Streaming to ${s.tablet}, ${Wire.FORMAT}, ${s.kbps} kbps, level ${s.rms}, JBL latency ${s.outMs} ms (HAL ${s.halMs} ms), capture ${s.capMs} ms" + players(ctx).let { p ->
                if (p.isEmpty()) ", nothing playing" else if (s.rms == 0) ". Silent, blocking capture: $p" else ". Capturing: $p"
            }
        }
    }

    /** App names of the active media players; uid comes from toString since getClientUid is a system API. */
    private fun players(ctx: Context): String {
        val pm = ctx.packageManager
        return ctx.getSystemService(android.media.AudioManager::class.java).activePlaybackConfigurations
            .filter { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_MEDIA && "state:started" in it.toString() }
            .mapNotNull { Regex("u/pid:(\\d+)").find(it.toString())?.groupValues?.get(1)?.toIntOrNull() }
            .distinct().filter { it != android.os.Process.myUid() }
            .joinToString { pm.getNameForUid(it)?.let { n -> try { pm.getApplicationLabel(pm.getApplicationInfo(n, 0)).toString() } catch (_: Exception) { n } } ?: "uid $it" }
    }

    /** Adds the rows right after [anchor] inside its parent. */
    fun addSettings(a: Activity, anchor: View) {
        val parent = anchor.parent as ViewGroup
        val tablet = Role.isTablet(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val caption = { t: String -> TextView(a).apply { text = t; textSize = 13f; setTextColor(a.getColor(R.color.secondaryText)) } }
        val status = caption("")
        fun switch(t: String, key: String, def: Boolean, on: (Boolean) -> Unit) = MaterialSwitch(a).apply {
            text = t; textSize = 17f; minHeight = (48 * resources.displayMetrics.density).toInt()
            setTextColor(a.getColor(R.color.white)); isChecked = sp(a).getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> sp(a).edit().putBoolean(key, v).apply(); on(v) }
        }
        box.addView(switch("Tablet speaker over Wi-Fi", "on", true) { v ->
            if (tablet) StreamReceiver.sync(a) else if (v) StreamSender.start(a) else StreamSender.stop(a)
        })
        box.addView(caption(if (tablet) "Plays the phone's music on this tablet's speaker, in sync with the JBL"
            else "Streams this phone's music to the tablet speaker while GO is on, the JBL keeps playing over Bluetooth"))
        box.addView(status)
        if (tablet) {
            val label = caption("")
            fun show(v: Int) { label.text = "Sync trim ${if (v > 0) "+" else ""}$v ms on top of auto sync (positive plays the tablet later)" }
            show(delayMs(a))
            box.addView(label)
            box.addView(Slider(a).apply {
                valueFrom = -500f; valueTo = 1000f; stepSize = 10f; value = delayMs(a).toFloat()
                addOnChangeListener { _, v, _ -> sp(a).edit().putInt("delay", v.toInt()).apply(); show(v.toInt()) }
            })
            box.addView(switch("Also play on the tablet's Bluetooth speaker", "bt", false) {
                a.stopService(android.content.Intent(a, StreamReceiver::class.java)); StreamReceiver.sync(a)
            })
            box.addView(caption("Pair the JBL with the tablet instead of the phone: both outputs then share one clock"))
        }
        parent.addView(box, parent.indexOfChild(anchor) + 1)
        val tick = object : Runnable {
            override fun run() { status.text = status(a); if (box.isAttachedToWindow || !a.isFinishing) box.postDelayed(this, 1000) }
        }
        box.post(tick)
    }
}
