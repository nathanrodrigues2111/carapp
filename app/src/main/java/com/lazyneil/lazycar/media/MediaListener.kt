package com.lazyneil.lazycar.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import com.lazyneil.lazycar.media.MediaListener.Companion.clients
import com.lazyneil.lazycar.media.MediaListener.Companion.player
import com.lazyneil.lazycar.media.MediaListener.Companion.tabletPct

/**
 * Phone side of "control like Spotify Connect": the notification listener grant is what lets us read
 * every app's media session. Serves Now Playing + library browsing to the tablet over TCP [Link.PORT].
 */
/** Notification access, optional: only the grant matters (it lets [MediaLink] read and control other players'
 *  sessions). CarApp's own player and the phone-tablet link work without it. */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() { MediaLink.self?.let { it.main.post { it.watchSessions() } } }

    companion object {
        /** The phone's link server runs (between app open or GO and Shut down). */
        val bound get() = MediaLink.self != null
        @Volatile var clients = 0
        @Volatile var player = ""
        @Volatile var tabletPct = -1     // tablet speaker level, reported by the tablet
        private val self get() = MediaLink.self
        /** Phone slider: tell every tablet to set its speaker level. */
        fun setTablet(pct: Int) { tabletPct = pct; self?.let { m -> m.out.forEach { m.send(it, JSONObject().put("t", "tvol").put("pct", pct).put("user", true).toString()) } } }
        /** Tablet mode GO / STOP; the tablet answers "goAck". */
        fun goTablet(on: Boolean) { self?.let { m -> m.out.forEach { m.send(it, JSONObject().put("t", "go").put("on", on).toString()) } } }
        /** Tablet mode: the phone UI's media commands to every tablet, as "cmd". */
        fun toTablet(j: JSONObject) { self?.let { m -> val l = j.put("t", "cmd").toString(); m.out.forEach { m.send(it, l) } } }
        /** Shut down: tell every tablet first, then unbind (no server, no sessions) until [wake]. */
        fun speakerTo(mac: String, name: String, tablet: Boolean) { self?.let { m -> m.out.forEach { m.send(it, JSONObject().put("t", "spk").put("mac", mac).put("name", name).put("on", tablet).toString()) } } }
        fun shutDownTablets() { self?.let { m -> m.out.forEach { m.send(it, JSONObject().put("t", "shutdown").toString()) } } }
        fun sleep() { self?.let { it.stopSelf() } }
        /** App open or GO on the phone: start the link server (a plain in-process service, no grant needed). */
        fun wake(ctx: Context) { if (!bound && !com.lazyneil.lazycar.Role.isTablet(ctx)) try { ctx.startService(Intent(ctx, MediaLink::class.java)) } catch (e: Exception) { Log.e("LazyCar", "link start: $e") } }
        fun granted(ctx: Context) = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        fun settingsIntent() = Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        /** OxygenOS lacks the plain listener screen: try detail page, list page, then app info. First that resolves wins, never throws. */
        fun openSettings(ctx: Context) {
            val cn = ComponentName(ctx.packageName, MediaListener::class.java.name).flattenToString()
            val tries = listOf(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn),
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            com.lazyneil.lazycar.Ui.openSettings(ctx, *tries.toTypedArray())
        }
    }

}

/**
 * Phone side of "control like Spotify Connect": serves Now Playing, library browsing, GO, Shut down and settings to the
 * tablet over TCP [Link.PORT]. Other players' sessions need the [MediaListener] grant; without it only CarApp's own.
 */
class MediaLink : android.app.Service() {
    companion object { @Volatile var self: MediaLink? = null }
    override fun onBind(i: Intent?) = null

    /** One writer thread per tablet: a dead or slow socket blocks only its own queue, never the heartbeat or other work. */
    class Client(val s: Socket) {
        val w: Writer = s.getOutputStream().bufferedWriter(); var artKey: String? = null
        val q = Executors.newSingleThreadExecutor()
        @Volatile var heardAt = SystemClock.elapsedRealtime()
    }

    val main = Handler(Looper.getMainLooper())
    val out = CopyOnWriteArrayList<Client>()
    @Volatile private var alive = false
    private var server: ServerSocket? = null
    private var probe: DatagramSocket? = null
    private val sess = Session(this, false)
    private val io get() = sess.io

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onSessionDestroyed() = pick()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick() }
    private val tick = object : Runnable {
        override fun run() { push(); main.postDelayed(this, 1000) }
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int) = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        // Shut down: nothing runs until the app is opened or GO. The tablet never serves (it would connect to itself).
        if (com.lazyneil.lazycar.Prefs(this).shutDown || com.lazyneil.lazycar.Role.isTablet(this)) { stopSelf(); return }
        alive = true; self = this; PhoneMedia.app = applicationContext
        com.lazyneil.lazycar.SettingsSync.init(this) { items ->
            val line = JSONObject().put("t", "prefs").put("items", items).toString(); out.forEach { send(it, line) } }
        watchSessions(); main.post(tick)
        thread(name = "media-server") { serve() }
        thread(name = "media-probe") { answerProbes() }
        thread(name = "media-hb", isDaemon = true) { heartbeat() }
    }

    /** 1 Hz whatever the player, main thread or io do. The tablet answers each one, so silence for 15 s = dead socket. */
    private fun heartbeat() {
        val line = JSONObject().put("t", "hb").toString()
        while (alive) {
            val now = SystemClock.elapsedRealtime()
            for (cl in out) if (now - cl.heardAt > 15_000) { Log.e("LazyCar", "media drop ${cl.s.inetAddress?.hostAddress} silent ${now - cl.heardAt} ms"); drop(cl) } else write(cl, line)
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
    }

    private var watching = false
    /** Other players' sessions, once notification access is granted (called again when it is). */
    fun watchSessions() {
        if (!watching) try {
            getSystemService(MediaSessionManager::class.java).addOnActiveSessionsChangedListener(sessions, ComponentName(this, MediaListener::class.java), main)
            watching = true
        } catch (_: SecurityException) {}
        pick()
    }

    override fun onDestroy() {
        if (!alive) { super.onDestroy(); return }
        alive = false; self = null
        if (watching) getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessions)
        sess.ctl?.unregisterCallback(cb); sess.ctl = null
        main.removeCallbacks(tick)
        sess.browser?.disconnect(); sess.browser = null
        server?.close(); probe?.close()
        out.forEach(::drop); clients = 0
        super.onDestroy()
    }

    /** Playing session wins, else the current one, else the most recent. */
    private fun pick() {
        val list = try {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this, MediaListener::class.java))
        } catch (e: SecurityException) { emptyList() }.filter { it.packageName != "com.android.server.telecom" }
        // Nothing playing (a track change, a pause): stay on the current player, a flip to another session flickers the title.
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.sessionToken == sess.ctl?.sessionToken } ?: list.firstOrNull()
        if (next?.sessionToken != sess.ctl?.sessionToken) {
            sess.ctl?.unregisterCallback(cb); sess.ctl = next; next?.registerCallback(cb, main)
            if (next?.packageName != sess.browser?.serviceComponent?.packageName) { sess.browser?.disconnect(); sess.browser = null }
        }
        player = next?.packageName ?: ""; sess.player = player
        push()
    }

    private var accentKey = ""; private var trackAccent = 0

    /** Now Playing to every tablet; art only when the track changed for that client. */
    private fun push() {
        if (out.isEmpty()) return
        val m = sess.ctl?.metadata
        val j = sess.np()
        j.put("go", com.lazyneil.lazycar.GoAction.effectiveState(com.lazyneil.lazycar.Prefs(this)) == com.lazyneil.lazycar.GoAction.ON)
            .put("spkOff", com.lazyneil.lazycar.audio.TabletSpeaker.speakerOff(this))
        val key = sess.key(j)
        val artFor = out.filter { it.artKey != key }.onEach { it.artKey = key }
        // Decoded once per track; the JPEG and Base64 encode run on io, never the main thread.
        val bmp = if (m != null && (artFor.isNotEmpty() || key != accentKey)) sess.art(m) else null
        if (key != accentKey) { accentKey = key; com.lazyneil.lazycar.Accent.art = bmp; trackAccent = com.lazyneil.lazycar.Accent.now(this); com.lazyneil.lazycar.Accent.publish(this) }
        j.put("accent", trackAccent)
        val line = j.toString()
        for (cl in out) if (cl !in artFor) write(cl, line)
        if (artFor.isNotEmpty()) io.execute {
            val a = bmp?.let { sess.jpeg(it, 1024, 92) } ?: ""
            for (cl in artFor) write(cl, JSONObject(line).put("art", a).toString())
        }
    }

    fun send(cl: Client, line: String) = write(cl, line)
    private fun write(cl: Client, line: String) = try {
        cl.q.execute { try { cl.w.write(line); cl.w.write("\n"); cl.w.flush() } catch (_: Exception) { drop(cl) } }
    } catch (_: java.util.concurrent.RejectedExecutionException) {}

    private fun drop(cl: Client) { try { cl.s.close() } catch (_: Exception) {}; cl.q.shutdownNow(); out.remove(cl); clients = out.size; if (out.isEmpty()) PhoneMedia.tabletGone() }

    private fun serve() { try {
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Link.PORT)) }.use { ss ->
            server = ss
            while (alive) {
                val s = ss.accept().apply { tcpNoDelay = true }
                val cl = Client(s)
                // Dead sockets go via heartbeat silence (15 s), never by IP: a live tablet link must not be kicked.
                Log.e("LazyCar", "media accept ${s.inetAddress?.hostAddress} clients=${out.size + 1}")
                out += cl; clients = out.size
                main.post { push() }
                thread(name = "media-client") {
                    try { s.getInputStream().bufferedReader().forEachLine { l -> cl.heardAt = SystemClock.elapsedRealtime(); main.post { handle(cl, JSONObject(l)) } } }
                    catch (_: Exception) {}
                    drop(cl)
                }
            }
        }
    } catch (e: Exception) { if (alive) Log.e("LazyCar", "media server", e) } }

    /** Tablets on the same Wi-Fi find us by broadcasting [Link.PROBE]. */
    private fun answerProbes() { try {
        DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(Link.PORT)) }.use { s ->
            probe = s
            val buf = ByteArray(32)
            while (alive) {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                if (String(buf, 0, p.length) == Link.PROBE) s.send(DatagramPacket(Link.PROBE.toByteArray(), Link.PROBE.length, p.socketAddress))
            }
        }
    } catch (e: Exception) { if (alive) Log.e("LazyCar", "media probe", e) } }

    private fun handle(cl: Client, j: JSONObject) {
        when (j.optString("c")) {
            "vol" -> getSystemService(AudioManager::class.java).adjustStreamVolume(Volumes.jblStream(),
                if (j.optInt("d") > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0).also { main.postDelayed({ push() }, 150) }
            "volSet" -> com.lazyneil.lazycar.media.Volumes.setJblLocal(this, j.optInt("pct")).also { main.postDelayed({ push() }, 150) }
            "tvol" -> tabletPct = j.optInt("pct")
            "goAck" -> com.lazyneil.lazycar.GoAction.tabletAcked()
            "shutdown" -> main.post {   // Shut down on the tablet: the phone side too
                try { startForegroundService(Intent(this, com.lazyneil.lazycar.GoService::class.java).putExtra("action", "stopPeer")) }
                catch (e: Exception) { com.lazyneil.lazycar.GoAction.stop(applicationContext, fromPeer = true) } }
            "prefs" -> { com.lazyneil.lazycar.SettingsSync.apply(j.optJSONArray("items"))
                if (j.optBoolean("full")) send(cl, JSONObject().put("t", "prefs").put("items", com.lazyneil.lazycar.SettingsSync.all()).toString()) }
            "np", "list" -> PhoneMedia.fromTablet(this, j)   // Tablet mode: the tablet player's Now Playing and library answers
            else -> sess.handle(j) { r -> send(cl, r.toString()) }
        }
        if (j.optString("c") in setOf("toggle", "next", "prev", "seek")) main.postDelayed({ push() }, 300)
    }
}

/**
 * One player's media session: Now Playing as JSON, library browsing and the media commands. The phone listener
 * serves the phone's player with it; in Tablet mode the tablet serves its own player ([local]) the same way.
 */
class Session(val ctx: Context, private val local: Boolean) {
    private val main = Handler(Looper.getMainLooper())
    val io = Executors.newSingleThreadExecutor()   // heavy work only (art, library icons, files); never writes
    var ctl: MediaController? = null
    var browser: MediaBrowser? = null
    var player = ""

    fun label(pkg: String) = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    fun jpeg(b: Bitmap?, px: Int, q: Int = 80): String? {
        b ?: return null
        val s = px.toFloat() / maxOf(b.width, b.height)
        val small = if (s < 1) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
        val o = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, q, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    fun bitmapFrom(uri: String?): Bitmap? = try {
        uri?.let { ctx.contentResolver.openInputStream(Uri.parse(it))?.use(BitmapFactory::decodeStream) }
    } catch (_: Exception) { null }

    // The session's bitmaps are downscaled by the framework, so the art URI (full resolution) goes first.
    fun art(m: MediaMetadata): Bitmap? =
        bitmapFrom(m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI))
        ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    /** Now Playing without art. */
    fun np(): JSONObject {
        val c = ctl
        val m = c?.metadata; val st = c?.playbackState
        val j = JSONObject().put("t", "np").put("pkg", c?.packageName ?: player).put("app", (c?.packageName ?: player).let(::label))
        if (m != null) j.put("title", m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.description?.title?.toString() ?: "")
        if (m != null) j.put("mediaId", m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "")
            .put("artist", m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.description?.subtitle?.toString() ?: "")
            .put("album", m.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            .put("dur", m.getLong(MediaMetadata.METADATA_KEY_DURATION))
        if (st != null) {
            val pos = if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0)
                st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else st.position
            j.put("state", st.state).put("pos", pos).put("speed", st.playbackSpeed.toDouble())
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        Volumes.jblStream().let { st -> j.put("vol", am.getStreamVolume(st)).put("volMax", am.getStreamMaxVolume(st)) }
        return j
    }
    fun key(j: JSONObject) = listOf(j.optString("pkg"), j.optString("title"), j.optString("artist"), j.optString("album")).joinToString("|")

    /** Tablet: [pkg]'s session from its browser service (no notification access needed), else its active session. */
    fun follow(pkg: String, cb: MediaController.Callback) {
        if (pkg != player) { ctl?.unregisterCallback(cb); ctl = null; browser?.disconnect(); browser = null; player = pkg }
        if (ctl != null) return
        val active = try { ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(ctx, MediaListener::class.java))
            .firstOrNull { it.packageName == pkg } } catch (_: SecurityException) { null }
        if (active != null) { ctl = active; active.registerCallback(cb, main) }
        else if (browser?.isConnected != false) withBrowser({}) { b -> if (ctl == null) ctl = MediaController(ctx, b.sessionToken).also { it.registerCallback(cb, main) } }
    }

    /** Media commands and library requests; [reply] gets the "list" answers. */
    fun handle(j: JSONObject, reply: (JSONObject) -> Unit) {
        val t = ctl?.transportControls
        // Tablet without a session yet: media keys straight to the player, headless.
        fun key(code: Int) = com.lazyneil.lazycar.GoAction.mediaTo(ctx, player, code)
        when (j.optString("c")) {
            "toggle" -> if (t == null) { if (local) com.lazyneil.lazycar.audio.CarPlayer.send(ctx, "play") else com.lazyneil.lazycar.GoAction.playMedia(ctx, com.lazyneil.lazycar.Prefs(ctx).playerPkg) }   // never the player's screen
                else if (ctl?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t?.skipToNext() ?: if (local) key(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) else Unit
            "prev" -> t?.skipToPrevious() ?: if (local) key(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) else Unit
            "seek" -> t?.seekTo(j.optLong("pos"))
            "browse" -> browse(j, reply)
            "playId" -> j.optString("id").let { id -> if (local && id.startsWith("song:")) playFile(id.substringAfter(':'))
                else withBrowser({ reply(list(j, JSONArray(), it)) }) { b -> MediaController(ctx, b.sessionToken).transportControls.playFromMediaId(id, null) } }
            "files" -> io.execute { reply(list(j, Files.list(ctx, j.optString("id"), j.optString("q")), noFolder())) }
            "playFile" -> main.post { playFile(j.optString("uri")) }
        }
    }

    /** Tablet: straight into the session when the player takes URIs (headless), else the player's open-file activity. */
    private fun playFile(song: String) {
        if (song.isEmpty()) return
        val t = ctl?.transportControls
        if (local && t != null && (ctl?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_URI != 0L)
            t.playFromUri(Uri.parse(song), null)
        else if (local) { com.lazyneil.lazycar.audio.CarPlayer.send(ctx, "pause"); main.postDelayed({ if (ctl != null) playFile(song) }, 800) }   // starts the player, then into its session
        else Files.play(ctx, Uri.parse(song), player)
    }

    private fun noFolder() = if (Files.has(ctx)) "" else if (local) "No music folder yet. Tap here to pick one" else "No music folder yet. Pick one on the phone's CarApp home screen"

    private fun list(req: JSONObject, items: JSONArray, err: String = "") =
        JSONObject().put("t", "list").put("req", req.optInt("req")).put("items", items).put("err", err)

    /** Connects to the current player's MediaBrowserService, the same one Android Auto uses. */
    private fun withBrowser(fail: (String) -> Unit, then: (MediaBrowser) -> Unit) {
        val pkg = player
        browser?.takeIf { it.isConnected && it.serviceComponent.packageName == pkg }?.let { then(it); return }
        val svc = ctx.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo
        if (svc == null) { fail("${label(pkg).ifEmpty { "No player" }} has no library to browse; controls still work"); return }
        browser?.disconnect()
        var b: MediaBrowser? = null
        b = MediaBrowser(ctx, ComponentName(svc.packageName, svc.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() { then(b!!) }
            override fun onConnectionFailed() { browser = null; fail("Browsing not allowed by ${label(pkg)}; controls still work") }
            override fun onConnectionSuspended() { browser = null }
        }, Bundle().apply { putBoolean("android.service.media.extra.RECENT", false) })
        browser = b
        b.connect()
    }

    // A frozen or busy player can leave connect/subscribe hanging: answer within 6 s and reconnect next time.
    private fun browse(req: JSONObject, reply: (JSONObject) -> Unit) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        main.postDelayed({ if (!done.get()) { browser?.disconnect(); browser = null; reply(list(req, JSONArray(), "${label(player)} did not answer")) } }, 6000)
        browseNow(req, reply, done)
    }

    // Tablet: a player that cannot be browsed lists the tablet's audio files instead (ids "song:<n>" play them).
    private fun browseNow(req: JSONObject, reply: (JSONObject) -> Unit, done: java.util.concurrent.atomic.AtomicBoolean) = withBrowser({ err ->
        done.set(true); if (local) io.execute { reply(list(req, Files.list(ctx, req.optString("id"), req.optString("q")), noFolder())) } else reply(list(req, JSONArray(), err))
    }) { b ->
        val q = req.optString("q")
        val sink = { items: List<MediaBrowser.MediaItem> -> done.set(true); io.execute { reply(list(req, JSONArray().apply {
            items.take(300).forEachIndexed { i, it -> val d = it.description
                put(JSONObject().put("id", it.mediaId).put("title", d.title ?: "").put("sub", d.subtitle ?: "")
                    .put("play", it.isPlayable).put("browse", it.isBrowsable)
                    .apply { if (i < 80) jpeg(d.iconBitmap ?: bitmapFrom(d.iconUri?.toString()), 96)?.let { a -> put("icon", a) } })
            } })) } }
        // ponytail: framework MediaBrowser has no search, so search filters the current level only.
        run {
            val id = req.optString("id").ifEmpty { b.root }
            b.subscribe(id, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                    b.unsubscribe(id)
                    sink(if (q.isEmpty()) children else children.filter { "${it.description.title} ${it.description.subtitle}".contains(q, true) })
                }
                override fun onError(parentId: String) { done.set(true); reply(list(req, JSONArray(), "${label(player)} would not list this")) }
            })
        }
    }
}

/** Shared by both sides. TCP for the session, UDP probe on the same number to find the phone. */
object Link {
    const val PORT = 47477
    const val PROBE = "CARMEDIA"
}

/** Audio files in the music folder the user picked (persisted tree grant, no storage permission), the fallback library for any player. */
object Files {
    private const val PICK = 4711
    class Song(val uri: Uri, val title: String, val artist: String, val album: String, val folder: String, val modified: Long)
    @Volatile private var cache: Pair<String, List<Song>>? = null
    /** Every scan runs here, one at a time, never on the main thread. */
    private val scanner = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun tree(ctx: Context) = com.lazyneil.lazycar.Prefs(ctx).musicTree
    fun has(ctx: Context) = tree(ctx).let { t -> t.isNotEmpty() && ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == t && it.isReadPermission } }
    fun pick(a: android.app.Activity) = try { a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK) } catch (e: Exception) { Log.e("LazyCar", "pick folder", e) }
    /** From onActivityResult: keeps the picked folder across reboots and scans it. True when it was the folder pick. */
    fun picked(ctx: Context, rc: Int, data: Intent?): Boolean {
        if (rc != PICK) return false
        val u = data?.data ?: return true
        try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { Log.e("LazyCar", "keep folder", e); return true }
        com.lazyneil.lazycar.Prefs(ctx).musicTree = u.toString(); refresh(ctx)
        return true
    }

    /** Background rescan (app open, folder pick, GO): only new or changed files have their tags read. */
    fun refresh(ctx: Context) { val app = ctx.applicationContext; scanner.execute { try { scan(app) } catch (e: Exception) { Log.e("LazyCar", "music scan", e) } } }
    /** The list on the scanner thread (scanning first if never scanned), so callers never block. */
    fun later(ctx: Context, then: (List<Song>) -> Unit) { val app = ctx.applicationContext; scanner.execute { then(try { cached(app) ?: scan(app) } catch (e: Exception) { emptyList() }) } }
    private fun cached(ctx: Context) = tree(ctx).let { t -> cache?.takeIf { it.first == t }?.second }

    /** Every audio file under the folder, sorted by title. Blocks on the first scan: io or scanner threads only. */
    fun songs(ctx: Context): List<Song> {
        val t = tree(ctx); if (!has(ctx)) return emptyList()
        cached(ctx)?.let { return it }
        return scanner.submit<List<Song>> { cached(ctx) ?: scan(ctx) }.get()   // never from the scanner thread itself
    }

    private fun scan(ctx: Context): List<Song> {
        val t = tree(ctx); if (!has(ctx)) return emptyList<Song>().also { cache = null }
        val old = cache?.takeIf { it.first == t }?.second?.associateBy { it.uri } ?: emptyMap()
        val root = Uri.parse(t); val out = mutableListOf<Song>()
        fun walk(doc: String, folder: String) {
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            try { ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(root, doc), cols, null, null, null) } catch (e: Exception) { null }?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: ""; val mime = c.getString(2) ?: ""; val mod = c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$folder$name/")
                    else if (mime.startsWith("audio/")) {
                        val u = DocumentsContract.buildDocumentUriUsingTree(root, id)
                        val known = old[u]?.takeIf { it.modified == mod && it.folder == folder }
                        if (known != null) { out += known; continue }
                        val tag = try { android.media.MediaMetadataRetriever().use { r -> r.setDataSource(ctx, u)
                            listOf(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE, android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST,
                                android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).map { r.extractMetadata(it) ?: "" } } } catch (e: Exception) { listOf("", "", "") }
                        out += Song(u, tag[0].ifEmpty { name.substringBeforeLast('.') }, tag[1], tag[2], folder, mod)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(root), "")
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }).also { cache = t to it }
    }

    /** id: "" = categories, "songs", "albums", "album:<name>", "artists", "artist:<name>", "folders", "folder:<path>". */
    fun list(ctx: Context, id: String, q: String): JSONArray {
        val a = JSONArray()
        fun item(id: String, title: String, sub: String, play: Boolean = false) =
            a.put(JSONObject().put("id", id).put("title", title).put("sub", sub).put("play", play).put("browse", !play))
        if (id.isEmpty() && q.isEmpty()) {
            item("songs", "Songs", "All tracks"); item("albums", "Albums", ""); item("artists", "Artists", ""); item("folders", "Folders", "")
            return a
        }
        val all = songs(ctx); val k = id.substringAfter(':')
        val rows = all.filter { s ->
            when {
                id.startsWith("album:") -> s.album == k
                id.startsWith("artist:") -> s.artist == k
                id.startsWith("folder:") -> s.folder == k
                else -> true
            } && (q.isEmpty() || s.title.contains(q, true) || s.artist.contains(q, true) || s.album.contains(q, true))
        }
        fun group(prefix: String, key: (Song) -> String, title: (String) -> String) =
            rows.groupBy(key).toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (g, v) -> item("$prefix:$g", title(g), "${v.size} songs") }
        // Song id = "song:<document uri>": stable across rescans, unlike a list position.
        fun songs() = rows.take(500).forEach { s -> item("song:${s.uri}", s.title, "${s.artist}  ${s.album}", true) }
        when {
            id == "albums" && q.isEmpty() -> group("album", { it.album }) { it }
            id == "artists" && q.isEmpty() -> group("artist", { it.artist }) { it }
            id == "folders" && q.isEmpty() -> group("folder", { it.folder }) { it.trimEnd('/').ifEmpty { "Top folder" } }
            else -> songs()
        }
        return a
    }

    /** Opens the file in the active player if it takes audio files (Android may block it while CarApp is in the background). */
    fun play(ctx: Context, uri: Uri, player: String) {
        val base = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pkg = player.takeIf { it.isNotEmpty() && ctx.packageManager.queryIntentActivities(Intent(base).setPackage(it), 0).isNotEmpty() }
        try { ctx.startActivity(if (pkg != null) base.setPackage(pkg) else base) } catch (e: Exception) { Log.e("LazyCar", "play file", e) }
    }
}
