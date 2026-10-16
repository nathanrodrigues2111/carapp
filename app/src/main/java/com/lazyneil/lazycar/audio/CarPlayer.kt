package com.lazyneil.lazycar.audio

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.util.Log
import com.lazyneil.lazycar.R
import kotlin.concurrent.thread

/**
 * Tablet player "CarApp (both speakers)": plays the tablet's music files to the JBL and the built-in speaker from one
 * decode. The JBL track sets the clock; the speaker is a [StreamReceiver.Output] fed chunks stamped with the time the JBL
 * plays them ([SinkClock] math), so the existing drift resampling, limiter and fades keep it on the JBL. No capture.
 * Controls, Now Playing and the library reach it through its MediaSession like any player.
 */
class CarPlayer : MediaBrowserService() {
    companion object {
        @Volatile var speaker: StreamReceiver.Output? = null
        @Volatile var session: MediaSession? = null
        /** Phone mode on the phone: decoded PCM goes straight to the tablet speaker (no capture). */
        @Volatile var streaming = false
        fun send(ctx: Context, action: String) = try {
            ctx.startForegroundService(Intent(ctx, CarPlayer::class.java).setAction(action))
        } catch (e: Exception) { Log.e("LazyCar", "car player $action", e) }
    }

    private lateinit var session: MediaSession
    @Volatile private var songs: List<Uri> = emptyList()
    private var index = 0
    @Volatile private var playing = false
    @Volatile private var alive = true
    @Volatile private var seekTo = -1L
    @Volatile private var trackStartMs = 0L    // position of the first frame written for this track
    @Volatile private var jblStart = 0L        // JBL frames written before this track
    @Volatile private var durMs = 0L
    private var jbl: AudioTrack? = null
    private var worker: Thread? = null
    private var focus: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "CarPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = pause()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(if (position() > 3000) 0 else -1)
                override fun onSeekTo(pos: Long) { seekTo = pos; state() }
                override fun onPlayFromUri(uri: Uri, extras: Bundle?) = load { index = songs.indexOf(uri).coerceAtLeast(0); open(); play() }
            })
            isActive = true
        }
        sessionToken = session.sessionToken; Companion.session = session
        state()
    }

    override fun onGetRoot(pkg: String, uid: Int, hints: Bundle?) = BrowserRoot("root", null)
    override fun onLoadChildren(id: String, result: Result<MutableList<MediaBrowser.MediaItem>>) = result.sendResult(mutableListOf())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, com.lazyneil.lazycar.GoService.channel(this)).setContentTitle("CarApp player")
            .setSmallIcon(R.drawable.ic_car_glyph).build()
        // Microphone type lets the one time calibration of a new speaker listen; refused from the background, then math only.
        try { startForeground(com.lazyneil.lazycar.GoService.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
        catch (e: Exception) { startForeground(com.lazyneil.lazycar.GoService.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        when (intent?.action) { "play" -> play(); "pause" -> pause() }
        return START_NOT_STICKY
    }

    /** Latest folder list off the main thread (a first scan can take seconds), then [then] on main. Keeps the current song. */
    private fun load(then: () -> Unit) = com.lazyneil.lazycar.media.Files.later(this) { l -> main {
        val cur = songs.getOrNull(index); songs = l.map { it.uri }
        if (cur != null) index = songs.indexOf(cur).coerceAtLeast(0) else if (index >= songs.size) index = 0
        then()
    } }

    private fun uri() = songs[index]

    /** Metadata (and art) for the current song; the decoder restarts on it. */
    private fun open() {
        if (songs.isEmpty()) return
        seekTo = 0
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri())
            durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val art = r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "Track ${index + 1}")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs)
                .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }.build())
        } catch (e: Exception) { Log.e("LazyCar", "car player meta", e) } finally { r.release() }
    }

    private fun skip(d: Int) = load { if (songs.isNotEmpty()) { index = (index + d).mod(songs.size); open(); state() } }

    private fun position(): Long {
        val t = jbl ?: return trackStartMs
        return trackStartMs + ((t.playbackHeadPosition.toLong() and 0xffffffffL) - jblStart).coerceAtLeast(0) * 1000 / Wire.RATE
    }

    private fun state() = session.setPlaybackState(PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY_FROM_URI)
        .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, if (seekTo >= 0) seekTo else position(), if (playing) 1f else 0f).build())

    private fun play() {
        if (songs.isEmpty()) { load { if (songs.isEmpty()) Log.e("LazyCar", "car player: no music files") else play() }; return }
        if (session.controller.metadata == null) open()
        val am = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS) pause() }.build()
            .also { am.requestAudioFocus(it) }
        if (playing) return
        playing = true; state()
        if (worker == null) worker = thread(name = "car-player") { run() }
    }

    private fun pause() {
        if (!playing) return
        playing = false; seekTo = position(); state()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
    }

    /** Decode loop: JBL track (blocking writes = the clock) plus stamped 5 ms chunks for the speaker Output. */
    private fun run() {
        StreamReceiver.urgent()
        val am = getSystemService(AudioManager::class.java)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Wire.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(Wire.RATE * 4 / 5).build()   // not pinned: media already routes to the JBL, and two pinned tracks
        // re-routed each other in a loop (every rebuild of one invalidated the other, ~37 rebuilds/s)
        jbl = t
        // The speaker Output (and Acoustic) live only while playing: a paused player holds no open track.
        var out: StreamReceiver.Output? = null; var outOn = booleanArrayOf(false)
        // Phone: the tablet speaker copy goes over Wi-Fi (Net), stamped like the speaker Output's chunks.
        val phone = !com.lazyneil.lazycar.Role.isTablet(this); var net: Net? = null
        val npk = ByteArray(Wire.HEADER + Wire.CHUNK); val nsb = java.nio.ByteBuffer.wrap(npk, Wire.HEADER, Wire.CHUNK).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        // No Bluetooth speaker on the tablet: the main track already plays on the built-in speaker, a copy would echo.
        fun hasBt() = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        fun openOut(): StreamReceiver.Output? {
            if (!hasBt()) { Log.e("LazyCar", "car player: no Bluetooth speaker, built-in speaker only"); return null }
            if (phone) { if (net == null && TabletSpeaker.enabled(this)) { net = Net(); streaming = true }; return null }
            val on = booleanArrayOf(true); outOn = on
            val o = StreamReceiver.Output("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, this, bufMs = 700, budgetMs = { budget }, safe = true) {
                StreamReceiver.acousticMs + TabletSpeaker.delayMs(this) }
            o.gain = (com.lazyneil.lazycar.media.Volumes.tabletSaved(this) / 100f).let { it * it }
            speaker = o
            synchronized(StreamReceiver.outputs) { StreamReceiver.outputs.clear(); StreamReceiver.outputs += o }
            o.run { alive && on[0] }; Acoustic(this, o, { sink }) { alive && on[0] }.start()
            return o
        }
        fun closeOut() { outOn[0] = false; out = null; speaker = null; net?.close(); net = null; streaming = false
            synchronized(StreamReceiver.outputs) { StreamReceiver.outputs.clear() } }
        val limJ = Limiter()
        var written = 0L; var seq = 0; var halAt = 0L; var hal = 200; val clk = SinkClock(t)
        var sq = 0.0; var n = 0; var secAt = SystemClock.elapsedRealtime()
        val pend = ShortArray(Wire.CHUNK / 2); var pn = 0; var chunkFrame = 0L   // JBL frame index of pend[0]
        var ex: MediaExtractor? = null; var codec: MediaCodec? = null; var rs = Cubic(); var ratio = 1.0; var ch = 2; var cur: Uri? = null
        val info = MediaCodec.BufferInfo()
        t.play()
        while (alive) {
            if (!playing) { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) { t.pause(); closeOut() }; Thread.sleep(50); continue }
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            if ((out != null || net != null) && !hasBt()) closeOut()
            val o = out ?: (if (now0() - outTry > 2000) { outTry = now0(); openOut().also { out = it } } else null)
            try {
                // (Re)open on a song change or seek.
                if (songs.getOrNull(index) != cur || seekTo >= 0) {
                    val to = seekTo.coerceAtLeast(0); seekTo = -1
                    if (songs.getOrNull(index) != cur) { codec?.release(); ex?.release()
                        ex = MediaExtractor().apply { setDataSource(this@CarPlayer, uri(), null) }
                        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                        ex.selectTrack(ti); val f = ex.getTrackFormat(ti)
                        codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!).apply { configure(f, null, null, 0); start() }
                        ratio = f.getInteger(MediaFormat.KEY_SAMPLE_RATE) / Wire.RATE.toDouble(); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT); cur = songs[index]
                    } else codec?.flush()
                    ex!!.seekTo(to * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC); rs = Cubic()
                    t.pause(); t.flush(); t.play(); o?.reset(); pn = 0
                    trackStartMs = to; jblStart = t.playbackHeadPosition.toLong() and 0xffffffffL; written = jblStart; chunkFrame = jblStart; main { state() }
                }
                val c = codec!!; val e = ex!!
                val inIx = c.dequeueInputBuffer(10_000)
                if (inIx >= 0) { val sz = e.readSampleData(c.getInputBuffer(inIx)!!, 0)
                    if (sz < 0) c.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) else { c.queueInputBuffer(inIx, 0, sz, e.sampleTime, 0); e.advance() } }
                val oIx = c.dequeueOutputBuffer(info, 10_000)
                if (oIx < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { c.releaseOutputBuffer(oIx, false); main { skip(1) }; Thread.sleep(100); continue }
                val sb = c.getOutputBuffer(oIx)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val raw = ShortArray(sb.remaining()).also { sb.get(it) }; c.releaseOutputBuffer(oIx, false)
                val st = if (ch == 2) raw else ShortArray(raw.size / ch * 2) { raw[(it / 2) * ch + if (ch > 1) it % 2 else 0] }
                val pcm = limJ.process(rs.process(st, ratio))
                for (v in pcm) { sq += v * v.toDouble() }; n += pcm.size
                // Speaker chunks: stamped with the time the JBL plays their first frame (head + queued + HAL figure).
                val now = SystemClock.elapsedRealtime()
                if (now - halAt > 1000) { halAt = now
                    hal = try { AudioManager::class.java.getMethod("getOutputLatency", Int::class.java).invoke(am, AudioManager.STREAM_MUSIC) as Int } catch (_: Exception) { 200 }
                    // Sink changed: its learned correction (0 until calibrated), the phone tells the tablet which sink it is.
                    val k = SinkClock.key(t.routedDevice).let { if (it.isEmpty()) "" else (if (phone) "p" else "t") + it }
                    if (k != sink) { sink = k; StreamReceiver.acousticMs = TabletSpeaker.cal(this, k)?.toDouble() ?: 0.0; StreamSender.sinkKey = k.hashCode()
                        Log.e("LazyCar", "car player sink '$k' correction ${StreamReceiver.acousticMs} ms") }
                    if (++logN % 5 == 0) Log.e("LazyCar", "car player sync: out=${clk.outMs()} af=${clk.afMs} down=${"%.1f".format(clk.downMs)} hidden=${"%.1f".format(clk.hiddenMs)} " +
                        "correction=${"%.0f".format(StreamReceiver.acousticMs)} speaker err=${o?.let { "%.1f".format(it.errMs) }} realign=${o?.realigns} heard=${"%.0f".format(Acoustic.residualMs)} (${Acoustic.note})") }
                clk.update()
                var i = 0
                while (i < pcm.size) {
                    val k = minOf(pcm.size - i, pend.size - pn); System.arraycopy(pcm, i, pend, pn, k); pn += k; i += k
                    if (pn == pend.size) {
                        val head = t.playbackHeadPosition.toLong() and 0xffffffffL
                        // A rebuilt JBL track (route change) drops what it held: more queued than it can hold means frames were
                        // lost, so re-anchor on the JBL position and re-prime the speaker instead of running early.
                        val lost = (written - head) - t.bufferSizeInFrames - Wire.RATE / 20
                        if (lost > 0) { written -= lost; chunkFrame -= lost; jblStart -= lost; o?.reset(); rebuilds++
                            Log.e("LazyCar", "car player: JBL lost ${lost * 1000 / Wire.RATE} ms, re-primed the speaker") }
                        // When the JBL plays this chunk: track timestamp + what the timestamp misses (no constants); HAL figure until it has one.
                        val atNs = clk.playNs(chunkFrame) ?: (System.nanoTime() + ((chunkFrame - head) * 1_000_000_000L / Wire.RATE) + hal * 1_000_000L)
                        budget = ((atNs - System.nanoTime()) / 1_000_000).toInt()
                        net?.let { nt -> nsb.clear(); nsb.put(pend); val nowNs = System.nanoTime()
                            nt.send(npk, Wire.CHUNK, seq, nowNs, -maxOf(0, ((atNs - nowNs) / 1_000_000).toInt()) - 1) }
                        o?.put(StreamReceiver.Chunk(seq, pend.copyOf(), atNs)); seq++; pn = 0; chunkFrame += pend.size / 2
                    }
                }
                written += t.write(pcm, 0, pcm.size).coerceAtLeast(0) / 2
                StreamReceiver.lastAt = now
                if (now - secAt >= 1000) { StreamReceiver.rms = kotlin.math.sqrt(sq / maxOf(n, 1)).toInt(); sq = 0.0; n = 0; secAt = now; main { state() } }
            } catch (e: Exception) { Log.e("LazyCar", "car player", e); playing = false; main { state(); skip(1) }; Thread.sleep(500) }
        }
        closeOut(); codec?.release(); ex?.release(); t.release(); jbl = null
    }

    @Volatile private var budget = 400
    @Volatile private var sink = ""
    private var logN = 0
    private var outTry = -10_000L
    private fun now0() = SystemClock.elapsedRealtime()
    private var rebuilds = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun main(r: () -> Unit) { handler.post(r) }

    override fun onDestroy() {
        alive = false; playing = false; speaker = null
        synchronized(StreamReceiver.outputs) { StreamReceiver.outputs.clear() }
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        session.release(); super.onDestroy()
    }
}

/** 44.1/48 kHz to 48 kHz for CarPlayer, 4 point cubic (Catmull-Rom): the shared linear [Resampler] only does ppm drift
 *  and dulled the top end at this ratio. Carries 3 frames of history across calls. */
private class Cubic {
    private val h = FloatArray(6); private var frac = 0.0
    fun process(x: ShortArray, ratio: Double): FloatArray {
        val n = x.size / 2
        if (ratio == 1.0) return FloatArray(x.size) { x[it].toFloat() }
        fun s(i: Int, c: Int) = if (i < 0) h[(i + 3) * 2 + c] else x[2 * i + c].toFloat()   // i in -3..n-1
        val out = FloatArray(((n - frac) / ratio + 2).toInt() * 2); var o = 0; var p = frac
        while (p + 1 < n) {   // needs samples i-1 .. i+2 with i = floor(p) - 1 relative to history
            val i = p.toInt() - 1; val t = (p - p.toInt()).toFloat()
            for (c in 0..1) {
                val a = s(i - 1, c); val b = s(i, c); val d = s(i + 1, c); val e = s(i + 2, c)
                out[o++] = b + 0.5f * t * (d - a + t * (2 * a - 5 * b + 4 * d - e + t * (3 * (b - d) + e - a)))
            }
            p += ratio
        }
        frac = p - n
        for (k in 0..2) for (c in 0..1) h[k * 2 + c] = x[2 * (n - 3 + k) + c].toFloat()
        return out.copyOf(o)
    }
}
