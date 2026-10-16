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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One time calibration of a Bluetooth sink CarApp has never seen. Runtime sync is math ([SinkClock]); a sink can add
 * delay beyond what it reports, so the first session with it lets the mic hear the tablet speaker and the sink
 * (GCC-PHAT against what we played, with its true play time from the track timestamp): one peak per speaker,
 * the strongest is the tablet, the next the sink. Many windows, median, 4 of 6 must agree within 5 ms; the
 * correction is applied and checked again until the residual is under 4 ms, then stored per sink address
 * ([TabletSpeaker.cal]) and the mic never opens for that sink again. While calibrating the tablet copy plays 6 dB lower.
 * No mic permission: math only. Test instrument: a file "sync_probe" in the app's files dir logs the residual
 * (measure only, never corrects).
 */
class Acoustic(private val ctx: Context, private val out: StreamReceiver.Output, private val sink: () -> String, private val alive: () -> Boolean) {
    companion object {
        @Volatile var residualMs = Double.NaN   // last measured sink minus tablet, ms
        @Volatile var note = "not run yet"
        @Volatile var runs = 0
        const val SR = 8000
        const val N = 16384        // 2 s of mic at 8 kHz
        const val L = 4096         // +-512 ms of lag search
        const val F = 32768
        const val MAX_MS = 400.0   // learned correction on top of the math
    }

    fun start() = Thread({ loop() }, "speaker-acoustic").apply { isDaemon = true; start() }

    private fun loop() {
        var key = "?"; val votes = mutableListOf<Double>(); var rounds = 0; var since = 0L; var gaveUp = false
        while (alive()) {
            Thread.sleep(500)
            val now = SystemClock.elapsedRealtime()
            val k = sink()
            if (k != key) { key = k; votes.clear(); rounds = 0; since = 0; gaveUp = false; residualMs = Double.NaN
                note = if (k.isEmpty()) "no Bluetooth sink" else if (TabletSpeaker.cal(ctx, k) != null) "calibrated earlier, math only" else "new sink, will calibrate" }
            val probe = java.io.File(ctx.filesDir, "sync_probe").exists()
            val need = k.isNotEmpty() && !gaveUp && TabletSpeaker.cal(ctx, k) == null &&
                ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            out.trim = if (need) 0.5f else 1f
            if (!need && !probe) continue                        // known sink (or no mic): math only, the mic stays closed
            if (now - StreamReceiver.lastAt > 2000 || StreamReceiver.rms < 200) { since = 0; continue }
            if (since == 0L) since = now
            if (need && now - since > 120_000) { gaveUp = true; StreamReceiver.acousticMs = 0.0; note = "no agreement in 2 min, math only this session"; Log.e("LazyCar", "acoustic: $note"); continue }
            if (now - since < 3000 || abs(out.errMs) > 8) continue   // the speaker first settles on the math
            val err = out.errMs
            val r = try { measure() } catch (e: Exception) { note = "error ${e.message}"; Log.e("LazyCar", "acoustic", e); null }
            runs++
            if (r == null) { Log.e("LazyCar", "acoustic: $note"); continue }
            val res = r + err   // relative to the math deadline: the speaker was err ms late while measuring
            residualMs = res
            if (!need) { Log.e("LazyCar", "acoustic probe: sink ${"%+.1f".format(res)} ms vs tablet (speaker err ${"%.1f".format(err)}), correction ${"%.0f".format(StreamReceiver.acousticMs)}, $note"); continue }
            votes += res; if (votes.size > 10) votes.removeAt(0)
            val med = votes.sorted()[votes.size / 2]; val agree = votes.filter { abs(it - med) <= 5 }
            if (votes.size < 6 || agree.size < 4) { note = "calibrating, ${votes.size} votes, ${agree.size} agree on ${"%+.0f".format(med)} ms"; Log.e("LazyCar", "acoustic: $note, last ${"%+.1f".format(res)}"); continue }
            val m = agree.average(); votes.clear()
            if (abs(m) <= 4) {
                TabletSpeaker.saveCal(ctx, k, StreamReceiver.acousticMs.roundToInt())
                note = "calibrated ${"%+.0f".format(StreamReceiver.acousticMs)} ms (residual ${"%+.1f".format(m)} ms), math only from now"
            } else if (rounds >= 3) { gaveUp = true; StreamReceiver.acousticMs = 0.0; note = "peak did not follow 3 corrections (reflection?), math only this session" }
            else { rounds++; StreamReceiver.acousticMs = (StreamReceiver.acousticMs + m).coerceIn(-MAX_MS, MAX_MS); note = "corrected ${"%+.0f".format(m)} ms, checking" }
            Log.e("LazyCar", "acoustic: $note correction=${"%.0f".format(StreamReceiver.acousticMs)}")
        }
        out.trim = 1f
    }

    /** One window: sink minus tablet in ms, 0 when only the tablet is heard (in sync within 3 ms), null when unusable. */
    @SuppressLint("MissingPermission")
    private fun measure(): Double? {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { note = "no mic permission"; return null }
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
            if (got < raw.size || rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) { note = "mic timestamp unavailable"; return null }
            // Our clock time of mic frame [first].
            val micNs = ts.nanoTime + (first - ts.framePosition) * 1_000_000_000L / Wire.RATE
            val m = DoubleArray(N) { i -> var a = 0.0; for (k in 0 until 6) a += raw[i * 6 + k]; a / 6 }
            if (sqrt(m.sumOf { it * it } / N) < 3) { note = "mic silent (blocked in background?)"; return null }
            Thread.sleep(700)                                                  // let the reference run ahead of the window
            // Reference index at time micNs - L: track timestamp maps frames to play time.
            val p0 = out.playNs(out.written) ?: run { note = "track timestamp unavailable"; return null }
            val refAtMic = (out.written - (p0 - micNs) * Wire.RATE / 1_000_000_000L) / 6
            val start = refAtMic - L
            if (start < out.refEnd - out.ref.size + 10 || start + N + 2 * L > out.refEnd) { note = "reference out of range"; return null }
            val r = DoubleArray(N + 2 * L) { out.ref[((start + it) and 0xffff).toInt()].toDouble() }
            val c = gccPhat(m, r)
            // c[k] peaks where mic[j] matches ref[j + k]; lag tau = (L - k) / SR, positive = heard after the tablet played it.
            val lag = { k: Int -> (L - k) * 1000.0 / SR }
            val floor = c.map { abs(it) }.sorted()[c.size / 2] + 1e-12
            // ponytail: strongest peak = tablet (mic is centimetres from its speaker); fails if the JBL is far louder at the tablet
            val kt = c.indices.maxBy { c[it] }; val st = c[kt] / floor
            if (st < 6 || abs(lag(kt)) > 200) { note = "no clear tablet peak (x${"%.0f".format(st)})"; return null }
            // Remove the tablet's own sound: least squares FIR of the tablet path (2 ms either side of its peak),
            // subtract it from the mic, then the JBL is the main peak of what is left instead of a side lobe.
            val k0 = (kt - SR * 2 / 1000).coerceAtLeast(0); val k1 = (kt + SR * 2 / 1000).coerceAtMost(2 * L)
            val w = k1 - k0 + 1
            val ac = DoubleArray(w) { d -> var a = 0.0; for (i in k0 until k0 + N) a += r[i] * r[i + d]; a }   // same span as the cross terms
            val a = solve(Array(w) { p -> DoubleArray(w) { q -> ac[abs(p - q)] * (if (p == q) 1.001 else 1.0) } },
                DoubleArray(w) { p -> var b = 0.0; for (j in 0 until N) b += m[j] * r[j + k0 + p]; b })
            val e = DoubleArray(N) { j -> var y = m[j]; for (p in 0 until w) y -= a[p] * r[j + k0 + p]; y }
            val cancelDb = 10 * kotlin.math.log10(e.sumOf { it * it } / m.sumOf { it * it })
            val c2 = gccPhat(e, r)
            val floor2 = c2.map { abs(it) }.sorted()[c2.size / 2] + 1e-12
            // Sink: 3 ms or more from the tablet (closer is inside the cancelled window), at most 250 ms.
            val cand = c2.indices.filter { k -> abs(k - kt) >= SR * 3 / 1000 && abs(k - kt) <= SR / 4 }
            val kj = cand.maxBy { c2[it] }; val sj = c2[kj] / floor2
            val res = lag(kj) - lag(kt) + out.lateMs   // the tablet plays lateMs behind on purpose
            note = "tablet ${"%.1f".format(lag(kt))} ms (x${"%.0f".format(st)}), cancelled ${"%.0f".format(cancelDb)} dB, sink ${"%.1f".format(lag(kj))} ms (x${"%.0f".format(sj)})"
            return if (sj < 8) 0.0 else res
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

/** Gaussian elimination with partial pivoting; overwrites [m] and [b]. */
fun solve(m: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = b.size
    for (i in 0 until n) {
        val p = (i until n).maxBy { abs(m[it][i]) }
        val t = m[i]; m[i] = m[p]; m[p] = t; val tb = b[i]; b[i] = b[p]; b[p] = tb
        for (r in i + 1 until n) { val f = m[r][i] / m[i][i]; if (f != 0.0) { for (c in i until n) m[r][c] -= f * m[i][c]; b[r] -= f * b[i] } }
    }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) { var v = b[i]; for (c in i + 1 until n) v -= m[i][c] * x[c]; x[i] = v / m[i][i] }
    return x
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
