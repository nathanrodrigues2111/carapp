package com.lazyneil.lazycar

import android.content.Context

/** Head unit / Android Auto launch is hidden (UI and GO ignore it) until this is true. */
const val SHOW_HEADUNIT = false

/** Thin SharedPreferences wrapper. */
class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("lazycar", Context.MODE_PRIVATE)
    private val tablet = Role.isTablet(ctx)
    private fun s(k: String) = sp.getString(k, "") ?: ""
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    // Blank = first installed player that allows the tablet speaker copy (Spotify blocks it).
    var playerPkg: String get() = s("player").ifEmpty { CAPTURABLE.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null } ?: "" }
        set(v) = put("player", v)
    var mac1: String get() = s("mac1"); set(v) = put("mac1", v)
    var mac3: String get() = s("mac3"); set(v) = put("mac3", v)
    var name1: String get() = s("name1"); set(v) = put("name1", v)
    var name3: String get() = s("name3"); set(v) = put("name3", v)
    var headunitIp: String get() = s("ip"); set(v) = put("ip", v)
    // Widget tap opens launchA, plus launchB beside it in split screen. Blank = none.
    // Tablet default is the user's Maps + Spotify pair; the phone defaults to nothing (GO opens the player).
    var launchA: String get() = sp.getString("launchA", null) ?: if (tablet) "com.google.android.apps.maps" else ""
        set(v) = put("launchA", v)
    var launchB: String get() = sp.getString("launchB", null) ?: if (tablet) "com.spotify.music" else ""
        set(v) = put("launchB", v)
    // "none", "one" (launchA only) or "split" (launchA left, launchB right). Older installs infer it.
    var launchMode: String get() = sp.getString("launchMode", null)
            ?: when { launchB.isNotEmpty() -> "split"; launchA.isNotEmpty() -> "one"; else -> "none" }
        set(v) = put("launchMode", v)
    // Left app share of the width in split mode; 65 matches the launcher's saved pair.
    var splitRatio: Int get() = i("splitRatio", 65); set(v) = sp.edit().putInt("splitRatio", v).apply()
    /** "auto", "lenovo" (launcher saved pair) or "standard" (adjacent launch, any Android 12L+ tablet). */
    var splitMethod: String get() = sp.getString("splitMethod", null) ?: "auto"; set(v) = put("splitMethod", v)
    // Tablet joins this phone hotspot on start (saved network on the tablet).
    var hotspotSsid: String get() = sp.getString("hotspotSsid", null) ?: "Nathan's Oneplus"; set(v) = put("hotspotSsid", v)
    private fun b(k: String, def: Boolean) = sp.getBoolean(k, def)
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var headunit: Boolean get() = SHOW_HEADUNIT && b("headunit", false); set(v) = putB("headunit", v)
    var amoled: Boolean get() = b("amoled", true); set(v) = putB("amoled", v)
    var btOffOnStop: Boolean get() = b("btoff", false); set(v) = putB("btoff", v)
    private fun i(k: String, def: Int) = sp.getInt(k, def)
    private fun l(k: String, def: Long) = sp.getLong(k, def)
    // GO state machine: 0 IDLE, 1 STARTING, 2 ON, 3 STOPPING. Writing state stamps the time.
    var state: Int get() = i("state", 0)
        set(v) = sp.edit().putInt("state", v).putLong("stateTs", System.currentTimeMillis()).apply()
    val stateTs: Long get() = l("stateTs", 0L)

    // ---- GO-time snapshot, restored by STOP (replaces the old "BT off on stop" switch).
    //      Defaults are "was on/connected" so a STOP with no prior GO tears nothing down. ----
    var snapBtWasOn: Boolean get() = b("snapBt", true); set(v) = putB("snapBt", v)
    var snapMusicWasActive: Boolean get() = b("snapMusic", true); set(v) = putB("snapMusic", v)
    var snapConn1: Boolean get() = b("snapC1", true); set(v) = putB("snapC1", v)
    var snapConn3: Boolean get() = b("snapC3", true); set(v) = putB("snapC3", v)
    var snapDataWasOn: Boolean get() = b("snapData", true); set(v) = putB("snapData", v)
    var snapHotspotWasOn: Boolean get() = b("snapHotspot", true); set(v) = putB("snapHotspot", v)
    // GO-time STREAM_MUSIC index, restored by STOP. -1 = not captured (don't restore).
    var snapMusicVol: Int get() = i("snapMusicVol", -1); set(v) = sp.edit().putInt("snapMusicVol", v).apply()

    // ---- start volume on GO (one level for the Bluetooth speaker) ----
    // Migrates the old per-device vol1 into the single vol on first read. Default 40%, on.
    var vol: Int get() = i("vol", i("vol1", 40)); set(v) = sp.edit().putInt("vol", v).apply()
    var volOn: Boolean get() = b("volOn", true); set(v) = putB("volOn", v)

    // ---- optional network setup on GO (Task 4) ----
    var hotspot: Boolean get() = b("hotspot", false); set(v) = putB("hotspot", v)
    var mobileData: Boolean get() = b("mobileData", false); set(v) = putB("mobileData", v)
    // Accent colour: ARGB, 0 = System (Material dynamic colour).
    var accent: Int get() = i("accent2", 0); set(v) = sp.edit().putInt("accent2", v).apply()

    companion object {
        val CAPTURABLE = listOf("revanced.youtube.music", "com.google.android.apps.youtube.music", "com.maxmpz.audioplayer", "org.videolan.vlc")
    }
}
