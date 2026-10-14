package com.lazyneil.lazycar.media

import android.content.ComponentName
import android.content.ContentUris
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
import android.provider.MediaStore
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

/**
 * Phone side of "control like Spotify Connect": the notification listener grant is what lets us read
 * every app's media session. Serves Now Playing + library browsing to the tablet over TCP [Link.PORT].
 */
class MediaListener : NotificationListenerService() {
    companion object {
        @Volatile var bound = false
        @Volatile var clients = 0
        @Volatile var player = ""
        @Volatile var tabletPct = -1     // tablet speaker level, reported by the tablet
        private var self: MediaListener? = null
        /** Phone slider: tell every tablet to set its speaker level. */
        fun setTablet(pct: Int) { tabletPct = pct; self?.let { m -> m.out.forEach { m.send(it, JSONObject().put("t", "tvol").put("pct", pct).put("user", true).toString()) } } }
        fun granted(ctx: Context) = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        fun settingsIntent() = Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    class Client(val s: Socket) { val w: Writer = s.getOutputStream().bufferedWriter(); var artKey: String? = null }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    val out = CopyOnWriteArrayList<Client>()
    @Volatile private var alive = false
    private var server: ServerSocket? = null
    private var probe: DatagramSocket? = null
    private var ctl: MediaController? = null
    private var browser: MediaBrowser? = null

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onSessionDestroyed() = pick()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick() }
    private val tick = object : Runnable {
        override fun run() { push(); main.postDelayed(this, 1000) }
    }

    override fun onListenerConnected() {
        bound = true; alive = true; self = this
        com.lazyneil.lazycar.SettingsSync.init(this) { items ->
            val line = JSONObject().put("t", "prefs").put("items", items).toString(); out.forEach { send(it, line) } }
        val msm = getSystemService(MediaSessionManager::class.java)
        msm.addOnActiveSessionsChangedListener(sessions, ComponentName(this, MediaListener::class.java), main)
        pick(); main.post(tick)
        thread(name = "media-server") { serve() }
        thread(name = "media-probe") { answerProbes() }
    }

    override fun onListenerDisconnected() {
        bound = false; alive = false; self = null
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessions)
        ctl?.unregisterCallback(cb); ctl = null
        main.removeCallbacks(tick)
        browser?.disconnect(); browser = null
        server?.close(); probe?.close()
        out.forEach { it.s.close() }; out.clear(); clients = 0
    }

    /** Playing session wins, else the most recent one. */
    private fun pick() {
        val list = try {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this, MediaListener::class.java))
        } catch (e: SecurityException) { emptyList() }.filter { it.packageName != "com.android.server.telecom" }
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (next?.sessionToken != ctl?.sessionToken) {
            ctl?.unregisterCallback(cb); ctl = next; next?.registerCallback(cb, main)
            if (next?.packageName != browser?.serviceComponent?.packageName) { browser?.disconnect(); browser = null }
        }
        player = next?.packageName ?: ""
        push()
    }

    private fun label(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    private fun jpeg(b: Bitmap?, px: Int, q: Int = 80): String? {
        b ?: return null
        val s = px.toFloat() / maxOf(b.width, b.height)
        val small = if (s < 1) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
        val o = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, q, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    private fun bitmapFrom(uri: String?): Bitmap? = try {
        uri?.let { contentResolver.openInputStream(Uri.parse(it))?.use(BitmapFactory::decodeStream) }
    } catch (_: Exception) { null }

    // The session's bitmaps are downscaled by the framework, so the art URI (full resolution) goes first.
    private var accentKey = ""; private var trackAccent = 0
    private fun art(m: MediaMetadata): Bitmap? =
        bitmapFrom(m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI))
        ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    /** Now Playing to every tablet; art only when the track changed for that client. */
    private fun push() {
        if (out.isEmpty()) return
        val c = ctl
        val m = c?.metadata; val st = c?.playbackState
        val j = JSONObject().put("t", "np").put("pkg", c?.packageName ?: "").put("app", c?.packageName?.let(::label) ?: "")
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
        val am = getSystemService(AudioManager::class.java)
        Volumes.jblStream().let { st -> j.put("vol", am.getStreamVolume(st)).put("volMax", am.getStreamMaxVolume(st)) }
        j.put("go", com.lazyneil.lazycar.GoAction.effectiveState(com.lazyneil.lazycar.Prefs(this)) == com.lazyneil.lazycar.GoAction.ON)
        val key = listOf(c?.packageName, j.optString("title"), j.optString("artist"), j.optString("album")).joinToString("|")
        val needArt = out.any { it.artKey != key }
        if (key != accentKey) { accentKey = key; com.lazyneil.lazycar.Accent.art = m?.let { art(it) }; trackAccent = com.lazyneil.lazycar.Accent.now(this); com.lazyneil.lazycar.Accent.publish(this) }
        j.put("accent", trackAccent)
        val a = if (needArt && m != null) jpeg(art(m), 1024, 92) else null
        for (cl in out) {
            val line = if (cl.artKey != key) JSONObject(j.toString()).put("art", a ?: "").toString().also { cl.artKey = key } else j.toString()
            send(cl, line)
        }
    }

    fun send(cl: Client, line: String) = io.execute {
        try { cl.w.write(line); cl.w.write("\n"); cl.w.flush() } catch (_: Exception) { drop(cl) }
    }

    private fun drop(cl: Client) { try { cl.s.close() } catch (_: Exception) {}; out.remove(cl); clients = out.size }

    private fun serve() { try {
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Link.PORT)) }.use { ss ->
            server = ss
            while (alive) {
                val s = ss.accept().apply { tcpNoDelay = true }
                val cl = Client(s)
                out += cl; clients = out.size
                main.post { push() }
                thread(name = "media-client") {
                    try { s.getInputStream().bufferedReader().forEachLine { l -> main.post { handle(cl, JSONObject(l)) } } }
                    catch (_: Exception) {}
                // A tablet that reconnects left its old socket dead (Wi-Fi drop): close it now, not after TCP gives up.
                out.filter { it.s.inetAddress == s.inetAddress }.forEach(::drop)
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
        val t = ctl?.transportControls
        when (j.optString("c")) {
            "toggle" -> if (ctl?.playbackState?.state == PlaybackState.STATE_PLAYING) t?.pause() else t?.play()
            "next" -> t?.skipToNext()
            "prev" -> t?.skipToPrevious()
            "seek" -> t?.seekTo(j.optLong("pos"))
            "vol" -> getSystemService(AudioManager::class.java).adjustStreamVolume(Volumes.jblStream(),
                if (j.optInt("d") > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0).also { main.postDelayed({ push() }, 150) }
            "volSet" -> com.lazyneil.lazycar.media.Volumes.setJblLocal(this, j.optInt("pct")).also { main.postDelayed({ push() }, 150) }
            "tvol" -> tabletPct = j.optInt("pct")
            "prefs" -> { com.lazyneil.lazycar.SettingsSync.apply(j.optJSONArray("items"))
                if (j.optBoolean("full")) send(cl, JSONObject().put("t", "prefs").put("items", com.lazyneil.lazycar.SettingsSync.all()).toString()) }
            "browse" -> browse(cl, j)
            "playId" -> withBrowser(cl, j) { b -> MediaController(this, b.sessionToken).transportControls.playFromMediaId(j.optString("id"), null) }
            "files" -> io.execute { reply(cl, j, Files.list(this, j.optString("id"), j.optString("q"))) }
            "playFile" -> main.post { Files.play(this, j.optLong("song"), player) }
        }
        if (j.optString("c") in setOf("toggle", "next", "prev", "seek")) main.postDelayed({ push() }, 300)
    }

    private fun reply(cl: Client, req: JSONObject, items: JSONArray, err: String = "") =
        send(cl, JSONObject().put("t", "list").put("req", req.optInt("req")).put("items", items).put("err", err).toString())

    /** Connects to the current player's MediaBrowserService, the same one Android Auto uses. */
    private fun withBrowser(cl: Client, req: JSONObject, then: (MediaBrowser) -> Unit) {
        val pkg = player
        browser?.takeIf { it.isConnected && it.serviceComponent.packageName == pkg }?.let { then(it); return }
        val svc = packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo
        if (svc == null) { reply(cl, req, JSONArray(), "${label(pkg).ifEmpty { "No player" }} has no library to browse; controls still work"); return }
        browser?.disconnect()
        var b: MediaBrowser? = null
        b = MediaBrowser(this, ComponentName(svc.packageName, svc.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() { then(b!!) }
            override fun onConnectionFailed() { browser = null; reply(cl, req, JSONArray(), "Browsing not allowed by ${label(pkg)}; controls still work") }
            override fun onConnectionSuspended() { browser = null }
        }, Bundle().apply { putBoolean("android.service.media.extra.RECENT", false) })
        browser = b
        b.connect()
    }

    // A frozen or busy player can leave connect/subscribe hanging: answer within 6 s and reconnect next time.
    private fun browse(cl: Client, req: JSONObject) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        main.postDelayed({ if (!done.get()) { browser?.disconnect(); browser = null; reply(cl, req, JSONArray(), "${label(player)} did not answer") } }, 6000)
        browseNow(cl, req, done)
    }

    private fun browseNow(cl: Client, req: JSONObject, done: java.util.concurrent.atomic.AtomicBoolean) = withBrowser(cl, req) { b ->
        val q = req.optString("q")
        val sink = { items: List<MediaBrowser.MediaItem> -> done.set(true); io.execute { reply(cl, req, JSONArray().apply {
            items.take(300).forEachIndexed { i, it -> val d = it.description
                put(JSONObject().put("id", it.mediaId).put("title", d.title ?: "").put("sub", d.subtitle ?: "")
                    .put("play", it.isPlayable).put("browse", it.isBrowsable)
                    .apply { if (i < 80) jpeg(d.iconBitmap ?: bitmapFrom(d.iconUri?.toString()), 96)?.let { a -> put("icon", a) } })
            } }) } }
        // ponytail: framework MediaBrowser has no search, so search filters the current level only.
        run {
            val id = req.optString("id").ifEmpty { b.root }
            b.subscribe(id, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                    b.unsubscribe(id)
                    sink(if (q.isEmpty()) children else children.filter { "${it.description.title} ${it.description.subtitle}".contains(q, true) })
                }
                override fun onError(parentId: String) { done.set(true); reply(cl, req, JSONArray(), "${label(player)} would not list this") }
            })
        }
    }
}

/** Shared by both sides. TCP for the session, UDP probe on the same number to find the phone. */
object Link {
    const val PORT = 47477
    const val PROBE = "CARMEDIA"
}

/** Local audio files on the phone (MediaStore), the fallback library for any player. */
object Files {
    private val songsUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    /** id: "" = categories, "songs", "albums", "album:<id>", "artists", "artist:<name>", "folders", "folder:<path>". */
    fun list(ctx: Context, id: String, q: String): JSONArray {
        val a = JSONArray()
        fun item(id: String, title: String, sub: String, play: Boolean = false, song: Long = 0) =
            a.put(JSONObject().put("id", id).put("title", title).put("sub", sub).put("play", play).put("browse", !play).put("song", song))
        if (id.isEmpty() && q.isEmpty()) {
            item("songs", "Songs", "All tracks"); item("albums", "Albums", ""); item("artists", "Artists", ""); item("folders", "Folders", "")
            return a
        }
        val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.RELATIVE_PATH)
        val sel = mutableListOf("${MediaStore.Audio.Media.IS_MUSIC} != 0"); val args = mutableListOf<String>()
        when {
            id.startsWith("album:") -> { sel += "${MediaStore.Audio.Media.ALBUM_ID} = ?"; args += id.substringAfter(':') }
            id.startsWith("artist:") -> { sel += "${MediaStore.Audio.Media.ARTIST} = ?"; args += id.substringAfter(':') }
            id.startsWith("folder:") -> { sel += "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"; args += id.substringAfter(':') }
        }
        if (q.isNotEmpty()) { sel += "(${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ? OR ${MediaStore.Audio.Media.ALBUM} LIKE ?)"; repeat(3) { args += "%$q%" } }
        val rows = mutableListOf<Array<String>>()
        try {
            ctx.contentResolver.query(songsUri, cols, sel.joinToString(" AND "), args.toTypedArray(), "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
                while (c.moveToNext()) rows += Array(6) { c.getString(it) ?: "" }
            }
        } catch (e: SecurityException) { return a }
        val group = { col: Int, prefix: String, title: (Array<String>) -> String ->
            rows.groupBy { it[col] }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (k, v) -> item("$prefix:$k", title(v[0]), "${v.size} songs") } }
        when (id) {
            "albums" -> if (q.isEmpty()) group(4, "album") { it[3] } else songs(rows, ::item)
            "artists" -> if (q.isEmpty()) group(2, "artist") { it[2] } else songs(rows, ::item)
            "folders" -> if (q.isEmpty()) group(5, "folder") { it[5].trimEnd('/') } else songs(rows, ::item)
            else -> songs(rows, ::item)
        }
        return a
    }

    private fun songs(rows: List<Array<String>>, item: (String, String, String, Boolean, Long) -> JSONArray) =
        rows.take(500).forEach { item("song:${it[0]}", it[1], "${it[2]}  ${it[3]}", true, it[0].toLong()) }

    /** Opens the file in the active player if it takes audio files, else Poweramp. Needs TapService for the background start. */
    fun play(ctx: Context, song: Long, player: String) {
        val uri = ContentUris.withAppendedId(songsUri, song)
        val base = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pkg = listOf(player, "com.maxmpz.audioplayer").firstOrNull { it.isNotEmpty() &&
            ctx.packageManager.queryIntentActivities(Intent(base).setPackage(it), 0).isNotEmpty() }
        try { ctx.startActivity(if (pkg != null) base.setPackage(pkg) else base) } catch (e: Exception) { Log.e("LazyCar", "play file", e) }
    }
}
