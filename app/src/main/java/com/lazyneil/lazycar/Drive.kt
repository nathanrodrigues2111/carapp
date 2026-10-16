package com.lazyneil.lazycar

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.widget.Toast

/** Tablet in the car without accessibility: the phone hotspot as a Wi-Fi suggestion, and a hint for the Maps split. */
object Drive {
    /** The phone hotspot as a suggestion: Android joins it by itself when it is in range (the first time it asks once,
     *  in a notification, to allow CarApp's suggestion). Never switches Wi-Fi on. Idempotent, safe to call often. */
    fun join(ctx: Context) {
        val p = Prefs(ctx)
        if (!Role.isTablet(ctx) || p.shutDown) return
        val ssid = p.hotspotSsid.trim(); val pass = p.hotspotPass
        val sug = WifiNetworkSuggestion.Builder().setSsid(ssid).apply { if (pass.length >= 8) setWpa2Passphrase(pass) }.build()
        val wm = ctx.getSystemService(WifiManager::class.java)
        // A changed name or password replaces the old suggestion.
        val old = wm.networkSuggestions.filter { it != sug }
        if (old.isNotEmpty()) wm.removeNetworkSuggestions(old)
        val r = wm.addNetworkSuggestions(listOf(sug))
        android.util.Log.e("LazyCar", "hotspot suggestion '$ssid' secured=${pass.length >= 8} status=$r")
    }

    /** Split screen is the launcher's own pair icon (apps cannot start a split on this tablet): remind, never auto tap. */
    fun hint(ctx: Context) {
        if (!Ui.openWithMaps(ctx)) return
        // The launcher only has the pairs the user saved (Maps + Poweramp, Maps + Spotify): say how to make ours once.
        Toast.makeText(ctx, "Maps beside CarApp: tap the Maps + CarApp pair on the home screen. No pair yet? Open Maps and CarApp " +
            "side by side once, then in Recents tap the icon above them and choose Save app pair", Toast.LENGTH_LONG).show()
    }
}
