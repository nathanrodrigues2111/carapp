package com.lazyneil.lazycar.audio

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.lazyneil.lazycar.R
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Phone side: captures media playback (what goes to the JBL) and streams raw PCM to the tablet. */
class StreamSender : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var tablet = ""       // tablet ip once its beacon arrives
        @Volatile var kbps = 0
        @Volatile var rms = 0           // capture level, 0 = silence (app blocks capture or nothing playing)
        @Volatile var error = ""
        @Volatile var outMs = 0         // mixer to JBL latency, from the monitor track
        @Volatile var capMs = 0         // mixer to our read() latency, from the AudioRecord timestamp
        @Volatile var halMs = -1        // hidden AudioManager.getOutputLatency(STREAM_MUSIC), cross-check only
        @Volatile var afMs = -1         // hidden AudioTrack.getLatency() minus the client buffer, includes the A2DP delay
        @Volatile var tsMs = -1         // how far the monitor timestamp trails the mixer read position
        /** Shared timeline: CarApp plays the JBL itself, [Wire.SHARED_MS] after capture, on the accessibility stream,
         *  with STREAM_MUSIC at 0 so the apps' direct path is silent. Off ([Wire.SHARED_ON]). */
        @Volatile var shared = false
        @Volatile var jbl: StreamReceiver.Output? = null
        @Volatile var duckRemote = false
        @Volatile var sinkAf = -1       // its reported latency (getLatency), the calibration's signature
        @Volatile var sinkKey = 0       // hash of the Bluetooth sink's calibration key, sent so the tablet keeps one correction per sink

        /** The user's "Start" on the screen share consent, asked by GO while CarApp is in front, used once at the end of GO. */
        @Volatile private var granted: Intent? = null
        @Volatile private var asking = false   // the dialog is up: its answer starts or holds the capture
        /** GO tap (an activity): the system consent dialog now, so the background GO end can start capturing. */
        fun askConsent(a: Activity, thenBt: Boolean = false) {
            if (!running && granted == null) a.startActivity(Intent(a, Consent::class.java).putExtra("thenBt", thenBt))
            else if (thenBt) com.lazyneil.lazycar.Ui.openSettings(a, Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), fallback = false)
        }

        /** GO end: needs the MediaProjection consent, which only an activity can ask for. */
        fun start(ctx: Context) {
            if (running || !TabletSpeaker.needsCapture(ctx)) return   // CarApp's player streams itself; others only when opted in
            // No Bluetooth speaker on the phone: music plays on the phone itself, a tablet copy would echo.
            if (ctx.getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    .none { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) { error = "Connect your speaker"; granted = null; Log.e("LazyCar", "speaker send: no Bluetooth speaker, not streaming"); return }
            if (asking) return
            val g = granted; granted = null
            if (g != null) try { ctx.startForegroundService(Intent(ctx, StreamSender::class.java).putExtra("data", g)); return }
                catch (e: Exception) { Log.e("LazyCar", "speaker send start", e) }
            ctx.startActivity(Intent(ctx, Consent::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))   // in front only: else Android blocks it
        }

        /** Tablet mode speaker level: CarPlayer's speaker copy (capture copies are blocked on ZUI: any speaker-pinned
         *  track is rebuilt ~8/s while playback capture runs). 0..100, squared. */
        fun speakerGain(pct: Int) { CarPlayer.speaker?.gain = (pct.coerceIn(0, 100) / 100f).let { it * it } }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, StreamSender::class.java)) }
    }

    /** Invisible: asks mic permission if missing, then the capture consent (auto-granted with appops PROJECT_MEDIA allow). */
    class Consent : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            asking = true
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            else ask()
        }
        override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
            if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) ask() else { error = "Microphone permission needed"; asking = false; finish() }
        }
        // The consent is per session on Android 14+: the standard system dialog, the user taps Start.
        private fun ask() { com.lazyneil.lazycar.SplitService.press("Start now", "Share screen", "Start"); val mpm = getSystemService(MediaProjectionManager::class.java)
            // Whole display, so the dialog skips the "Share one app" picker.
            startActivityForResult(if (android.os.Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent(), 2) }
        override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
            // GO still running: hold it for GO's end (the speaker must be connected first). Asked later: start now.
            if (res == RESULT_OK && data != null) {
                if (com.lazyneil.lazycar.GoAction.effectiveState(com.lazyneil.lazycar.Prefs(this)) == com.lazyneil.lazycar.GoAction.STARTING) granted = data
                else startForegroundService(Intent(this, StreamSender::class.java).putExtra("data", data))
            } else error = "Capture consent denied"
            asking = false
            if (intent.getBooleanExtra("thenBt", false)) com.lazyneil.lazycar.Ui.openSettings(this, Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), fallback = false)
            finish()
        }
    }

    @Volatile private var alive = false
    private var projection: MediaProjection? = null
    private var net: Net? = null
    private var lock: WifiManager.MulticastLock? = null
    private var wifiLow: WifiManager.WifiLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra("data", Intent::class.java) ?: run { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        startForeground(com.lazyneil.lazycar.GoService.NOTIF_ID, Notification.Builder(this, com.lazyneil.lazycar.GoService.channel(this)).setContentTitle("Tablet speaker")
            .setContentText("Streaming media audio to the tablet").setSmallIcon(R.drawable.ic_car_glyph).addAction(com.lazyneil.lazycar.GoService.shutAction(this)).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
            // Capture ended from outside (the cast chip, the system): it stays off until the next GO.
            mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, Handler(Looper.getMainLooper()))
            projection = mp
            alive = true; running = true; error = ""; tablet = ""
            lock = getSystemService(WifiManager::class.java).createMulticastLock("speaker").apply { acquire() }
            wifiLow = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "speaker-ll").apply { acquire() }
            // Low latency only applies while our activity is in front; high perf keeps Wi-Fi power save off in the background too.
            @Suppress("DEPRECATION")
            wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "speaker").apply { acquire() }
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carapp:speaker").apply { acquire() }
            capture(mp)
        } catch (e: Exception) { error = e.message ?: e.toString(); Log.e("LazyCar", "speaker send", e); stopSelf() }
        return START_NOT_STICKY
    }

    private fun capture(mp: MediaProjection) {
        startShared()
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // Shared: music volume is 0, so phone Maps prompts only play through us (450 ms late, both outputs).
            .apply { if (shared) addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) }.build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Wire.RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val rec = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg)
            // 40 ms, not 80: capMs showed the read side sitting a full buffer behind, and every ms here is jitter headroom the tablet loses.
            .setBufferSizeInBytes(maxOf(Wire.CHUNK * 8, AudioRecord.getMinBufferSize(Wire.RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT))).build()
        val net = Net { jbl?.duck = it }
        this.net = net
        thread(name = "speaker-capture") {
            StreamReceiver.urgent()
            rec.startRecording()
            val pkt = ByteArray(Wire.HEADER + Wire.CHUNK)
            val bb = ByteBuffer.wrap(pkt)
            var prevRd = 0L; var gapMax = 0
            var seq = 0; var bytes = 0L; var sq = 0.0; var n = 0; var secAt = SystemClock.elapsedRealtime()
            val pool = Array(512) { ShortArray(Wire.CHUNK / 2) }   // JBL copies, reused (the JBL queue holds < 1 s)
            var readFrames = 0L; val rts = android.media.AudioTimestamp(); var tsAt = 0L; var haveTs = false
            val capAnchors = ArrayDeque<Long>(); var capAnchor = 0L
            while (alive) {
                val r = rec.read(pkt, Wire.HEADER, Wire.CHUNK)
                if (r <= 0) { if (r < 0) { error = "Capture read $r"; break }; continue }
                val tr = System.nanoTime(); if (prevRd != 0L) gapMax = maxOf(gapMax, ((tr - prevRd) / 1_000_000).toInt()); prevRd = tr
                for (i in 0 until r / 2) { val v = java.lang.Short.reverseBytes(bb.getShort(Wire.HEADER + i * 2)).toInt(); sq += v * v.toDouble() }   // PCM is little endian
                val now = SystemClock.elapsedRealtime()
                if (now - tsAt > 100) { tsAt = now; haveTs = rec.getTimestamp(rts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                    // Median of 4 s of (time - frame) anchors: a raw capture timestamp steps by ms, each step moved every tablet deadline.
                    if (haveTs) { capAnchors.addLast(rts.nanoTime - rts.framePosition * 1_000_000_000L / Wire.RATE); if (capAnchors.size > 41) capAnchors.removeFirst()
                        capAnchor = capAnchors.sorted()[capAnchors.size / 2] } }
                // Mixer time of this chunk's first frame, so the tablet gets a smooth timeline despite bursty reads.
                val mixNs = if (haveTs) capAnchor + readFrames * 1_000_000_000L / Wire.RATE else System.nanoTime() - capMs * 1_000_000L
                n += r / 2; readFrames += r / 4
                if (net.send(pkt, r, seq, mixNs, if (shared) Wire.SHARED_MS else outMs)) bytes += Wire.HEADER + r
                seq++
                jbl?.let { o ->
                    val sh = if (r == Wire.CHUNK) pool[(seq - 1) and 511] else ShortArray(r / 2)
                    ByteBuffer.wrap(pkt, Wire.HEADER, r).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
                    o.put(StreamReceiver.Chunk(seq - 1, sh, mixNs + Wire.SHARED_MS * 1_000_000L))
                }
                if (now - secAt >= 1000) {
                    rms = sqrt(sq / maxOf(n, 1)).toInt(); kbps = (bytes * 8 / (now - secAt)).toInt()
                    if (haveTs)
                        capMs = ((rts.framePosition - readFrames) * 1000 / Wire.RATE + (System.nanoTime() - rts.nanoTime) / 1_000_000).toInt()
                    Log.e("LazyCar", "speaker send gap=$gapMax rms=$rms kbps=$kbps out=$outMs ts=$tsMs af=$afMs hal=$halMs cap=$capMs tablet=$tablet")
                    sq = 0.0; n = 0; bytes = 0; secAt = now; gapMax = 0
                }
            }
            rec.stop(); rec.release()
            if (alive) Handler(Looper.getMainLooper()).post { stopSelf() }   // read error: stop, so start() can run again
        }
        // Silent media track on the same route as the music: [SinkClock] gives the phone mixer to speaker latency by math.
        thread(name = "speaker-monitor") {
            val t = android.media.AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Wire.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).build()
            val zero = ByteArray(Wire.CHUNK * 4); var at = 0L; val clk = SinkClock(t)
            t.play()
            while (alive) {
                t.write(zero, 0, zero.size); clk.update()
                val now = SystemClock.elapsedRealtime()
                if (now - at > 1000) { at = now
                    halMs = try { android.media.AudioManager::class.java.getMethod("getOutputLatency", Int::class.java)
                        .invoke(getSystemService(android.media.AudioManager::class.java), android.media.AudioManager.STREAM_MUSIC) as Int } catch (_: Exception) { -1 }
                    afMs = clk.afMs; sinkAf = afMs; tsMs = clk.downMs.toInt()
                    clk.outMs()?.let { outMs = it }
                    sinkKey = SinkClock.key(t.routedDevice).let { if (it.isEmpty()) 0 else ("p$it").hashCode() }
                }
            }
            t.stop(); t.release()
        }
    }

    private var volGuard: android.content.BroadcastReceiver? = null

    private fun startShared() {
        restoreMusic()   // a crash may have left music muted
        if (!Wire.SHARED_ON) return   // ponytail: off until tested on the phone (installs need a tap on the phone)
        val am = getSystemService(android.media.AudioManager::class.java)
        if (am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).none { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) { Log.e("LazyCar", "speaker shared: no A2DP output, playing direct"); return }
        val p = getSharedPreferences("speaker", MODE_PRIVATE)
        val m = android.media.AudioManager.STREAM_MUSIC; val a = android.media.AudioManager.STREAM_ACCESSIBILITY
        val mMax = am.getStreamMaxVolume(m)
        // OnePlus ignores setStreamVolume on music from apps (OPLUS ADJUSTVOLUME_FORBID), but mute works and the
        // capture stays full scale while muted. Our level goes on the accessibility stream.
        val saved = am.getStreamVolume(m)
        p.edit().putBoolean("musicMuted", true).apply()   // a crash leaves music muted: the next start or stop unmutes
        am.adjustStreamVolume(a, android.media.AudioManager.ADJUST_SAME, 0)
        am.setStreamVolume(a, Math.round(saved * am.getStreamMaxVolume(a) / mMax.toFloat()).coerceAtLeast(1), 0)
        am.adjustStreamVolume(m, android.media.AudioManager.ADJUST_MUTE, 0)
        if (!am.isStreamMute(m) || am.isStreamMute(a)) {
            Log.e("LazyCar", "speaker shared: music mute ${am.isStreamMute(m)}, accessibility mute ${am.isStreamMute(a)}, playing direct"); restoreMusic(); return
        }
        shared = true
        jbl = StreamReceiver.Output("jbl", android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, this,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, bufMs = 150, budgetMs = { Wire.SHARED_MS - capMs }) { 0.0 }.also { it.run { alive } }
        // Anything that raises music (keys the key filter missed, GO's start volume, OnePlus resets) goes back to 0;
        // a one or two step raise is the user, so it moves our level instead.
        volGuard = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!shared || i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != m || am.isStreamMute(m)) return
                am.adjustStreamVolume(m, android.media.AudioManager.ADJUST_MUTE, 0)   // unmuted by keys, GO or a reset
            }
        }.also { androidx.core.content.ContextCompat.registerReceiver(this, it,
            android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED) }
        Log.e("LazyCar", "speaker shared: on, music $saved/$mMax muted -> accessibility ${am.getStreamVolume(a)}/${am.getStreamMaxVolume(a)}")
    }

    /** Music back to the level our accessibility stream was at, so the direct path continues seamlessly. */
    private fun restoreMusic() {
        val am = getSystemService(android.media.AudioManager::class.java)
        val p = getSharedPreferences("speaker", MODE_PRIVATE)
        val m = android.media.AudioManager.STREAM_MUSIC; val a = android.media.AudioManager.STREAM_ACCESSIBILITY
        if (p.getBoolean("musicMuted", false) || shared) am.adjustStreamVolume(m, android.media.AudioManager.ADJUST_UNMUTE, 0)
        p.edit().remove("musicMuted").apply()
    }

    override fun onDestroy() {
        volGuard?.let { unregisterReceiver(it) }; volGuard = null
        restoreMusic(); shared = false; jbl = null
        alive = false; running = false; tablet = ""; kbps = 0; rms = 0
        net?.close(); net = null
        projection?.stop(); projection = null
        lock?.release(); lock = null
        wifiLow?.release(); wifiLow = null
        wifi?.release(); wifi = null
        wake?.release(); wake = null
        super.onDestroy()
    }
}

/** Phone to tablet UDP link: answers the tablet's beacons and sends stamped PCM packets. Capture and CarPlayer share it.
 *  [send] gets a packet with the PCM at [Wire.HEADER]; out = phone to speaker ms, or -(exact ms to the deadline) - 1. */
class Net(private val onDuck: (Boolean) -> Unit = {}) {
    @Volatile private var alive = true
    @Volatile private var dest: SocketAddress? = null
    @Volatile private var beaconT = 0L; @Volatile private var beaconAt = 0L; @Volatile private var beaconNs = 0L
    private val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(Wire.PHONE_PORT)) }
    private val dp = DatagramPacket(ByteArray(1), 1)
    init {
        thread(name = "speaker-beacon") {   // tablet beacons once a second; its source address is where audio goes
            val buf = ByteArray(64)
            while (alive) try {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                if (p.length >= 14 && String(buf, 0, 6) == Wire.MAGIC) {
                    StreamSender.duckRemote = p.length >= 15 && buf[14] == 1.toByte(); onDuck(StreamSender.duckRemote)
                    beaconNs = System.nanoTime(); beaconT = ByteBuffer.wrap(buf, 6, 8).long; beaconAt = SystemClock.elapsedRealtime()
                    dest = p.socketAddress; StreamSender.tablet = p.address.hostAddress ?: ""
                }
            } catch (_: Exception) {}
        }
    }
    fun send(pkt: ByteArray, len: Int, seq: Int, mixNs: Long, out: Int): Boolean {
        val d = dest
        if (d == null || SystemClock.elapsedRealtime() - beaconAt >= 5000) { StreamSender.tablet = ""; return false }
        ByteBuffer.wrap(pkt).putInt(0, seq).putLong(4, beaconT).putLong(12, beaconNs).putLong(20, System.nanoTime()).putLong(28, mixNs).putInt(36, out).putInt(40, StreamSender.sinkKey).putInt(44, StreamSender.sinkAf)
        return try { dp.setData(pkt, 0, Wire.HEADER + len); dp.socketAddress = d; s.send(dp); true } catch (_: Exception) { false }
    }
    fun close() { alive = false; s.close(); StreamSender.tablet = "" }
}

/** Shared wire format: tablet beacons "CARSPK"+time to PHONE_PORT, phone answers with PCM packets. */
object Wire {
    const val MAGIC = "CARSPK"
    const val PHONE_PORT = 47476
    const val TABLET_PORT = 47475
    const val RATE = 48000
    const val CHUNK = 960          // 5 ms of 48 kHz stereo 16-bit, fits one Wi-Fi frame
    const val CHUNK_MS = 5
    const val FORMAT = "lossless PCM 48 kHz 16-bit stereo"
    const val SHARED_ON = false   // blocked: JBL uses absolute volume (music index = JBL hardware level), see commit
    const val SHARED_MS = 450      // capture to speaker on both outputs in shared mode; JBL A2DP ~250 + room for stalls
    const val HEADER = 48          // seq int, echoed tablet beacon ns, phone ns at beacon, phone ns at send, phone mixer ns of frame 0, phone to speaker ms int, sink key hash int, sink getLatency ms int
}
