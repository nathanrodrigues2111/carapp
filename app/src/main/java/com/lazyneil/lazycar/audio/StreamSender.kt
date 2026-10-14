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
        /** Shared timeline: CarApp plays the JBL itself, [Wire.SHARED_MS] after capture, on the accessibility stream
         *  (own volume via TapService), with STREAM_MUSIC at 0 so the apps' direct path is silent. */
        @Volatile var shared = false
        @Volatile var jbl: StreamReceiver.Output? = null
        @Volatile var duckRemote = false

        /** ms from writing a frame to it leaving the device, from the track's own timestamp. */
        fun latencyMs(t: android.media.AudioTrack, written: Long): Int? {
            val ts = android.media.AudioTimestamp()
            if (!t.getTimestamp(ts)) return null
            val heard = ts.framePosition + (System.nanoTime() - ts.nanoTime) * Wire.RATE / 1_000_000_000
            return ((written - heard) * 1000 / Wire.RATE).toInt()
        }

        /** GO entry: needs the MediaProjection consent, which only an activity can ask for. */
        fun start(ctx: Context) {
            if (running) return
            ctx.startActivity(Intent(ctx, Consent::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, StreamSender::class.java)) }
    }

    /** Invisible: asks mic permission if missing, then the capture consent (auto-granted with appops PROJECT_MEDIA allow). */
    class Consent : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            else ask()
        }
        override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
            if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) ask() else { error = "Microphone permission needed"; finish() }
        }
        // The consent is per session on Android 14+; TapService taps it so GO stays zero-touch.
        private fun ask() = com.lazyneil.lazycar.TapService.armButtons(listOf("Start now", "Share screen", "Start")).let { val mpm = getSystemService(MediaProjectionManager::class.java)
            // Whole display, so the dialog skips the "Share one app" picker.
            startActivityForResult(if (android.os.Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent(), 2) }
        override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
            if (res == RESULT_OK && data != null)
                startForegroundService(Intent(this, StreamSender::class.java).putExtra("data", data))
            else error = "Capture consent denied"
            finish()
        }
    }

    @Volatile private var alive = false
    private var projection: MediaProjection? = null
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var wifiLow: WifiManager.WifiLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra("data", Intent::class.java) ?: run { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        startForeground(com.lazyneil.lazycar.GoService.NOTIF_ID, Notification.Builder(this, com.lazyneil.lazycar.GoService.channel(this)).setContentTitle("Tablet speaker")
            .setContentText("Streaming media audio to the tablet").setSmallIcon(R.drawable.ic_car_glyph).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
            mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() {
                val external = alive   // false when our own onDestroy stopped it
                stopSelf()
                // The system or the cast chip ended the capture mid drive: ask again (TapService taps the consent).
                val ctx = applicationContext
                if (external && com.lazyneil.lazycar.GoAction.effectiveState(com.lazyneil.lazycar.Prefs(ctx)) == com.lazyneil.lazycar.GoAction.ON && TabletSpeaker.enabled(ctx))
                    Handler(Looper.getMainLooper()).postDelayed({ start(ctx) }, 1500)
            } }, Handler(Looper.getMainLooper()))
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
        val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(Wire.PHONE_PORT)) }
        socket = s
        var dest: SocketAddress? = null
        var beaconT = 0L; var beaconAt = 0L; var beaconNs = 0L

        // Tablet beacons once a second; its source address is where audio goes.
        thread(name = "speaker-beacon") {
            val buf = ByteArray(64)
            while (alive) try {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                if (p.length >= 14 && String(buf, 0, 6) == Wire.MAGIC) {
                    duckRemote = p.length >= 15 && buf[14] == 1.toByte(); jbl?.duck = duckRemote
                    beaconNs = System.nanoTime(); beaconT = ByteBuffer.wrap(buf, 6, 8).long; beaconAt = SystemClock.elapsedRealtime()
                    dest = p.socketAddress; tablet = p.address.hostAddress ?: ""
                }
            } catch (_: Exception) {}
        }
        thread(name = "speaker-capture") {
            StreamReceiver.urgent()
            rec.startRecording()
            val pkt = ByteArray(Wire.HEADER + Wire.CHUNK)
            val bb = ByteBuffer.wrap(pkt)
            val dp = DatagramPacket(pkt, pkt.size); var prevRd = 0L; var gapMax = 0   // one packet object, reused
            var seq = 0; var bytes = 0L; var sq = 0.0; var n = 0; var secAt = SystemClock.elapsedRealtime()
            val pool = Array(512) { ShortArray(Wire.CHUNK / 2) }   // JBL copies, reused (the JBL queue holds < 1 s)
            var readFrames = 0L; val rts = android.media.AudioTimestamp(); var tsAt = 0L; var haveTs = false
            while (alive) {
                val r = rec.read(pkt, Wire.HEADER, Wire.CHUNK)
                if (r <= 0) { if (r < 0) { error = "Capture read $r"; break }; continue }
                val tr = System.nanoTime(); if (prevRd != 0L) gapMax = maxOf(gapMax, ((tr - prevRd) / 1_000_000).toInt()); prevRd = tr
                for (i in 0 until r / 2) { val v = java.lang.Short.reverseBytes(bb.getShort(Wire.HEADER + i * 2)).toInt(); sq += v * v.toDouble() }   // PCM is little endian
                val now = SystemClock.elapsedRealtime()
                if (now - tsAt > 100) { tsAt = now; haveTs = rec.getTimestamp(rts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS }
                // Mixer time of this chunk's first frame, so the tablet gets a smooth timeline despite bursty reads.
                val mixNs = if (haveTs) rts.nanoTime + (readFrames - rts.framePosition) * 1_000_000_000L / Wire.RATE else System.nanoTime() - capMs * 1_000_000L
                n += r / 2; readFrames += r / 4
                val d = dest
                if (d != null && now - beaconAt < 5000) {
                    bb.putInt(0, seq).putLong(4, beaconT).putLong(12, beaconNs).putLong(20, System.nanoTime()).putLong(28, mixNs).putInt(36, if (shared) Wire.SHARED_MS else outMs)
                    try { dp.setData(pkt, 0, Wire.HEADER + r); dp.socketAddress = d; s.send(dp); bytes += Wire.HEADER + r } catch (_: Exception) {}
                } else tablet = ""
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
                    Log.e("LazyCar", "speaker send gap=$gapMax rms=$rms kbps=$kbps out=$outMs hal=$halMs cap=$capMs tablet=$tablet")
                    sq = 0.0; n = 0; bytes = 0; secAt = now; gapMax = 0
                }
            }
            rec.stop(); rec.release()
            if (alive) Handler(Looper.getMainLooper()).post { stopSelf() }   // read error: stop, so start() can run again
        }
        // Silent media track on the same route as the music: its timestamp is the real phone to JBL latency,
        // A2DP delay reporting included. ponytail: SBC-ish 150 ms added when the HAL hides the BT part.
        thread(name = "speaker-monitor") {
            val t = android.media.AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Wire.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).build()
            val zero = ByteArray(Wire.CHUNK * 4); var written = 0L; var at = 0L
            t.play()
            while (alive) {
                written += t.write(zero, 0, zero.size).coerceAtLeast(0) / 4
                val now = SystemClock.elapsedRealtime()
                if (now - at > 1000) { at = now
                    halMs = try { android.media.AudioManager::class.java.getMethod("getOutputLatency", Int::class.java)
                        .invoke(getSystemService(android.media.AudioManager::class.java), android.media.AudioManager.STREAM_MUSIC) as Int } catch (_: Exception) { -1 }
                    val bt = t.routedDevice?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    // Blocking writes keep the client buffer full; the music is captured after it, so drop it.
                    latencyMs(t, written)?.let { val mix = it - t.bufferSizeInFrames * 1000 / Wire.RATE
                        outMs = if (bt && mix < 100) mix + 150 else mix }
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
        // capture stays full scale while muted. Our level goes on the accessibility stream (own volume via TapService).
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
        socket?.close(); socket = null
        projection?.stop(); projection = null
        lock?.release(); lock = null
        wifiLow?.release(); wifiLow = null
        wifi?.release(); wifi = null
        wake?.release(); wake = null
        super.onDestroy()
    }
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
    const val HEADER = 40          // seq int, echoed tablet beacon ns, phone ns at beacon, phone ns at send, phone mixer ns of frame 0, phone to JBL ms int
}
