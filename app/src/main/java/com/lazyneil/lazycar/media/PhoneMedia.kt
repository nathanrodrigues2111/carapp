package com.lazyneil.lazycar.media

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.lazyneil.lazycar.R
import com.lazyneil.lazycar.audio.StreamReceiver
import org.json.JSONObject
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Tablet side: keeps a TCP session to the phone's [MediaListener], holds Now Playing, sends button taps. */
object PhoneMedia {
    @Volatile var connected = false
    @Volatile var phone = ""
    @Volatile var np = JSONObject()
    @Volatile var art: Bitmap? = null
    @Volatile private var npAt = 0L
    @Volatile private var w: Writer? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var reqSeq = 0
    private val pending = mutableMapOf<Int, (JSONObject) -> Unit>()
    var onChange: (() -> Unit)? = null   // dashboard card, main thread

    val playing get() = np.optInt("state") == PlaybackState.STATE_PLAYING
    val hasTrack get() = connected && np.optString("title").isNotEmpty()
    /** Position now, extrapolated from the last update. */
    fun position(): Long {
        val p = np.optLong("pos")
        return if (playing) minOf(p + ((SystemClock.elapsedRealtime() - npAt) * np.optDouble("speed", 1.0)).toLong(),
            np.optLong("dur").takeIf { it > 0 } ?: Long.MAX_VALUE) else p
    }

    fun send(j: JSONObject) = io.execute { try { w?.apply { write(j.toString()); write("\n"); flush() } } catch (_: Exception) {} }
    fun cmd(c: String, extra: JSONObject.() -> Unit = {}) = send(JSONObject().put("c", c).apply(extra))

    /** Library request; [done] gets the reply on the main thread. */
    fun request(c: String, id: String, q: String = "", done: (JSONObject) -> Unit) {
        val r = synchronized(pending) { (++reqSeq).also { pending[it] = done } }
        if (!connected) { main.post { done(JSONObject().put("err", "Phone not connected")) }; return }
        cmd(c) { put("id", id).put("q", q).put("req", r) }
    }

    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(name = "phone-media", isDaemon = true) {
            while (true) {
                try {
                    val ip = find(app) ?: throw Exception("no phone")
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, Link.PORT), 3000); s.tcpNoDelay = true
                        w = s.getOutputStream().bufferedWriter(); phone = ip; connected = true
                        s.getInputStream().bufferedReader().forEachLine { l -> handle(app, JSONObject(l)) }
                    }
                } catch (_: Exception) {}
                if (connected) { connected = false; w = null; main.post { onChange?.invoke(); notifyMedia(app) } }
                Thread.sleep(2000)
            }
        }
    }

    private var sentTvol = -1

    private fun handle(ctx: Context, j: JSONObject) {
        when (j.optString("t")) {
            "tvol" -> { Volumes.applyTablet(ctx, j.optInt("pct")); sentTvol = -1 }
            "np" -> {
                val old = np
                if (j.has("art")) art = j.optString("art").takeIf { it.isNotEmpty() }?.let { decode(it) }
                else j.put("art", "")
                np = j; npAt = SystemClock.elapsedRealtime()
                // Report our speaker level so the phone slider is live; hardware buttons included.
                Volumes.localPct(ctx).let { if (it != sentTvol) { sentTvol = it; Volumes.saveTablet(ctx, it); cmd("tvol") { put("pct", it) } } }
                val big = old.optString("title") != j.optString("title") || old.optInt("state") != j.optInt("state") || j.has("art") && j.optString("art").isNotEmpty()
                main.post { onChange?.invoke(); if (big) notifyMedia(ctx) }
            }
            "list" -> synchronized(pending) { pending.remove(j.optInt("req")) }?.let { main.post { it(j) } }
        }
    }

    fun decode(b64: String): Bitmap? = try { Base64.decode(b64, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (_: Exception) { null }

    /** Phone candidates: the stream and GPS peers, the Wi-Fi gateway (the phone hotspot in the car), then a broadcast probe. */
    private fun find(ctx: Context): String? {
        val known = listOf(phone, StreamReceiver.phone.substringBefore(':')).filter { it.isNotEmpty() }
        @Suppress("DEPRECATION")
        val gw = ctx.getSystemService(WifiManager::class.java).dhcpInfo?.gateway ?: 0
        val gateway = if (gw != 0) "${gw and 255}.${gw shr 8 and 255}.${gw shr 16 and 255}.${gw shr 24 and 255}" else null
        return probe() ?: known.firstOrNull() ?: gateway
    }

    private fun probe(): String? = try {
        DatagramSocket().use { s ->
            s.broadcast = true; s.soTimeout = 1500
            val msg = Link.PROBE.toByteArray()
            val targets = mutableSetOf(InetAddress.getByName("255.255.255.255"))
            NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }
                ?.forEach { ni -> ni.interfaceAddresses.mapNotNullTo(targets) { it.broadcast } }
            targets.forEach { try { s.send(DatagramPacket(msg, msg.size, it, Link.PORT)) } catch (_: Exception) {} }
            val buf = ByteArray(32); val p = DatagramPacket(buf, buf.size); s.receive(p)
            p.address.hostAddress
        }
    } catch (_: Exception) { null }

    /** Compact controls on CarApp's tablet speaker notification while it runs. */
    fun notifyMedia(ctx: Context) {
        if (!StreamReceiver.running) return
        ctx.getSystemService(NotificationManager::class.java).notify(47475, notification(ctx))
    }

    fun notification(ctx: Context): Notification {
        val b = Notification.Builder(ctx, "speaker").setSmallIcon(R.drawable.ic_car_glyph).setOngoing(true)
        if (!hasTrack) return b.setContentTitle("Tablet speaker").setContentText("Ready for phone audio").build()
        fun act(icon: Int, label: String, c: String) = Notification.Action.Builder(Icon.createWithResource(ctx, icon), label,
            PendingIntent.getBroadcast(ctx, c.hashCode(), Intent(ctx, Button::class.java).setAction(c), PendingIntent.FLAG_IMMUTABLE)).build()
        return b.setContentTitle(np.optString("title")).setContentText(np.optString("artist"))
            .setLargeIcon(art).setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .addAction(act(R.drawable.ic_skip_prev, "Previous", "prev"))
            .addAction(if (playing) act(R.drawable.ic_pause, "Pause", "toggle") else act(R.drawable.ic_play, "Play", "toggle"))
            .addAction(act(R.drawable.ic_skip_next, "Next", "next")).build()
    }

    class Button : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) { i.action?.let { cmd(it) } }
    }
}
