package com.lazyneil.lazycar.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.lazyneil.lazycar.R
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Tablet side: beacons for the phone, then plays its PCM on the built-in speaker (and optionally a BT speaker). */
class StreamReceiver : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var phone = ""
        @Volatile var lastAt = 0L
        @Volatile var kbps = 0
        @Volatile var netMs = 0        // one way, half the beacon round trip
        @Volatile var lost = 0L
        @Volatile var rms = 0          // level of the received PCM, proves audio arrives even at volume 0
        @Volatile var phoneOutMs = 0   // phone to JBL latency, sent in every packet
        val outputs = mutableListOf<Output>()

        fun sync(ctx: Context) {
            val i = Intent(ctx, StreamReceiver::class.java)
            try { if (TabletSpeaker.enabled(ctx)) ctx.startForegroundService(i) else ctx.stopService(i) }
            catch (e: Exception) { Log.e("LazyCar", "speaker receive start", e) }
        }
    }

    /** One AudioTrack pinned to one device. Holds queue + track latency at [extra] (the auto target plus trim). */
    class Output(val name: String, type: Int, ctx: Context, val extra: () -> Int) {
        val q = LinkedBlockingQueue<ByteArray>()
        @Volatile var queueMs = 0
        @Volatile var trackMs = 0
        @Volatile var underruns = 0
        val head get() = track.playbackHeadPosition
        val device: AudioDeviceInfo? = ctx.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == type }
        private val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Wire.RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(Wire.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2)
            .build().apply { setPreferredDevice(device); setVolume(1f) }
        // ponytail: fixed +6 dB boost, no setting; add a slider if it needs tuning
        private val boost = try { android.media.audiofx.LoudnessEnhancer(track.audioSessionId).apply { setTargetGain(600); enabled = true } } catch (e: Exception) { null }
        val routed get() = track.routedDevice?.productName?.toString() + " (" + (track.routedDevice?.type ?: -1) + ")"

        fun run(alive: () -> Boolean) = thread(name = "speaker-$name") {
            track.play()
            var written = 0L
            var buffering = true
            while (alive()) {
                val target = maxOf(BASE_MS, extra())
                trackMs = StreamSender.latencyMs(track, written / 4) ?: ((written / 4 - track.playbackHeadPosition) / (Wire.RATE / 1000)).toInt()
                queueMs = q.size * Wire.CHUNK_MS
                if (buffering) { if (queueMs + trackMs >= target) buffering = false else { Thread.sleep(2); continue } }
                val c: ByteArray? = q.poll(100, TimeUnit.MILLISECONDS)
                if (c == null) { buffering = true; underruns++; continue }
                val total = queueMs + trackMs
                if (total > target + 30) continue                       // too late: drop 5 ms
                val times = if (total < target - 30) 2 else 1           // too early: repeat 5 ms
                repeat(times) { written += track.write(c, 0, c.size).coerceAtLeast(0) }
            }
            track.stop(); boost?.release(); track.release()
        }
    }

    @Volatile private var alive = false
    private var socket: DatagramSocket? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("speaker", "Tablet speaker", NotificationManager.IMPORTANCE_LOW))
        startForeground(47475, Notification.Builder(this, "speaker").setContentTitle("Tablet speaker")
            .setContentText("Ready for phone audio").setSmallIcon(R.drawable.ic_car_glyph).setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (alive) return START_STICKY
        alive = true; running = true
        try { start() } catch (e: Exception) { Log.e("LazyCar", "speaker receive", e); stopSelf() }
        return START_STICKY
    }

    private fun start() {
        @Suppress("DEPRECATION")
        wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "speaker").apply { acquire() }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carapp:speaker").apply { acquire() }
        val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(Wire.TABLET_PORT)) }
        socket = s
        val ctx = this
        synchronized(outputs) {
            outputs.clear()
            // Auto sync: sound leaves the JBL phoneOut after capture and reaches us net after capture; play the rest later.
            outputs += Output("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ctx) { phoneOutMs - netMs + TabletSpeaker.delayMs(ctx) }
            if (TabletSpeaker.btToo(ctx)) outputs += Output("bluetooth", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, ctx) { 0 }
            outputs.forEach { it.run { alive } }
        }
        thread(name = "speaker-beacon") {
            val hello = ByteArray(14).also { Wire.MAGIC.toByteArray().copyInto(it) }
            while (alive) {
                ByteBuffer.wrap(hello).putLong(6, SystemClock.elapsedRealtime())
                for (a in broadcastTargets()) try { s.send(DatagramPacket(hello, hello.size, a, Wire.PHONE_PORT)) } catch (_: Exception) {}
                Thread.sleep(1000)
            }
        }
        thread(name = "speaker-net") {
            val buf = ByteArray(Wire.HEADER + Wire.CHUNK)
            val bb = ByteBuffer.wrap(buf)
            var last = -1; var bytes = 0L; var secAt = SystemClock.elapsedRealtime(); var sq = 0.0; var n = 0
            while (alive) try {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                if (p.length <= Wire.HEADER) continue
                val now = SystemClock.elapsedRealtime()
                val seq = bb.getInt(0)
                netMs = ((now - bb.getLong(4) - bb.getInt(12)) / 2).toInt()
                phoneOutMs = bb.getInt(16)
                if (now - lastAt > 2000) last = seq - 1                    // fresh stream (phone restarted)
                if (seq <= last) continue                                   // late duplicate or reordered
                val chunk = buf.copyOfRange(Wire.HEADER, p.length)
                for (i in Wire.HEADER until p.length - 1 step 8) { val v = bb.getShort(i).toDouble(); sq += v * v; n++ }
                val gap = (seq - last - 1).coerceAtMost(20)
                lost += gap
                for (o in outputs) { repeat(gap) { o.q.offer(ByteArray(chunk.size)) }; o.q.offer(chunk) }
                last = seq; lastAt = now; phone = p.address.hostAddress ?: ""; bytes += p.length
                if (now - secAt >= 1000) {
                    kbps = (bytes * 8 / (now - secAt)).toInt(); bytes = 0; secAt = now
                    rms = kotlin.math.sqrt(sq / maxOf(n, 1)).toInt(); sq = 0.0; n = 0
                    Log.e("LazyCar", "speaker recv kbps=$kbps rms=$rms net=$netMs phoneOut=$phoneOutMs " + outputs.joinToString { "${it.name} q=${it.queueMs} track=${it.trackMs} under=${it.underruns} head=${it.head} -> ${it.routed}" })
                }
            } catch (_: Exception) {}
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
        wake?.release(); wake = null
        super.onDestroy()
    }
}

/** Jitter floor: the tablet never buffers less than this, even if that leaves it behind the JBL. */
const val BASE_MS = 40
