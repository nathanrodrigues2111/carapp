package com.lazyneil.lazycar.audio

import android.media.AudioDeviceInfo
import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * When a track's frames leave its speaker, by math. The track timestamp (frame position vs time) is the smooth time
 * base; on top of it goes the part of AudioFlinger's latency figure (hidden AudioTrack.getLatency(), which on A2DP
 * carries the sink's delay report or the stack default) that lies past where the timestamp stops. A stack whose
 * timestamp already includes the sink delay gets 0 added. No per speaker numbers: the mic calibration in [Acoustic]
 * learns what a given sink adds beyond its report, once, per address.
 */
class SinkClock(private val t: AudioTrack) {
    private val ts = AudioTimestamp()
    @Volatile private var tsFrame = -1L; @Volatile private var tsNs = 0L
    private var at = 0L
    private val downs = ArrayDeque<Double>()
    private val anchors = ArrayDeque<Long>()
    @Volatile var afMs = -1        // AudioFlinger latency past the client buffer
    @Volatile var downMs = 0.0     // how far the timestamp's frame trails the mixer's read position, median
    @Volatile var hiddenMs = 0.0   // added on top of the timestamp: max(0, afMs - downMs)
    @Volatile var stepMax = 0.0    // largest prediction step since the last log, ms

    /** Writer thread, as often as it likes: refreshes every 50 ms. */
    fun update() {
        val now = System.nanoTime(); if (now - at < 50_000_000L) return; at = now
        if (!t.getTimestamp(ts) || ts.framePosition <= 0) return
        val f0 = t.playbackHeadPosition.toLong() and 0xffffffffL; val before = playNs(f0)
        // The A2DP timestamp moves in steps of up to +-13 ms (measured); its true line is smooth. Median of the last
        // 2 s of (time - frame) anchors, so a step is not a deadline jump. Lags drift by 1 s (< 0.1 ms at 100 ppm).
        anchors.addLast(ts.nanoTime - ts.framePosition * 1_000_000_000L / Wire.RATE); if (anchors.size > 41) anchors.removeFirst()
        tsFrame = 0; tsNs = anchors.sorted()[anchors.size / 2]
        val head = t.playbackHeadPosition.toLong() and 0xffffffffL
        downs.addLast((head - ts.framePosition) * 1000.0 / Wire.RATE - (now - ts.nanoTime) / 1e6); if (downs.size > 40) downs.removeFirst()
        downMs = downs.sorted()[downs.size / 2]
        afMs = try { (latency.invoke(t) as Int) - t.bufferCapacityInFrames * 1000 / Wire.RATE } catch (_: Exception) { -1 }
        if (afMs >= 0) hiddenMs = maxOf(0.0, afMs - downMs)
        // Diagnostics: how far this refresh moved the predicted play time of one frame (should be ~0).
        if (before != null) playNs(f0)?.let { val st = (it - before) / 1e6; if (abs(st) > abs(stepMax)) stepMax = st
            if (abs(st) > 2) StreamReceiver.disturb("sink clock step ${"%+.1f".format(st)} ms") }
    }

    /** Our clock time at which frame [f] leaves the speaker, null until the track has a timestamp. */
    fun playNs(f: Long): Long? = if (tsFrame < 0) null else tsNs + (f - tsFrame) * 1_000_000_000L / Wire.RATE + (hiddenMs * 1e6).toLong()

    /** ms from the mixer reading a frame to it leaving the speaker (medians: the head moves in mixer bursts). */
    fun outMs(): Int? = if (tsFrame < 0) null else (downMs + hiddenMs).roundToInt()

    companion object {
        private val latency = AudioTrack::class.java.getMethod("getLatency")
        /** Calibration key for the Bluetooth sink a track plays on ("" = not Bluetooth). */
        fun key(d: AudioDeviceInfo?): String = if (d?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) "${d.productName}|${d.address}" else ""
    }
}
