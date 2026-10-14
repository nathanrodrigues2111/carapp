package com.lazyneil.lazycar

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared settings, both ways over the phone-tablet media link: {k, v, ts} items, last writer wins.
 * A full exchange on connect, then one item per local change. Device-local settings are not listed.
 * JBL and tablet volume are live levels and already travel as volSet / tvol.
 */
object SettingsSync {
    private class Key(val name: String, val file: String, val key: String, val def: Any)
    private val KEYS = listOf(
        Key("openWithMaps", "lazycar", "openWithMaps", true),
        Key("duckNav", "lazycar", "duckNav", true),
        Key("speakerOn", "speaker", "on", true),        // phone "send" and tablet "play" are one switch
        Key("syncTrim", "speaker", "delay", 0),
        Key("gpsShare", "lazygps", "mode", true))        // stored as a mode; shared as on / off

    private lateinit var app: Context
    private var send: ((JSONArray) -> Unit)? = null
    private var applying = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()   // strong refs
    /** Called on the main thread after a remote change was applied (open Settings screens refresh). */
    var onRemote: (() -> Unit)? = null

    private fun sp(f: String) = app.getSharedPreferences(f, Context.MODE_PRIVATE)
    private fun stamps() = sp("prefsync")

    private fun value(k: Key): Any = when {
        k.name == "gpsShare" -> sp(k.file).getString(k.key, null) != "stop"
        k.def is Int -> sp(k.file).getInt(k.key, k.def)
        k.def is String -> sp(k.file).getString(k.key, null) ?: ""
        else -> sp(k.file).getBoolean(k.key, k.def as Boolean)
    }

    private fun item(k: Key) = JSONObject().put("k", k.name).put("v", value(k)).put("ts", stamps().getLong(k.name, 0))

    /** Start watching local changes; [sender] puts an items array on the link (no-op when not connected). */
    fun init(ctx: Context, sender: (JSONArray) -> Unit) {
        send = sender
        if (::app.isInitialized) return
        app = ctx.applicationContext
        KEYS.map { it.file }.distinct().forEach { f ->
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
                if (applying) return@OnSharedPreferenceChangeListener
                val k = KEYS.firstOrNull { it.file == f && it.key == changed } ?: return@OnSharedPreferenceChangeListener
                stamps().edit().putLong(k.name, System.currentTimeMillis()).apply()
                send?.invoke(JSONArray().put(item(k)))
            }
            listeners += l; sp(f).registerOnSharedPreferenceChangeListener(l)
        }
    }

    /** Every shared item with its timestamp, for the exchange on connect. */
    fun all() = JSONArray().apply { KEYS.forEach { put(item(it)) } }

    /** Apply remote items that are newer than ours. Main thread. */
    fun apply(items: JSONArray?) = main.post {
        items ?: return@post
        var changed = false
        for (i in 0 until items.length()) {
            val j = items.getJSONObject(i)
            val k = KEYS.firstOrNull { it.name == j.optString("k") } ?: continue
            val ts = j.optLong("ts")
            if (ts <= stamps().getLong(k.name, 0) || j.opt("v") == value(k)) {
                if (ts > stamps().getLong(k.name, 0)) stamps().edit().putLong(k.name, ts).apply()
                continue
            }
            applying = true
            try {
                when {
                    k.name == "gpsShare" -> com.lazyneil.lazycar.gps.GpsService.launch(app,
                        if (!j.optBoolean("v")) "stop" else if (Role.isTablet(app)) "receive" else "send")
                    k.def is Int -> sp(k.file).edit().putInt(k.key, j.optInt("v")).commit()
                    k.def is String -> sp(k.file).edit().putString(k.key, j.optString("v")).commit()
                    else -> sp(k.file).edit().putBoolean(k.key, j.optBoolean("v")).commit()
                }
                stamps().edit().putLong(k.name, ts).commit()
            } finally { applying = false }
            if (k.name == "speakerOn") react(j.optBoolean("v"))
            changed = true
        }
        if (changed) onRemote?.invoke()
    }

    /** Same reaction as flipping the Tablet speaker switch on this device. */
    private fun react(on: Boolean) {
        if (Role.isTablet(app)) com.lazyneil.lazycar.audio.StreamReceiver.sync(app)
        else if (on) com.lazyneil.lazycar.audio.StreamSender.start(app) else com.lazyneil.lazycar.audio.StreamSender.stop(app)
    }
}
