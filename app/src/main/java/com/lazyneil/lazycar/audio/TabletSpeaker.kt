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
    /** Debug only: also play on a Bluetooth speaker paired with the tablet. */
    fun setBtToo(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("bt", on).apply()
        ctx.stopService(android.content.Intent(ctx, StreamReceiver::class.java)); StreamReceiver.sync(ctx)
    }
    /** Mic measured JBL correction, kept so the next stream starts close. */
    fun acoustic(ctx: Context) = sp(ctx).getInt("acoustic", 0)
    fun saveAcoustic(ctx: Context, v: Int) = sp(ctx).edit().putInt("acoustic", v).apply()

    fun onGo(ctx: Context) { if (enabled(ctx)) StreamSender.start(ctx) }
    fun onStop(ctx: Context) = StreamSender.stop(ctx)

    /** One-word state for the main screen row. */
    fun summary(ctx: Context): String = when {
        !enabled(ctx) -> "off"
        Role.isTablet(ctx) -> if (StreamReceiver.running && SystemClock.elapsedRealtime() - StreamReceiver.lastAt <= 2000) "connected" else "searching"
        !StreamSender.running -> "on, waits for Start"
        StreamSender.tablet.isEmpty() -> "on, searching for the tablet"
        else -> "on, connected"
    }

    /** The phone also uses this tablet as a Bluetooth speaker (A2DP sink): a second, lossy, unsynced copy on top of ours. */
    fun btSinkPlaying(ctx: Context) = try {    // 11 = BluetoothProfile.A2DP_SINK (hidden constant); playback uids are anonymized for us
        ctx.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getProfileConnectionState(11) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
    } catch (_: Exception) { false }
    private const val BT_SINK = "The phone also plays to this tablet over Bluetooth. On the phone: Bluetooth, this tablet, turn off Media audio"

    /** One line for Settings; the full numbers are in Debug. */
    fun line(ctx: Context): String = if (Role.isTablet(ctx)) {
        val r = StreamReceiver
        when {
            btSinkPlaying(ctx) -> BT_SINK
            !r.running -> "Off"
            SystemClock.elapsedRealtime() - r.lastAt > 2000 -> "Searching for the phone"
            r.outputs.all { kotlin.math.abs(it.errMs) < 20 } -> "In sync"
            else -> "Syncing..."
        }
    } else summary(ctx).replaceFirstChar { it.uppercase() }

    fun status(ctx: Context): String = if (Role.isTablet(ctx)) {
        val r = StreamReceiver
        when {
            btSinkPlaying(ctx) -> BT_SINK
            !r.running -> "Off"
            SystemClock.elapsedRealtime() - r.lastAt > 2000 -> "Waiting for the phone"
            else -> "Connected to ${r.phone}, ${Wire.FORMAT}, ${r.kbps} kbps, level ${r.rms}. " +
                "Clock offset locked, round trip ${"%.1f".format(r.rttMs)} ms. JBL latency (phone measured) ${r.phoneOutMs} ms. " +
                "Acoustic correction ${"%+.0f".format(r.acousticMs)} ms, last residual ${if (Acoustic.residualMs.isNaN()) "n/a" else "%+.0f ms".format(Acoustic.residualMs)} (${Acoustic.note}). " +
                "Jitter ${"%.0f".format(r.jitterMs)} ms, buffer target ${r.targetMs} ms" +
                r.outputs.joinToString("") { (if (it.protect) ". Protecting speaker" else "") + ". ${it.name}: ${it.aheadMs} ms ahead, sync error ${"%+.1f".format(it.errMs)} ms, resample ${it.ppm} ppm, jitter depth ${it.depthMs} ms, track buffer ${it.bufferMs} ms, underruns ${it.underruns}, concealed ${it.concealed}, dropped ${it.dropped}, silenced ${it.mutes}" } +
                ", lost ${r.lost}"
        }
    } else {
        val s = StreamSender
        when {
            s.error.isNotEmpty() && !s.running -> s.error
            !s.running -> "Waits for Start"
            s.tablet.isEmpty() -> "Capturing, waiting for the tablet"
            else -> "Streaming to ${s.tablet}, ${Wire.FORMAT}, ${s.kbps} kbps, level ${s.rms}, JBL latency ${s.outMs} ms (HAL ${s.halMs} ms), capture ${s.capMs} ms" + players(ctx).let { p ->
                if (p.isEmpty()) ", nothing playing" else if (s.rms == 0) ". Silent, blocking capture: $p" else ". Capturing: $p"
            }
        }
    }

    /** App names of the active media players; uid comes from toString since getClientUid is a system API. */
    fun players(ctx: Context): String {
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
        var rows = 0
        // Rows in the shared settings style: title, one line subtitle, control right (or below for the slider).
        val inflater = android.view.LayoutInflater.from(a)
        // The on/off switch sits on the Tablet speaker volume row; the slider shows only while it is on.
        val slider = a.findViewById<View>(R.id.outTabSlider)
        a.findViewById<MaterialSwitch>(R.id.outTabSwitch)?.apply {
            // Not restored from saved state: a recreate() (accent change) restoring a stale value fired the listener and saved off.
            isSaveEnabled = false
            isChecked = enabled(a); slider?.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, v -> sp(a).edit().putBoolean("on", v).apply(); slider?.visibility = if (v) View.VISIBLE else View.GONE
                if (tablet) StreamReceiver.sync(a) else if (v) StreamSender.start(a) else StreamSender.stop(a) }
        }
        if (tablet) {
            val r = inflater.inflate(R.layout.item_set_slider, parent, false)
            val label = r.findViewById<TextView>(R.id.setValue)
            fun show(v: Int) { label.text = "${if (v > 0) "+" else ""}$v ms" }
            show(delayMs(a))
            r.findViewById<Slider>(R.id.setSlider).apply {
                valueFrom = -500f; valueTo = 1000f; stepSize = 10f; value = delayMs(a).toFloat()
                addOnChangeListener { _, v, _ -> sp(a).edit().putInt("delay", v.toInt()).apply(); show(v.toInt()) }
            }
            parent.addView(r, parent.indexOfChild(anchor) + 1 + rows++)
        }
    }
}
