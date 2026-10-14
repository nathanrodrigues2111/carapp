package com.lazyneil.lazycar.audio

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
        @Volatile var lagMs = 0         // best case phone mixer to our arrival (capture + network)
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
    class Output(val name: String, type: Int, ctx: Context, usage: Int = AudioAttributes.USAGE_MEDIA, private val bufMs: Int = 200,
                 /** ms from a chunk's arrival to its deadline; the track buffer is sized to fit inside it. */
                 private val budgetMs: () -> Int = { 1000 }, val extraMs: () -> Double) {
        @Volatile var protect = false   // too many glitches: -6 dB until 10 s without one (never silence: that stuck muted for minutes)
        /** Lower music for directions: -12 dB, 100 ms down, 300 ms up. */
        @Volatile var duck = false
        private var duckG = 1f
        private val jb = TreeMap<Int, Chunk>()
        @Volatile var aheadMs = 0       // queued plus in the track, ms of audio ahead of the speaker
        @Volatile var depthMs = 0       // jitter protection: reorder buffer plus unplayed client buffer
        @Volatile var errMs = 0.0       // smoothed playout minus deadline, positive = late
        @Volatile var ppm = 0           // current resample correction
        @Volatile var concealed = 0
        @Volatile var dropped = 0
        @Volatile var mutes = 0         // fail-safe silences
        @Volatile var realigns = 0      // 10 ms crossfade jumps to fix big sync errors
        @Volatile var lateMs = 0        // played this much behind the JBL on purpose, for jitter cover
        val underruns get() = track.underrunCount
        val device: AudioDeviceInfo? = ctx.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == type }
        private val minBuf = AudioTrack.getMinBufferSize(Wire.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        private val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Wire.RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            // Big track buffer, normal mixer: a busy device (app launch, GC, Maps) can stall our thread for
            // [bufMs] without an underrun. The shared 450 ms timeline leaves room for it.
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)   // the normal mixer path adds ~80 ms of HAL latency here
            .setBufferSizeInBytes(maxOf(minBuf, Wire.RATE * 4 * (bufMs + 50) / 1000))
            .build().apply { setPreferredDevice(device); setVolume(1f) }
        // Filled to bufMs; grows on underruns up to the capacity.
        private var fill = (Wire.RATE * bufMs / 1000).coerceAtMost(track.bufferCapacityInFrames).also { track.bufferSizeInFrames = it }
        val bufferMs get() = track.bufferSizeInFrames * 1000 / Wire.RATE
        val routed get() = track.routedDevice?.productName?.toString() + " (" + (track.routedDevice?.type ?: -1) + ")"
        /** Mono 8 kHz copy of what we wrote, for the mic correlation. Index = written frames / 6. */
        val ref = FloatArray(1 shl 16)
        @Volatile var refEnd = 0L
        private val ts = AudioTimestamp()
        @Volatile private var nextSeq = -1
        @Volatile var written = 0L
        @Volatile var outRms = 0        // level actually written to the track, 0 = we are silent
        private var outSq = 0.0; private var outN = 0
        fun outLevel(): Int { outRms = kotlin.math.sqrt(outSq / maxOf(outN, 1)).toInt(); outSq = 0.0; outN = 0; return outRms }
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
            // Fail-safe: any gap or a near empty buffer fades out over 5 ms and stays silent (timeline keeps running)
            // until the buffer has been healthy for 1 s, then fades in over 50 ms. Silence instead of crackle.
            var sizedAt = 0L; var spareSince = 0L
            var muted = false; var healthySince = 0L; var fadeIn = 0; var fadeLen = Wire.RATE / 20
            // Speaker protection: more than 3 glitches in 10 s lowers the level 6 dB until 10 s pass without one.
            val events = LongArray(4); var ev = 0; var mutedAt = 0L
            fun glitch() { val t = SystemClock.elapsedRealtime(); events[ev++ and 3] = t; healthySince = 0; if (events.all { t - it < 10_000 }) protect = true }
            var dec = 0f; var decN = 0; var seenUnder = 0
            fun write(s: ShortArray) {
                written += track.write(s, 0, s.size).coerceAtLeast(0) / 2
                for (i in s.indices step 8) { val v = s[i].toDouble(); outSq += v * v; outN++ }
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
                if (track.underrunCount > seenUnder) { seenUnder = track.underrunCount; glitch() }
                // Track write ahead = what is left of the budget after the HAL latency and 40 ms for network jitter.
                if (now - sizedAt > 1_000_000_000L) { sizedAt = now
                    val hal = (aheadMs - client).coerceIn(0, 300)
                    // The speaker's latency (phoneOut) is the budget. If what is left after the HAL cannot cover the measured
                    // jitter (targetMs), play late behind the JBL instead of dropping out. 20 ms steps, so no realign every second.
                    // Grows at once (+10 ms margin), shrinks only after 5 min with 30 ms spare: each change is a realign.
                    val l = (targetMs - (budgetMs() + extraMs().toInt() - hal)).coerceIn(0, 250)
                    if (l > lateMs) { lateMs = l + 10; spareSince = 0 }
                    else if (l < lateMs - 30) { if (spareSince == 0L) spareSince = now else if (now - spareSince > 300_000_000_000L) { lateMs = l + 10; spareSince = 0 } }
                    else spareSince = 0
                    val want = (Wire.RATE * (budgetMs() + extraMs().toInt() + lateMs - hal - 40).coerceIn(20, bufMs) / 1000).coerceAtMost(track.bufferCapacityInFrames)
                    if (abs(want - fill) > Wire.RATE / 200) { fill = want; track.bufferSizeInFrames = fill }
                }
                val c = take()
                if (c == null) {
                    // Nothing in time: conceal with the last frame, fading, before the track runs dry.
                    // First gap chunk: the last chunk played backwards (starts where the audio left off) fading to 0, then silence. No step, no click.
                    // A gap with 20 ms of later packets queued is a loss, not reordering: conceal now instead of draining the track.
                    val lostOne = synchronized(jb) { jb.size >= 4 }
                    if (last != null && (client < 15 || lostOne)) {
                        val l = last; val n = l.size / 2
                        write(lim.process(FloatArray(l.size) { if (fades > 0) 0f else l[2 * (n - 1 - it / 2) + it % 2] * (1 - (it / 2) / n.toFloat()) }))
                        // A lost packet (later ones already queued) is 5 ms: conceal it and fade the next one in, no mute.
                        // Only an empty queue (the link stalled) mutes.
                        if (lostOne) { fadeIn = Wire.RATE / 200; fadeLen = fadeIn; concealed++; skip(); continue }
                        if (fades == 0 && !muted) { muted = true; mutes++; healthySince = 0; glitch() }
                        fades++; concealed++; skip(); healthySince = 0
                    } else Thread.sleep(2)
                    continue
                }
                val deadline = c.jblNs + ((extraMs() + lateMs) * 1e6).toLong()
                val e = (playNow - deadline) / 1e6
                // Far off (start, stall, big clock step): jump once, then micro resampling keeps it there.
                // The JBL latency is the whole budget: playing late to grow the buffer only trades sync for drops later.
                errMs = errMs * 0.98 + e * 0.02
                // Off by more than drift can fix (start, stall, clock step): one re-align. This chunk fades out over
                // 5 ms, we skip (late) or pad silence (early) by the error, the next chunk fades in over 5 ms.
                if (abs(e) > 50 || (abs(errMs) > 8 && abs(e) > 8)) {
                    val y = rs.process(c.pcm, 1.0); val yn = y.size / 2
                    for (i in 0 until yn) { val g = 1 - i / yn.toFloat(); y[2 * i] *= g; y[2 * i + 1] *= g }
                    if (muted || fades > 0) y.fill(0f)
                    write(lim.process(y)); last = c.pcm; realigns++
                    if (e > 0) { var k = ((e - Wire.CHUNK_MS) / Wire.CHUNK_MS).toInt(); while (k-- > 0 && depthMs - Wire.CHUNK_MS > 30) { skip(); depthMs -= Wire.CHUNK_MS; dropped++ } }
                    else write(ShortArray(((-e - Wire.CHUNK_MS).coerceIn(0.0, 600.0) * Wire.RATE / 1000).toInt() * 2))
                    errMs = 0.0; fadeIn = maxOf(fadeIn, Wire.RATE / 200); fadeLen = Wire.RATE / 200
                    continue
                }
                // Drift only: at most 500 ppm. Big errors take the jump above instead of pinning the resampler.
                // Drift only, inaudible: at most 100 ppm (0.1 ms per second), 20 ppm per ms of error.
                val r = if (abs(errMs) < 1) 1.0 else 1 + (errMs / 50000).coerceIn(-0.0001, if (depthMs < 30) 0.0 else 0.0001)
                ppm = ((r - 1) * 1e6).toInt()
                val x = rs.process(c.pcm, r)
                val fr = x.size / 2
                val t = SystemClock.elapsedRealtime()
                if (protect && t - events[(ev - 1) and 3] > 10_000) protect = false
                if (!muted && depthMs < 20) { for (i in x.indices) x[i] *= 1 - (i / 2) / fr.toFloat(); muted = true; mutes++; healthySince = 0; glitch() }
                else if (muted) {
                    // Back after 300 ms of a usable buffer, and never more than 2 s of silence while audio arrives:
                    // the old 60 ms for 1 s (5 s when protecting) rule never held with 70 to 130 ms jitter and stayed silent.
                    if (mutedAt == 0L) mutedAt = t
                    if (depthMs < 30) healthySince = 0 else if (healthySince == 0L) healthySince = t
                    if ((healthySince != 0L && t - healthySince >= 300) || t - mutedAt >= 2000) { fadeIn = Wire.RATE / 20; fadeLen = fadeIn; muted = false; mutedAt = 0L }
                    else x.fill(0f)
                }
                if (!muted && fadeIn > 0) for (i in 0 until fr) { val g = 1 - (fadeIn - i).coerceAtLeast(0) / fadeLen.toFloat(); x[2 * i] *= g; x[2 * i + 1] *= g }
                if (fadeIn > 0) fadeIn = (fadeIn - fr).coerceAtLeast(0)
                val dt = (if (duck) 0.25f else 1f) * (if (protect) 0.5f else 1f)
                if (duckG != dt || dt != 1f) for (i in 0 until fr) {
                    duckG = if (duckG > dt) maxOf(dt, duckG - 0.75f / (Wire.RATE / 10)) else minOf(dt, duckG + 0.75f / (Wire.RATE * 0.3f))
                    x[2 * i] *= duckG; x[2 * i + 1] *= duckG
                }
                write(lim.process(x))
                last = c.pcm; fades = 0
            }
            track.stop(); track.release()
        }
    }

    @Volatile private var alive = false
    @Volatile private var navDuck = false
    private var playback: AudioManager.AudioPlaybackCallback? = null
    private var socket: DatagramSocket? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wifiLow: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        // No LOW_LATENCY lock: it only applies while our activity is in front, so every app switch toggled the
        // radio mode and stalled packets 150 to 3000 ms. HIGH_PERF alone keeps power save off all the time.
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carapp:speaker").apply { acquire() }
        val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; receiveBufferSize = 1 shl 20; trafficClass = 0xB8; bind(InetSocketAddress(Wire.TABLET_PORT)) }   // DSCP EF: WMM voice queue
        socket = s
        val ctx = this
        // An older build let this run away to the clamp; anything beyond the new bound restarts from 0.
        acousticMs = TabletSpeaker.acoustic(ctx).toDouble().let { if (abs(it) > Acoustic.MAX_MS) { TabletSpeaker.saveAcoustic(ctx, 0); 0.0 } else it }
        synchronized(outputs) {
            outputs.clear()
            outputs += Output("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ctx, budgetMs = { phoneOutMs - lagMs }) { acousticMs + TabletSpeaker.delayMs(ctx) }
            if (TabletSpeaker.btToo(ctx)) outputs += Output("bluetooth", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, ctx) { 0.0 }
            outputs.forEach { it.run { alive } }
        }
        Acoustic(ctx, outputs[0]) { alive }.start()
        // Maps (or anything) speaking directions here: duck our output and tell the phone so the JBL dips too.
        val am = getSystemService(AudioManager::class.java)
        fun nav() { val on = com.lazyneil.lazycar.Ui.duckNav(ctx) && am.activePlaybackConfigurations.any {
                it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE && "state:started" in it.toString() }
            if (on != navDuck) Log.e("LazyCar", "speaker duck $on")
            navDuck = on; outputs.forEach { it.duck = on } }
        playback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(c: MutableList<android.media.AudioPlaybackConfiguration>) = nav()
        }.also { am.registerAudioPlaybackCallback(it, null) }
        thread(name = "speaker-beacon") {
            val hello = ByteArray(15).also { Wire.MAGIC.toByteArray().copyInto(it) }
            var k = 0
            while (alive) {
                ByteBuffer.wrap(hello).putLong(6, System.nanoTime()); hello[14] = if (navDuck) 1 else 0   // 14 bytes unless ducking: older phone builds only accept 14
                // Unicast to the phone once it is known (a power saving client only counts unicast as activity), broadcast every 1 s to find it.
                val to = if (phone.isNotEmpty() && SystemClock.elapsedRealtime() - lastAt < 2000 && k++ % 50 != 0) setOf(InetAddress.getByName(phone)) else broadcastTargets()
                for (a in to) try { s.send(DatagramPacket(hello, if (navDuck) 15 else 14, a, Wire.PHONE_PORT)) } catch (_: Exception) {}
                Thread.sleep(20)    // keep-alive: our own transmits keep the Wi-Fi out of power save (else the AP holds frames to the ~100 ms beacon); also carries the duck flag
            }
        }
        thread(name = "speaker-net") {
            urgent()
            val buf = ByteArray(Wire.HEADER + Wire.CHUNK * 2)
            val bb = ByteBuffer.wrap(buf)
            val clock = Clock()
            var prevArr = 0L; var gapMax = 0
            var last = -1; var bytes = 0L; var secAt = SystemClock.elapsedRealtime(); var sq = 0.0; var n = 0
            while (alive) try {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                val len = p.length - Wire.HEADER
                if (len <= 0 || len % 4 != 0) continue
                val t3 = System.nanoTime()
                if (prevArr != 0L) gapMax = maxOf(gapMax, ((t3 - prevArr) / 1_000_000).toInt()); prevArr = t3
                val seq = bb.getInt(0)
                val now = SystemClock.elapsedRealtime()
                if (now - lastAt > 2000 || seq < last - 1000) {                      // fresh stream (phone restarted)
                    last = seq - 1; clock.reset(); outputs.forEach { it.reset() }
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
                    rms = kotlin.math.sqrt(sq / maxOf(n, 1)).toInt(); sq = 0.0; n = 0; val g = gapMax; gapMax = 0
                    Log.e("LazyCar", "speaker recv kbps=$kbps gap=$g rms=$rms rtt=${"%.1f".format(rttMs)} jitter=${"%.1f".format(jitterMs)} target=$targetMs phoneOut=$phoneOutMs lag=$lagMs acoustic=${"%.0f".format(acousticMs)} lost=$lost " +
                        outputs.joinToString { "${it.name} ahead=${it.aheadMs} depth=${it.depthMs} err=${"%.1f".format(it.errMs)} ppm=${it.ppm} under=${it.underruns} plc=${it.concealed} drop=${it.dropped} mute=${it.mutes} realign=${it.realigns} late=${it.lateMs} clip=${it.lim.clipped} out=${it.outLevel()} prot=${it.protect} gr=${"%.1f".format(it.lim.reductionDb)} buf=${it.bufferMs} -> ${it.routed}" })
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
            minLagNs = win.minOf { it[3] }; lagMs = (minLagNs / 1_000_000).toInt()
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
        playback?.let { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(it) }; playback = null
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
 * Unity gain (bit exact while nothing goes over) into a stereo linked peak limiter at -1 dBFS: 1 ms lookahead, ~0.2 ms attack, 10 ms hold, 150 ms release.
 * Gain moves smoothly, so loud passages get quieter instead of distorted (the old per sample tanh crackled).
 */
class Limiter(private val gain: Float = 1f) {
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
            if (t < g) { g += (t - g) * 0.2f; hold = 480 } else if (hold > 0) hold-- else if (g < 1f) { g += (1 - g) * rel; if (g > 0.999f) g = 1f }
            if (g < minG) minG = g
            val dl0 = dl[2 * p]; val dl1 = dl[2 * p + 1]; dl[2 * p] = l; dl[2 * p + 1] = r
            p = (p + 1) % la
            out[i] = pcm(dl0 * g); out[i + 1] = pcm(dl1 * g)
            i += 2
        }
        reductionDb = (20 * kotlin.math.log10(minG.toDouble())).toFloat()
        return out
    }
    private fun pcm(v: Float): Short { val s = v * 32768f; if (s > 32767f || s < -32768f) clipped++; return s.coerceIn(-32768f, 32767f).toInt().toShort() }
}
