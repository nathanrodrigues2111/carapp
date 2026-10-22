package com.lazyneil.lazycar

import android.content.Context

/** Thin SharedPreferences wrapper. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lazycar", Context.MODE_PRIVATE)
    private fun s(k: String) = sp.getString(k, "") ?: ""
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun b(k: String, def: Boolean) = sp.getBoolean(k, def)
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** The car stereo the tablet connects on the phone's GO. */
    var mac1: String get() = s("mac1"); set(v) = put("mac1", v)
    var name1: String get() = s("name1"); set(v) = put("name1", v)
    // Tablet joins this phone hotspot (a Wi-Fi suggestion, see Drive.join). Blank password = open network.
    var hotspotSsid: String get() = sp.getString("hotspotSsid", null)?.takeIf { it.isNotBlank() } ?: "Nathan's Oneplus"; set(v) = put("hotspotSsid", v)
    var hotspotPass: String get() = s("hotspotPass"); set(v) = put("hotspotPass", v)

    /** Tablet: saved launcher pairs the helper has seen ("App pair: Maps and Spotify"), and the one GO opens ("" = none).
     *  Never chosen: the old Maps + Spotify default when that pair exists. */
    var pairs: Set<String> get() = sp.getStringSet("pairs", emptySet()) ?: emptySet(); set(v) = sp.edit().putStringSet("pairs", v).apply()
    var goPair: String get() = sp.getString("goPair", null) ?: pairs.firstOrNull { it.contains("Maps") && it.contains("Spotify") } ?: ""
        set(v) = put("goPair", v)

    // GO state machine: 0 IDLE, 1 STARTING, 2 ON, 3 STOPPING. Writing state stamps the time.
    var state: Int get() = sp.getInt("state", 0)
        set(v) = sp.edit().putInt("state", v).putLong("stateTs", System.currentTimeMillis()).apply()
    val stateTs: Long get() = sp.getLong("stateTs", 0L)

    // ---- GO-time snapshot, restored by Shut down. Defaults are "was on/connected" so a Shut down with no prior GO
    //      tears nothing down. ----
    // Phone: hotspot, mobile data and location. Tablet: Wi-Fi, Bluetooth and the car stereo link.
    var snapApWasOn: Boolean get() = b("snapAp", true); set(v) = putB("snapAp", v)
    var snapLocWasOn: Boolean get() = b("snapLoc", true); set(v) = putB("snapLoc", v)
    var snapDataWasOn: Boolean get() = b("snapData", true); set(v) = putB("snapData", v)
    var snapWifiWasOn: Boolean get() = b("snapWifi", true); set(v) = putB("snapWifi", v)
    var snapBtWasOn: Boolean get() = b("snapBt", true); set(v) = putB("snapBt", v)
    var snapConn1: Boolean get() = b("snapC1", true); set(v) = putB("snapC1", v)
    /** After a restore the snapshot is spent: a later Shut down without GO must not undo the user's own changes. */
    fun clearSnap() = sp.edit().apply { listOf("snapAp", "snapLoc", "snapData", "snapWifi", "snapBt", "snapC1").forEach { remove(it) } }.apply()
    /** Shut down: nothing of CarApp runs (boot, hotspot join, link) until the app is opened or GO is pressed. */
    var shutDown: Boolean get() = b("shutDown", false); set(v) = putB("shutDown", v)
}
