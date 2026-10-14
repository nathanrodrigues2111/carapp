package com.lazyneil.lazycar.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.TreeMap
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Tablet side: beacons for the phone, then plays its PCM on the built-in speaker (and optionally a BT speaker).
 *
 * Sync: every chunk carries the phone mixer time of its first frame and the phone to JBL latency, so the
 * chunk has a deadline on our clock (NTP-style offset from the beacon echo). The output thread compares
 * the AudioTrack timestamp (true playout time) with that deadline and micro resamples (max 0.5 %) to
 * close the gap. [Acoustic] then measures what is left with the mic and nudges [acousticMs].
 */
class StreamReceiver : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var phone = ""
        @Volatile var lastAt = 0L
        @Volatile var kbps = 0
        @Volatile var lost = 0L
        @Volatile var rms = 0           // level of the received PCM, proves audio arrives even at volume 0
        @Volatile var phoneOutMs = 0    // phone mixer to JBL latency, measured on the phone every second
        @Volatile var rttMs = 0.0       // best beacon round trip in the clock window
        @Volatile var offsetMs = 0.0    // phone clock minus ours
        @Volatile var jitterMs = 0.0    // peak arrival spread, decays
        @Volatile var targetMs = 0      // jitter buffer the deadline never goes below
        @Volatile var acousticMs = 0.0  // mic measured correction on top of the phone's numbers
        val outputs = mutableListOf<Output>()

        fun sync(ctx: Context) {
            val i = Intent(ctx, StreamReceiver::class.java)
            try { if (TabletSpeaker.enabled(ctx)) ctx.startForegroundService(i) else ctx.stopService(i) }
            catch (e: Exception) { Log.e("LazyCar", "speaker receive start", e) }
        }

        fun urgent() = try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
    }

    /** jblNs: when the JBL plays the first frame (our clock). */
    class Chunk(val seq: Int, val pcm: ShortArray, val jblNs: Long)

    /** One AudioTrack pinned to one device, fed from a reorder buffer keyed by sequence number. */
    class Output(val name: String, type: Int, ctx: Context, val extraMs: () -> Double) {
        private val jb = TreeMap<Int, Chunk>()
        @Volatile var aheadMs = 0       // queued plus in the track, ms of audio ahead of the speaker
        @Volatile var depthMs = 0       // jitter protection: reorder buffer plus unplayed client buffer
        @Volatile var errMs = 0.0       // smoothed playout minus deadline, positive = late
        @Volatile var ppm = 0           // current resample correction
        @Volatile var concealed = 0
        @Volatile var dropped = 0
        val underruns get() = track.underrunCount
        val device: AudioDeviceInfo? = ctx.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == type }
        private val minBuf = AudioTrack.getMinBufferSize(Wire.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        private val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Wire.RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minBuf * 4)
            .build().apply { setPreferredDevice(device); setVolume(1f) }
        // Capacity 4x min, but only fill 2x: every ms of track buffer is a ms later than the JBL. Grows on underruns.
        private var fill = (minBuf / 4 * 2).coerceAtMost(track.bufferCapacityInFrames).also { track.bufferSizeInFrames = it }
        val bufferMs get() = track.bufferSizeInFrames * 1000 / Wire.RATE
        val routed get() = track.routedDevice?.productName?.toString() + " (" + (track.routedDevice?.type ?: -1) + ")"
        /** Mono 8 kHz copy of what we wrote, for the mic correlation. Index = written frames / 6. */
        val ref = FloatArray(1 shl 16)
        @Volatile var refEnd = 0L
        private val ts = AudioTimestamp()
        @Volatile private var nextSeq = -1
        @Volatile var written = 0L
        val lim = Limiter()

        fun put(c: Chunk) = synchronized(jb) {
            if (nextSeq < 0 || c.seq >= nextSeq) jb[c.seq] = c
            while (jb.size > 600) jb.pollFirstEntry()
        }
        fun reset() = synchronized(jb) { jb.clear(); nextSeq = -1 }

        private fun take(): Chunk? = synchronized(jb) {
            if (nextSeq < 0) return jb.pollFirstEntry()?.value?.also { nextSeq = it.seq + 1 }
            if (jb.isNotEmpty() && jb.lastKey() < nextSeq) nextSeq = jb.firstKey()   // concealed past a stall: rejoin, late ones get dropped
            while (jb.isNotEmpty() && jb.firstKey() < nextSeq) jb.pollFirstEntry()
            jb.remove(nextSeq)?.also { nextSeq++ }
        }
        private fun skip() = synchronized(jb) { if (nextSeq >= 0) nextSeq++ }

        /** Our clock time at which frame [f] leaves the speaker, from the track timestamp. */
        fun playNs(f: Long): Long? = synchronized(ts) {
            if (!track.getTimestamp(ts) || ts.framePosition == 0L) null
            else ts.nanoTime + (f - ts.framePosition) * 1_000_000_000L / Wire.RATE
        }

        fun run(alive: () -> Boolean) = thread(name = "speaker-$name") {
            urgent()
            track.play()
            val rs = Resampler()
            var last: ShortArray? = null; var fades = 0
            var dec = 0f; var decN = 0; var seenUnder = 0
            fun write(s: ShortArray) {
                written += track.write(s, 0, s.size).coerceAtLeast(0) / 2
                for (i in 0 until s.size / 2) {
                    dec += s[2 * i] + s[2 * i + 1]; if (++decN == 6) { ref[(refEnd and 0xffff).toInt()] = dec / 393216f; refEnd++; dec = 0f; decN = 0 }
                }
            }
            while (alive()) {
                val now = System.nanoTime()
                val playNow = playNs(written) ?: (now + (written - track.playbackHeadPosition) * 1_000_000_000L / Wire.RATE)
                val aheadNs = playNow - now
                aheadMs = (aheadNs / 1_000_000).toInt()
                val client = ((written - track.playbackHeadPosition) * 1000 / Wire.RATE).toInt()
                depthMs = client + synchronized(jb) { jb.size } * Wire.CHUNK_MS
                if (track.underrunCount > seenUnder) { seenUnder = track.underrunCount
                    if (fill < track.bufferCapacityInFrames) { fill = minOf(track.bufferCapacityInFrames, fill + minBuf / 16); track.bufferSizeInFrames = fill } }
                val c = take()
                if (c == null) {
                    // Nothing in time: conceal with the last frame, fading, before the track runs dry.
                    // First gap chunk: the last chunk played backwards (starts where the audio left off) fading to 0, then silence. No step, no click.
                    if (last != null && client < 15) {
                        val l = last; val n = l.size / 2
                        write(lim.process(FloatArray(l.size) { if (fades > 0) 0f else l[2 * (n - 1 - it / 2) + it % 2] * (1 - (it / 2) / n.toFloat()) }))
                        fades++; concealed++; skip()
                    } else Thread.sleep(2)
                    continue
                }
                val deadline = c.jblNs + (extraMs() * 1e6).toLong()
                val e = (playNow - deadline) / 1e6
                // Far off (start, stall, big clock step): jump once, then micro resampling keeps it there.
                // The JBL latency is the whole budget: playing late to grow the buffer only trades sync for drops later.
                if (e > 50 && depthMs > 40) { dropped++; errMs = 0.0; continue }
                if (e < -50) { write(ShortArray(((-e).coerceAtMost(500.0) * Wire.RATE / 1000).toInt() * 2)); putBack(c); errMs = 0.0; continue }
                errMs = errMs * 0.98 + e * 0.02
                // Drift only: at most 500 ppm. Big errors take the jump above instead of pinning the resampler.
                val r = if (abs(errMs) < 1) 1.0 else 1 + (errMs / 20000).coerceIn(-0.0005, if (depthMs < 30) 0.0 else 0.0005)
                ppm = ((r - 1) * 1e6).toInt()
                val x = rs.process(c.pcm, r)
                if (fades > 0) for (i in x.indices) x[i] *= (i / 2) / (x.size / 2f)   // fade back in after a gap
                write(lim.process(x))
                last = c.pcm; fades = 0
            }
            track.stop(); track.release()
        }
        private fun putBack(c: Chunk) = synchronized(jb) { jb[c.seq] = c; nextSeq = c.seq }
    }

    @Volatile private var alive = false
    private var socket: DatagramSocket? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wifiLow: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("speaker", "Tablet speaker", NotificationManager.IMPORTANCE_LOW))
        val n = com.lazyneil.lazycar.media.PhoneMedia.notification(this)
        // Microphone type lets the acoustic sync record; it is refused when started from the background.
        try {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException("no mic")
            startForeground(47475, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) { startForeground(47475, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        com.lazyneil.lazycar.media.PhoneMedia.start(this)
        if (alive) return START_STICKY
        alive = true; running = true
        try { start() } catch (e: Exception) { Log.e("LazyCar", "speaker receive", e); stopSelf() }
        return START_STICKY
    }

    private fun start() {
        val wm = getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "speaker").apply { acquire() }
        wifiLow = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "speaker-ll").apply { acquire() }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carapp:speaker").apply { acquire() }
        val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; receiveBufferSize = 1 shl 20; bind(InetSocketAddress(Wire.TABLET_PORT)) }
        socket = s
        val ctx = this
        // An older build let this run away to the clamp; anything beyond the new bound restarts from 0.
        acousticMs = TabletSpeaker.acoustic(ctx).toDouble().let { if (abs(it) > Acoustic.MAX_MS) { TabletSpeaker.saveAcoustic(ctx, 0); 0.0 } else it }
        synchronized(outputs) {
            outputs.clear()
            outputs += Output("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ctx) { acousticMs + TabletSpeaker.delayMs(ctx) }
            if (TabletSpeaker.btToo(ctx)) outputs += Output("bluetooth", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, ctx) { 0.0 }
            outputs.forEach { it.run { alive } }
        }
        Acoustic(ctx, outputs[0]) { alive }.start()
        thread(name = "speaker-beacon") {
            val hello = ByteArray(14).also { Wire.MAGIC.toByteArray().copyInto(it) }
            while (alive) {
                ByteBuffer.wrap(hello).putLong(6, System.nanoTime())
                for (a in broadcastTargets()) try { s.send(DatagramPacket(hello, hello.size, a, Wire.PHONE_PORT)) } catch (_: Exception) {}
                Thread.sleep(500)
            }
        }
        thread(name = "speaker-net") {
            urgent()
            val buf = ByteArray(Wire.HEADER + Wire.CHUNK * 2)
            val bb = ByteBuffer.wrap(buf)
            val clock = Clock()
            var last = -1; var bytes = 0L; var secAt = SystemClock.elapsedRealtime(); var sq = 0.0; var n = 0
            while (alive) try {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                val len = p.length - Wire.HEADER
                if (len <= 0 || len % 4 != 0) continue
                val t3 = System.nanoTime()
                val seq = bb.getInt(0)
                val now = SystemClock.elapsedRealtime()
                if (now - lastAt > 2000 || seq < last - 1000) {                      // fresh stream (phone restarted)
                    last = seq - 1; clock.reset(); outputs.forEach { it.reset() }
                    com.lazyneil.lazycar.media.Volumes.applyTablet(ctx, com.lazyneil.lazycar.media.Volumes.tabletSaved(ctx))
                }
                clock.add(t3, bb.getLong(4), bb.getLong(12), bb.getLong(20), bb.getLong(28))
                phoneOutMs = bb.getInt(36)
                if (seq > last) { lost += (seq - last - 1).coerceAtMost(200); last = seq }
                val pcm = ShortArray(len / 2); ByteBuffer.wrap(buf, Wire.HEADER, len).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                for (i in pcm.indices step 4) { val v = pcm[i].toDouble(); sq += v * v; n++ }
                val mixNs = bb.getLong(28) - clock.offsetNs
                val c = Chunk(seq, pcm, mixNs + phoneOutMs * 1_000_000L)
                for (o in outputs) o.put(c)
                lastAt = now; phone = p.address.hostAddress ?: ""; bytes += p.length
                if (now - secAt >= 1000) {
                    kbps = (bytes * 8 / (now - secAt)).toInt(); bytes = 0; secAt = now
                    rms = kotlin.math.sqrt(sq / maxOf(n, 1)).toInt(); sq = 0.0; n = 0
                    Log.e("LazyCar", "speaker recv kbps=$kbps rms=$rms rtt=${"%.1f".format(rttMs)} jitter=${"%.1f".format(jitterMs)} target=$targetMs phoneOut=$phoneOutMs acoustic=${"%.0f".format(acousticMs)} lost=$lost " +
                        outputs.joinToString { "${it.name} ahead=${it.aheadMs} depth=${it.depthMs} err=${"%.1f".format(it.errMs)} ppm=${it.ppm} under=${it.underruns} plc=${it.concealed} drop=${it.dropped} clip=${it.lim.clipped} gr=${"%.1f".format(it.lim.reductionDb)} buf=${it.bufferMs} -> ${it.routed}" })
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Clock offset (phone minus tablet) from the beacon echo: the packet with the smallest round trip in a
     * ~8 s window wins (NTP style). Also tracks how late packets arrive relative to their mixer time, for the
     * adaptive jitter target.
     */
    private class Clock {
        private val win = ArrayDeque<LongArray>()   // per 1 s bucket: start, best rtt, its offset, min lag
        @Volatile var offsetNs = 0L
        @Volatile var minLagNs = 0L
        private var peak = 0.0
        fun reset() { win.clear(); peak = 0.0 }
        fun add(t3: Long, t0: Long, p1: Long, p2: Long, mix: Long) {
            if (t0 == 0L) return
            val rtt = (t3 - t0) - (p2 - p1)
            val off = p1 - t0 - rtt / 2
            val b = win.lastOrNull()
            if (b == null || t3 - b[0] > 1_000_000_000L) { win.addLast(longArrayOf(t3, rtt, off, Long.MAX_VALUE)); if (win.size > 8) win.removeFirst() }
            val cur = win.last()
            if (rtt < cur[1]) { cur[1] = rtt; cur[2] = off }
            val best = win.minBy { it[1] }
            offsetNs = best[2]; rttMs = best[1] / 1e6; offsetMs = offsetNs / 1e6
            val lag = t3 - (mix - offsetNs)
            if (lag < cur[3]) cur[3] = lag
            minLagNs = win.minOf { it[3] }
            // Peak hold that halves in about 5 s (200 packets a second).
            peak = maxOf((lag - minLagNs) / 1e6, peak * 0.9993)
            jitterMs = peak
            targetMs = (peak * 1.5 + 30).toInt().coerceIn(80, 300)
        }
    }

    private fun broadcastTargets(): Set<InetAddress> {
        val out = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try { for (ni in NetworkInterface.getNetworkInterfaces()) if (ni.isUp && !ni.isLoopback) ni.interfaceAddresses.mapNotNullTo(out) { it.broadcast } }
        catch (_: Exception) {}
        return out
    }

    override fun onDestroy() {
        alive = false; running = false; phone = ""; kbps = 0
        socket?.close(); socket = null
        wifi?.release(); wifi = null
        wifiLow?.release(); wifiLow = null
        wake?.release(); wake = null
        super.onDestroy()
    }
}

/** Linear interpolation resampler for interleaved stereo; ratio > 1 consumes input faster (plays earlier). */
class Resampler {
    private var pl = 0f; private var pr = 0f; private var frac = 0.0
    fun process(x: ShortArray, ratio: Double): FloatArray {
        val n = x.size / 2
        val out = FloatArray(((n - frac) / ratio + 2).toInt() * 2)
        var o = 0; var p = frac
        while (p < n) {
            val i = p.toInt(); val f = (p - i).toFloat()
            val l0 = if (i == 0) pl else x[2 * (i - 1)].toFloat(); val r0 = if (i == 0) pr else x[2 * (i - 1) + 1].toFloat()
            out[o++] = l0 + (x[2 * i] - l0) * f; out[o++] = r0 + (x[2 * i + 1] - r0) * f
            p += ratio
        }
        frac = p - n; pl = x[2 * n - 2].toFloat(); pr = x[2 * n - 1].toFloat()
        return out.copyOf(o)
    }
}

/**
 * +6 dB into a stereo linked peak limiter at -1 dBFS: 1 ms lookahead, ~0.2 ms attack, 10 ms hold, 150 ms release.
 * Gain moves smoothly, so loud passages get quieter instead of distorted (the old per sample tanh crackled).
 */
class Limiter(private val gain: Float = 2f) {
    private val la = 48; private val ceil = 0.891f
    private val rel = (1 - kotlin.math.exp(-1.0 / (0.15 * Wire.RATE))).toFloat()
    private val dl = FloatArray(la * 2); private val need = FloatArray(la) { 1f }
    private var p = 0; private var g = 1f; private var hold = 0
    @Volatile var clipped = 0L      // samples the final clamp had to touch, should stay 0
    @Volatile var reductionDb = 0f  // deepest gain reduction in the last call
    fun process(x: FloatArray): ShortArray {
        val out = ShortArray(x.size); var minG = 1f
        var i = 0
        while (i + 1 < x.size) {
            val l = x[i] * gain / 32768f; val r = x[i + 1] * gain / 32768f
            val pk = maxOf(abs(l), abs(r))
            need[p] = if (pk > ceil) ceil / pk else 1f
            var t = 1f; for (v in need) if (v < t) t = v                  // lowest gain needed within the lookahead
            if (t < g) { g += (t - g) * 0.2f; hold = 480 } else if (hold > 0) hold-- else g += (1 - g) * rel
            if (g < minG) minG = g
            val dl0 = dl[2 * p]; val dl1 = dl[2 * p + 1]; dl[2 * p] = l; dl[2 * p + 1] = r
            p = (p + 1) % la
            out[i] = pcm(dl0 * g); out[i + 1] = pcm(dl1 * g)
            i += 2
        }
        reductionDb = (20 * kotlin.math.log10(minG.toDouble())).toFloat()
        return out
    }
    private fun pcm(v: Float): Short { val s = v * 32767f; if (s > 32767f || s < -32768f) clipped++; return s.coerceIn(-32768f, 32767f).toInt().toShort() }
}
