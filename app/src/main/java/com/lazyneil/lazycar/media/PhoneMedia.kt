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
import android.util.Log
import com.lazyneil.lazycar.R
import com.lazyneil.lazycar.Role
import com.lazyneil.lazycar.audio.TabletSpeaker
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

/**
 * Now Playing and library for CarApp's player UI. Tablet: from the phone's [MediaListener] over TCP, or in Tablet mode
 * from the tablet's own player ([local]). Phone in Tablet mode: from the tablet, relayed by [MediaListener].
 */
object PhoneMedia {
    @Volatile var app: Context? = null
    /** Tablet, Tablet mode: the music plays here, so Now Playing and the controls are this tablet's player. */
    val local get() = app?.let { Role.isTablet(it) && TabletSpeaker.fromTablet(it) } == true
    private val onPhone get() = app?.let { !Role.isTablet(it) } == true
    /** Whose music the UI shows. */
    val source get() = if (onPhone || local) "Tablet" else "Phone"
    @Volatile var connected = false
    @Volatile private var lostAt = SystemClock.elapsedRealtime()   // app start counts as a reconnect
    private val gpsAt get() = com.lazyneil.lazycar.gps.GpsService.let { if (it.mode == "receive") it.lastAt else 0L }
    /** Same rule as the phone's tile: any traffic from the phone (media link, audio or GPS) in the last 10 s. */
    val alive get() = local || connected || SystemClock.elapsedRealtime() - maxOf(StreamReceiver.lastAt, gpsAt) < 10_000
    /** First 10 s of a reconnect with no traffic at all: "Connecting...", never "not connected". */
    val reconnecting get() = !alive && SystemClock.elapsedRealtime() - lostAt < 10_000
    @Volatile var phone = ""
    @Volatile var np = JSONObject()
    @Volatile var art: Bitmap? = null
    @Volatile private var npAt = 0L
    @Volatile private var w: Writer? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    @Volatile private var gen = 0   // bumped by stop(): the old link thread and loops end
    @Volatile private var sock: Socket? = null
    /** Shut down: end the phone link and its loops; start() runs them again. */
    fun stop() {
        io.execute { synchronized(this) { started = false; gen++ }; try { sock?.close() } catch (_: Exception) {} }
        // Let go of CarApp's player too: a bound browser keeps its service alive after Shut down.
        main.post { sess?.let { it.ctl?.unregisterCallback(localCb); it.browser?.disconnect() }; sess = null; localKey = "" }
    }
    private var reqSeq = 0
    private val pending = mutableMapOf<Int, (JSONObject) -> Unit>()
    /** Now Playing listeners, main thread. A list, so the player and the library can both listen. */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private fun fire() = listeners.forEach { it() }

    val playing get() = np.optInt("state") == PlaybackState.STATE_PLAYING
    val hasTrack get() = (alive || reconnecting) && np.optString("title").isNotEmpty()   // keep the last track through a blip
    /** Position now, extrapolated from the last update. */
    fun position(): Long {
        val p = np.optLong("pos")
        return if (playing) minOf(p + ((SystemClock.elapsedRealtime() - npAt) * np.optDouble("speed", 1.0)).toLong(),
            np.optLong("dur").takeIf { it > 0 } ?: Long.MAX_VALUE) else p
    }

    fun send(j: JSONObject) = io.execute { try { w?.apply { write(j.toString()); write("\n"); flush() } } catch (_: Exception) {} }
    fun cmd(c: String, extra: JSONObject.() -> Unit = {}) {
        if (c in acked) { ackC = c; ackAt = SystemClock.elapsedRealtime(); ackOld = np }
        // Optimistic: the play/pause state flips at once, the phone's reply confirms or corrects it.
        if (c == "seek" && hasTrack) { val o = JSONObject(np.toString()); extra(o); np = o; npAt = SystemClock.elapsedRealtime() }   // optimistic position
        if (c == "toggle" && hasTrack) { val p = position(); np = JSONObject(np.toString()).put("pos", p)
            .put("state", if (playing) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING); npAt = SystemClock.elapsedRealtime(); main.post { fire() } }
        val j = JSONObject().put("c", c).apply(extra)
        when {
            local && c in media -> main.post { here(j) { r -> handle(app!!, r) } }
            onPhone -> MediaListener.toTablet(j)
            else -> send(j)
        }
    }
    private val media = setOf("toggle", "next", "prev", "seek", "browse", "playId", "files", "playFile", "volSet")
    private val acked = setOf("toggle", "next", "prev", "seek")
    @Volatile private var ackC = ""; @Volatile private var ackAt = 0L; private var ackOld = JSONObject()

    // Library cache: every good reply in memory and as a small JSON file, shown at once on the next open.
    private var dir: java.io.File? = null
    private val cache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private fun key(c: String, id: String, q: String) = "$c|$id|$q"
    fun cached(c: String, id: String, q: String = ""): JSONObject? = key(c, id, q).let { k ->
        cache[k] ?: try { dir?.let { java.io.File(it, "${k.hashCode()}.json").takeIf { f -> f.exists() }?.readText()?.let(::JSONObject) }?.also { cache[k] = it } } catch (_: Exception) { null }
    }
    private fun store(k: String, j: JSONObject) {
        cache[k] = j; io.execute { try { dir?.let { java.io.File(it, "${k.hashCode()}.json").writeText(j.toString()) } } catch (_: Exception) {} }
    }

    /** Library request; [done] gets the phone's reply on the main thread, or an error after 8 s. */
    fun request(c: String, id: String, q: String = "", done: (JSONObject) -> Unit) {
        val k = key(c, id, q)
        val r = synchronized(pending) { (++reqSeq).also { pending[it] = { j -> if (j.optString("err").isEmpty()) store(k, j); done(j) } } }
        if (!connected && !local) { fail(r, "$source not connected"); return }
        cmd(c) { put("id", id).put("q", q).put("req", r) }
        main.postDelayed({ fail(r, "The ${source.lowercase()} did not answer. Unlock it and try again") }, 8000)
    }
    private fun fail(r: Int, err: String) {
        synchronized(pending) { pending.remove(r) }?.let { main.post { it(JSONObject().put("err", err)) } }
    }

    /** First open is instant too: fetch the root and its first level as soon as the link is up. */
    private fun prefetch() = request("browse", "") { root ->
        val items = root.optJSONArray("items") ?: return@request
        for (i in 0 until minOf(items.length(), 8)) items.getJSONObject(i).takeIf { it.optBoolean("browse") }?.let { request("browse", it.optString("id")) {} }
    }

    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true; val g = gen
        val app = ctx.applicationContext; this.app = app
        main.post(object : Runnable { override fun run() { if (g != gen) return; follow(app); main.postDelayed(this, 1000) } })
        dir = java.io.File(app.cacheDir, "lib").apply { mkdirs() }
        com.lazyneil.lazycar.SettingsSync.init(app) { items -> if (connected) cmd("prefs") { put("items", items) } }
        // Keepalive on its own clock: the phone drops a tablet that stays silent for 15 s.
        main.post(object : Runnable { override fun run() { if (g != gen) return; if (connected) send(JSONObject().put("c", "hb")); main.postDelayed(this, 5000) } })
        thread(name = "phone-media", isDaemon = true) {
            while (g == gen) {
                try {
                    val ip = find(app) ?: throw Exception("no phone")
                    Socket().use { s -> sock = s
                        s.connect(InetSocketAddress(ip, Link.PORT), 3000); s.tcpNoDelay = true
                        s.soTimeout = 15_000   // the phone heartbeats every second; 15 s of silence = dead link, the timeout ends the read and we reconnect
                        w = s.getOutputStream().bufferedWriter(); phone = ip; connected = true; sentTvol = -1; localKey = ""   // the phone may have restarted: report our level (and art) again
                        upAt = SystemClock.elapsedRealtime()
                        Log.e("LazyCar", "media link up $ip after ${upAt - lostAt} ms down")
                        main.postDelayed({ prefetch() }, 1500)
                        cmd("prefs") { put("items", com.lazyneil.lazycar.SettingsSync.all()).put("full", true) }   // full exchange
                        s.getInputStream().bufferedReader().forEachLine { l -> handle(app, JSONObject(l)) }
                    }
                    why = "phone closed"
                } catch (e: Exception) { why = e.toString() }
                lastGo = null
                if (connected) {
                    connected = false; w = null; lostAt = SystemClock.elapsedRealtime()
                    Log.e("LazyCar", "media link down after ${lostAt - upAt} ms: $why")
                    main.post { fire(); notifyMedia(app) }
                    main.postDelayed({ fire() }, 10_500)   // grace over: let the UI say "not connected" if still down
                }
                synchronized(pending) { pending.keys.toList() }.forEach { fail(it, "Phone not connected") }
                Thread.sleep(500)   // a phone app restart should reconnect in about a second
            }
        }
    }

    private var sentTvol = -1
    private var upAt = 0L; private var why = ""
    private var artKey = ""
    private var lastGo: Boolean? = null   // null until the first message after a connect

    private fun handle(ctx: Context, j: JSONObject) {
        when (j.optString("t")) {
            // Only a slider moved by the user on the phone sets our volume; nothing on connect, never by itself.
            "tvol" -> if (j.optBoolean("user")) { Volumes.applyTablet(ctx, j.optInt("pct")); sentTvol = -1 }
            "np" -> {
                val old = np
                // Phone GO switched on while we are connected: open Maps + CarApp once. The first message after a
                // connect (an app restart, an install, a Wi-Fi drop) only records the state: a restart must never
                // re-split, because going HOME sends navigating Maps to PiP. Boot has its own path.
                val go = j.optBoolean("go")
                if (go && lastGo == false && com.lazyneil.lazycar.Ui.openOnGo(ctx)) main.post { com.lazyneil.lazycar.Drive.hint(ctx) }
                lastGo = go
                // Report our speaker level so the phone slider is live; hardware buttons included.
                Volumes.localPct(ctx).let { if (it != sentTvol) { sentTvol = it; Volumes.saveTablet(ctx, it); cmd("tvol") { put("pct", it) } } }
                if (!local) onNp(ctx, j)   // Tablet mode: the phone's own player is not ours to show
            }
            "prefs" -> com.lazyneil.lazycar.SettingsSync.apply(j.optJSONArray("items"))
            "shutdown" -> main.post { com.lazyneil.lazycar.GoAction.stop(ctx, fromPeer = true) }
            "go" -> { val on = j.optBoolean("on"); if (on) cmd("goAck"); main.post {
                if (on) { com.lazyneil.lazycar.Prefs(ctx).shutDown = false; com.lazyneil.lazycar.gps.GpsService.startIfIdle(ctx) }   // GO on the phone
                com.lazyneil.lazycar.audio.TabletSpeaker.playHere(ctx, on) } }
            "list" -> synchronized(pending) { pending.remove(j.optInt("req")) }?.let { main.post { it(j) } }
            "cmd" -> main.post { if (local) here(j) { r -> send(r.put("c", "list")) } }   // the phone UI in Tablet mode
        }
    }

    private fun onNp(ctx: Context, j: JSONObject) {
                val old = np
                // A reconnect resends the art: same track keeps the bitmap we have, so the UI does not flicker.
                val key = listOf("pkg", "title", "artist", "album").joinToString("|") { j.optString(it) }
                if (j.has("art") && (key != artKey || art == null)) { artKey = key; art = j.optString("art").takeIf { it.isNotEmpty() }?.let { decode(it) }; com.lazyneil.lazycar.Accent.art = art }
                val newArt = j.optString("art").isNotEmpty(); j.remove("art")   // np stays small: the bitmap lives in [art]
                com.lazyneil.lazycar.Accent.fromPhone = j.optInt("accent").takeIf { it != 0 }; com.lazyneil.lazycar.Accent.publish(ctx)
                if (ackAt > 0 && (ackC == "seek" || j.optString("title") != ackOld.optString("title") || j.optInt("state") != ackOld.optInt("state"))) {
                    Log.e("LazyCar", "media cmd $ackC acked in ${SystemClock.elapsedRealtime() - ackAt} ms"); ackAt = 0 }
                np = j; npAt = SystemClock.elapsedRealtime()
                val big = old.optString("title") != j.optString("title") || old.optInt("state") != j.optInt("state") || newArt
                main.post { fire(); if (big) notifyMedia(ctx) }
    }

    /** Phone, Tablet mode: the tablet's Now Playing ("np") and library answers ("list"), relayed by [MediaListener]. */
    fun fromTablet(ctx: Context, j: JSONObject) {
        app = ctx.applicationContext
        if (j.optString("t") == "np") { if (!connected) { connected = true; lostAt = SystemClock.elapsedRealtime() }; onNp(ctx, j) }
        else synchronized(pending) { pending.remove(j.optInt("req")) }?.let { main.post { it(j) } }
    }
    /** Phone: the tablet link dropped. */
    fun tabletGone() { if (connected) { connected = false; lostAt = SystemClock.elapsedRealtime(); main.post { fire() } } }

    // Tablet mode on the tablet: the tablet player's session, the same Session code the phone uses for its player.
    private var sess: Session? = null
    private var localKey = ""
    private val localCb = object : android.media.session.MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushLocal()
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = pushLocal()
        override fun onSessionDestroyed() { sess?.ctl?.unregisterCallback(this); sess?.ctl = null }
    }
    /** 1 Hz: follow the chosen tablet player while in Tablet mode, let go of it otherwise. */
    private fun follow(ctx: Context) {
        if (!local) { sess?.let { it.ctl?.unregisterCallback(localCb); it.browser?.disconnect(); sess = null; localKey = "" }; return }
        val s = sess ?: Session(ctx, true).also { sess = it }
        s.follow(TabletSpeaker.tabletPlayer(ctx), localCb)
        pushLocal()
    }
    /** Like the phone's push: art only when the track changed; the copy for the phone UI rides the existing link. */
    private fun pushLocal() {
        val s = sess ?: return; val ctx = app ?: return
        val m = s.ctl?.metadata; val j = s.np(); val key = s.key(j)
        val withArt = key != localKey; localKey = key
        s.io.execute {
            if (withArt) j.put("art", m?.let(s::art)?.let { s.jpeg(it, 1024, 92) } ?: "")
            if (connected) send(JSONObject(j.toString()).put("c", "np"))
            onNp(ctx, j)
        }
    }
    /** A media command for the tablet player; "volSet" is the JBL, which the tablet plays in Tablet mode. */
    private fun here(j: JSONObject, reply: (JSONObject) -> Unit) {
        val s = sess ?: return reply(JSONObject().put("t", "list").put("req", j.optInt("req")).put("err", "Starting the player, try again"))
        if (j.optString("c") == "volSet") Volumes.setJblLocal(s.ctx, j.optInt("pct")) else s.handle(j, reply)
        main.postDelayed({ pushLocal() }, 300)
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
        ctx.getSystemService(NotificationManager::class.java).notify(com.lazyneil.lazycar.GoService.NOTIF_ID, notification(ctx))
    }

    fun notification(ctx: Context): Notification {
        val b = Notification.Builder(ctx, com.lazyneil.lazycar.GoService.channel(ctx)).setSmallIcon(R.drawable.ic_car_glyph)
        if (!hasTrack) return b.setContentTitle("Tablet speaker").setContentText("Ready for phone audio").addAction(com.lazyneil.lazycar.GoService.shutAction(ctx)).build()
        fun act(icon: Int, label: String, c: String) = Notification.Action.Builder(Icon.createWithResource(ctx, icon), label,
            PendingIntent.getBroadcast(ctx, c.hashCode(), Intent(ctx, Button::class.java).setAction(c), PendingIntent.FLAG_IMMUTABLE)).build()
        return b.setContentTitle(np.optString("title")).setContentText(np.optString("artist"))
            .setLargeIcon(art).setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .addAction(act(R.drawable.ic_skip_prev, "Previous", "prev"))
            .addAction(if (playing) act(R.drawable.ic_pause, "Pause", "toggle") else act(R.drawable.ic_play, "Play", "toggle"))
            .addAction(act(R.drawable.ic_skip_next, "Next", "next")).addAction(com.lazyneil.lazycar.GoService.shutAction(ctx)).build()
    }

    class Button : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) { i.action?.let { cmd(it) } }
    }
}
