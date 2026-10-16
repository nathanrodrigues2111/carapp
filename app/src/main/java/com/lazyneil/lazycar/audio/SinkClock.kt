package com.lazyneil.lazycar.audio

import android.media.AudioDeviceInfo
import android.media.AudioTimestamp
import android.media.AudioTrack

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
    @Volatile var afMs = -1        // AudioFlinger latency past the client buffer
    @Volatile var downMs = 0.0     // how far the timestamp's frame trails the mixer's read position, median
    @Volatile var hiddenMs = 0.0   // added on top of the timestamp: max(0, afMs - downMs)

    /** Writer thread, as often as it likes: refreshes every 50 ms. */
    fun update() {
        val now = System.nanoTime(); if (now - at < 50_000_000L) return; at = now
        if (!t.getTimestamp(ts) || ts.framePosition <= 0) return
        tsFrame = ts.framePosition; tsNs = ts.nanoTime
        val head = t.playbackHeadPosition.toLong() and 0xffffffffL
        downs.addLast((head - tsFrame) * 1000.0 / Wire.RATE - (now - tsNs) / 1e6); if (downs.size > 40) downs.removeFirst()
        downMs = downs.sorted()[downs.size / 2]
        afMs = try { (latency.invoke(t) as Int) - t.bufferCapacityInFrames * 1000 / Wire.RATE } catch (_: Exception) { -1 }
        if (afMs >= 0) hiddenMs = maxOf(0.0, afMs - downMs)
    }

    /** Our clock time at which frame [f] leaves the speaker, null until the track has a timestamp. */
    fun playNs(f: Long): Long? = if (tsFrame < 0) null else tsNs + (f - tsFrame) * 1_000_000_000L / Wire.RATE + (hiddenMs * 1e6).toLong()

    /** ms from now until the frame the mixer reads now leaves the speaker. */
    fun outMs(): Int? = playNs(t.playbackHeadPosition.toLong() and 0xffffffffL)?.let { ((it - System.nanoTime()) / 1_000_000).toInt() }

    companion object {
        private val latency = AudioTrack::class.java.getMethod("getLatency")
        /** Calibration key for the Bluetooth sink a track plays on ("" = not Bluetooth). */
        fun key(d: AudioDeviceInfo?): String = if (d?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) "${d.productName}|${d.address}" else ""
    }
}
