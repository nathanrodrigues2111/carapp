package com.lazyneil.lazycar.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Acoustic auto-calibration on the music itself: the mic hears the tablet speaker and the JBL, GCC-PHAT
 * against what we played (with its true play time from the track timestamp) gives one peak per speaker.
 * The strongest peak is the tablet (its lag is the mic input bias); the next is the JBL. Their gap nudges [StreamReceiver.acousticMs].
 * Runs ~5 s after a stream starts, again at ~20 s, then every 60 s.
 */
class Acoustic(private val ctx: Context, private val out: StreamReceiver.Output, private val alive: () -> Boolean) {
    companion object {
        @Volatile var residualMs = Double.NaN   // last measured JBL minus tablet, ms
        @Volatile var note = "not run yet"
        @Volatile var runs = 0
        const val SR = 8000
        const val N = 16384        // 2 s of mic at 8 kHz
        const val L = 4096         // +-512 ms of lag search
        const val F = 32768
        const val MAX_MS = 120.0   // total acoustic correction on top of the measured BT latency
    }

    private val seen = mutableListOf<Double>()   // consecutive confident residuals

    fun start() = Thread({ loop() }, "speaker-acoustic").apply { isDaemon = true; start() }

    private fun loop() {
        var streamAt = 0L; var next = 0L; var n = 0
        while (alive()) {
            Thread.sleep(1000)
            val now = SystemClock.elapsedRealtime()
            if (now - StreamReceiver.lastAt > 2000) { streamAt = 0; continue }
            if (streamAt == 0L) { streamAt = now; next = now + 5000; n = 0 }
            if (now < next || StreamReceiver.rms < 200) continue
            try { measure() } catch (e: Exception) { note = "error ${e.message}"; Log.e("LazyCar", "acoustic", e) }
            n++; runs++
            next = now + if (n < 2) 15_000 else 60_000
        }
    }

    @SuppressLint("MissingPermission")
    private fun measure() {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { note = "no mic permission"; return }
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val src = if (am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = AudioRecord(src, Wire.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Wire.RATE * 2)
        val raw = ShortArray(N * 6)
        var read = 0L
        val skip = ShortArray(Wire.RATE / 4)
        val ts = AudioTimestamp()
        try {
            rec.startRecording()
            read += rec.read(skip, 0, skip.size).coerceAtLeast(0)          // settle
            val first = read
            var got = 0
            while (got < raw.size) { val r = rec.read(raw, got, raw.size - got); if (r <= 0) break; got += r }
            read += got
            if (got < raw.size || rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) { note = "mic timestamp unavailable"; return }
            // Our clock time of mic frame [first].
            val micNs = ts.nanoTime + (first - ts.framePosition) * 1_000_000_000L / Wire.RATE
            val m = DoubleArray(N) { i -> var a = 0.0; for (k in 0 until 6) a += raw[i * 6 + k]; a / 6 }
            if (sqrt(m.sumOf { it * it } / N) < 3) { note = "mic silent (blocked in background?)"; return }
            Thread.sleep(700)                                                  // let the reference run ahead of the window
            // Reference index at time micNs - L: track timestamp maps frames to play time.
            val p0 = out.playNs(out.written) ?: run { note = "track timestamp unavailable"; return }
            val refAtMic = (out.written - (p0 - micNs) * Wire.RATE / 1_000_000_000L) / 6
            val start = refAtMic - L
            if (start < out.refEnd - out.ref.size + 10 || start + N + 2 * L > out.refEnd) { note = "reference out of range"; return }
            val r = DoubleArray(N + 2 * L) { out.ref[((start + it) and 0xffff).toInt()].toDouble() }
            val c = gccPhat(m, r)
            // c[k] peaks where mic[j] matches ref[j + k]; lag tau = (L - k) / SR, positive = heard after the tablet played it.
            val lag = { k: Int -> (L - k) * 1000.0 / SR }
            val floor = c.map { abs(it) }.sorted()[c.size / 2] + 1e-12
            val k1 = c.indices.maxBy { c[it] }
            val k2 = c.indices.filter { abs(it - k1) > SR / 50 }.maxBy { c[it] }   // outside +-20 ms: closer is a reflection of the tablet, and in spec anyway
            // ponytail: strongest peak = tablet (mic is centimetres from its speaker); fails if the JBL is far louder at the tablet
            val kt = k1; val kj = k2
            val sj = c[kj] / floor; val st = c[kt] / floor
            val res = lag(kj) - lag(kt) + out.lateMs   // the tablet plays lateMs behind on purpose
            val msg = "peaks tablet ${"%.1f".format(lag(kt))} ms (x${"%.0f".format(st)}), other ${"%.1f".format(lag(kj))} ms (x${"%.0f".format(sj)})"
            if (st < 6 || abs(lag(kt)) > 200) { note = "no clear tablet peak, $msg"; residualMs = Double.NaN }
            else if (sj < 6 || c[kj] < 0.2 * c[kt]) { note = "single peak, in sync within 20 ms, $msg"; residualMs = 0.0; seen.clear() }
            else {
                // The JBL peak must clearly beat the next candidate, and agree over 3 runs, before it moves anything.
                val k3 = c.indices.filter { abs(it - kt) > SR / 50 && abs(it - kj) > SR / 50 }.maxBy { c[it] }
                residualMs = res
                if (c[kj] < 2 * c[k3]) { note = "ambiguous JBL peak, ignored, $msg"; seen.clear() }
                else {
                    seen += res; if (seen.size > 3) seen.removeAt(0)
                    if (seen.size == 3 && seen.max() - seen.min() <= 10) {
                        val step = (0.8 * seen.sorted()[1]).coerceIn(-60.0, 60.0)
                        StreamReceiver.acousticMs = (StreamReceiver.acousticMs + step).coerceIn(-MAX_MS, MAX_MS)
                        TabletSpeaker.saveAcoustic(ctx, StreamReceiver.acousticMs.toInt())
                        seen.clear()
                        note = "JBL ${"%.0f".format(res)} ms vs tablet, corrected ${"%+.0f".format(step)} ms, $msg"
                    } else note = "JBL ${"%.0f".format(res)} ms vs tablet, confirming (${seen.size} of 3), $msg"
                }
            }
            Log.e("LazyCar", "acoustic: $note acoustic=${"%.0f".format(StreamReceiver.acousticMs)}")
        } finally { try { rec.stop() } catch (_: Exception) {}; rec.release() }
    }

    /** Cross-correlation with phase transform (whitened), 150 Hz to 3.5 kHz; returns c[0..2L]. */
    private fun gccPhat(m: DoubleArray, r: DoubleArray): DoubleArray {
        val mr = DoubleArray(F); val mi = DoubleArray(F); val rr = DoubleArray(F); val ri = DoubleArray(F)
        m.copyInto(mr); r.copyInto(rr)
        fft(mr, mi, false); fft(rr, ri, false)
        val lo = 150 * F / SR; val hi = 3500 * F / SR
        for (k in 0 until F) {
            val f = if (k <= F / 2) k else F - k
            // conj(M) * R
            val re = mr[k] * rr[k] + mi[k] * ri[k]; val im = mr[k] * ri[k] - mi[k] * rr[k]
            val mag = sqrt(re * re + im * im)
            if (f < lo || f > hi || mag < 1e-9) { mr[k] = 0.0; mi[k] = 0.0 } else { mr[k] = re / mag; mi[k] = im / mag }
        }
        fft(mr, mi, true)
        return DoubleArray(2 * L + 1) { mr[it] }
    }
}

/** In-place iterative radix-2 FFT. */
fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
    }
    var len = 2
    while (len <= n) {
        val ang = 2 * PI / len * if (inverse) 1 else -1
        val wr = cos(ang); val wi = sin(ang)
        for (i in 0 until n step len) {
            var cr = 1.0; var ci = 0.0
            for (k in 0 until len / 2) {
                val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                re[i + k] += ar; im[i + k] += ai
                val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
            }
        }
        len = len shl 1
    }
    if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
}
