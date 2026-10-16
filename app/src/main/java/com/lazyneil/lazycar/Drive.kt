package com.lazyneil.lazycar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/** Tablet in the car: the phone hotspot as a Wi-Fi suggestion, the Maps split, and the phone's GO waking the tablet. */
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
        Log.e("LazyCar", "hotspot suggestion '$ssid' secured=${pass.length >= 8} status=$r")
    }

    /** Maps beside CarApp: by itself when the user turned on [SplitService], else the one-tap reminder. */
    fun split(ctx: Context) {
        if (!Ui.openWithMaps(ctx)) return
        SplitService.self?.split() ?: hint(ctx)
    }

    /** No split service: the launcher's own pair icon is one tap. */
    fun hint(ctx: Context) {
        if (!Ui.openWithMaps(ctx)) return
        Toast.makeText(ctx, "Maps beside CarApp: tap the Maps + CarApp pair on the home screen, or turn on CarApp split " +
            "in Android Settings > Accessibility to have it open by itself", Toast.LENGTH_LONG).show()
    }

    /** Phone GO: wake the tablet's CarApp ([SplitService] listens). A UDP broadcast once a second for 20 s, the
     *  tablet ignores repeats. 255.255.255.255 plus each interface's subnet broadcast (hotspot or shared Wi-Fi). */
    fun wakeTablet(ctx: Context) = thread(name = "wake-send", isDaemon = true) {
        val p = Prefs(ctx); val msg = SplitService.WAKE.toByteArray()
        try { DatagramSocket().use { s ->
            s.broadcast = true
            repeat(20) {
                if (p.shutDown) return@thread
                val to = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
                try { for (ni in NetworkInterface.getNetworkInterfaces()) if (ni.isUp && !ni.isLoopback) ni.interfaceAddresses.mapNotNullTo(to) { it.broadcast } } catch (_: Exception) {}
                for (a in to) try { s.send(DatagramPacket(msg, msg.size, a, SplitService.PORT)) } catch (_: Exception) {}
                Thread.sleep(1000)
            }
        } } catch (e: Exception) { Log.e("LazyCar", "wake send: $e") }
    }
}

/**
 * Tablet only and optional: the user turns it on once in Android's Accessibility settings, CarApp never turns it on or
 * back on. Two jobs: (1) wake CarApp when the phone presses GO (system bound, so it listens with no notification and
 * no other service, also after Shut down); (2) open the Maps + CarApp split the way a user does on ZUI, which has no
 * app API for it (LAUNCH_ADJACENT and launch windowing modes are ignored): the saved pair icon on the home screen,
 * else Recents, Maps' icon, Split screen, CarApp.
 */
class SplitService : AccessibilityService() {
    companion object {
        @Volatile var self: SplitService? = null
        const val PORT = 47478
        const val WAKE = "CARWAKE"
        private const val ZUI_LAUNCHER = "com.zui.launcher"
        private const val ZUI_PICKER = "com.lenovo.screensplit"
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var sock: DatagramSocket? = null

    override fun onServiceConnected() {
        if (!Role.isTablet(this)) { disableSelf(); return }
        self = this
        thread(name = "wake-listen", isDaemon = true) { listen() }
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { self = null; sock?.close(); main.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun listen() { try {
        DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(PORT)) }.use { s ->
            sock = s; val buf = ByteArray(16)
            while (self === this) {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                if (String(buf, 0, p.length) == WAKE) main.post { wake() }
            }
        }
    } catch (e: Exception) { if (self === this) Log.e("LazyCar", "wake listen: $e") } }

    private var wokeAt = -60_000L
    /** Phone GO: CarApp to the front with the screen on. Like any open it ends a Shut down and starts the link; its
     *  launcher flag asks for the split. Repeats within 30 s are the same GO. */
    private fun wake() {
        val now = SystemClock.elapsedRealtime(); if (now - wokeAt < 30_000) return; wokeAt = now
        Log.e("LazyCar", "wake: phone GO")
        startActivity(Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("launcher", true).putExtra("wake", true))
    }

    private var busyUntil = 0L
    fun split() {
        val now = SystemClock.elapsedRealtime(); if (now < busyUntil) return
        val maps = Prefs(this).launchA.ifEmpty { "com.google.android.apps.maps" }
        if (isSplit(maps)) return
        val launch = packageManager.getLaunchIntentForPackage(maps) ?: return fail("$maps not installed")
        val mapsLabel = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(maps, 0)).toString() } catch (_: Exception) { "Maps" }
        val ours = getString(R.string.app_name)
        busyUntil = now + 20_000
        Log.e("LazyCar", "split: start")
        fun done() { busyUntil = 0; Log.e("LazyCar", "split: done split=${isSplit(maps)}") }
        // ponytail: English ZUI labels ("Split screen"); a localized ZUI needs its string here.
        fun recents() {
            // Maps first in front, so its task tile is in Recents; ZUI puts the app picked in Recents on the left.
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main.postDelayed({
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                main.postDelayed({
                    find("Maps icon in Recents", { node(mapsLabel) { it.packageName == ZUI_LAUNCHER && it.viewIdResourceName == "$ZUI_LAUNCHER:id/icon" } }) {
                        find("Split screen menu", { node("Split screen") { it.packageName == ZUI_LAUNCHER } }) {
                            find("CarApp in the picker", { node(ours) { it.packageName == ZUI_PICKER && it.viewIdResourceName == "$ZUI_PICKER:id/appname_tv" } }) {
                                main.postDelayed({ ratio(); main.postDelayed({ done() }, 800) }, 1200)
                            }
                        }
                    }
                }, 1000)   // Recents settled
            }, 1500)
        }
        // The user's saved Maps + CarApp pair on the home screen or dock: one tap, its own sides and ratio.
        // Not there (another home page, never saved) or no split after it: the Recents route.
        performGlobalAction(GLOBAL_ACTION_HOME)
        main.postDelayed({
            find("Maps + CarApp pair", { pair(mapsLabel, ours) }, tries = 4, orElse = { Log.e("LazyCar", "split: no pair icon"); recents() }) {
                main.postDelayed({ if (isSplit(maps)) done() else { Log.e("LazyCar", "split: pair tap gave no split"); recents() } }, 2500)
            }
        }, 1200)
    }

    /** A launcher icon whose label names both apps (ZUI app pairs are labelled with both names). */
    private fun pair(a: String, b: String): AccessibilityNodeInfo? = windows.mapNotNull { it.root }.firstNotNullOfOrNull { root ->
        root.findAccessibilityNodeInfosByText(a).firstOrNull { n -> n.packageName == ZUI_LAUNCHER &&
            listOfNotNull(n.text, n.contentDescription).any { it.contains(a, true) && it.contains(b, true) } } }

    /** Polls up to 4 s for a node, taps its center, then [then]. */
    private fun find(what: String, get: () -> AccessibilityNodeInfo?, tries: Int = 14, orElse: () -> Unit = { fail("no $what") }, then: () -> Unit) {
        val n = get()
        if (n != null) {
            // A click action survives the Recents animation; a tap at the node's bounds is the fallback.
            val r = Rect().also { n.getBoundsInScreen(it) }
            val clicked = n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) tap(r.exactCenterX(), r.exactCenterY())
            Log.e("LazyCar", "split: $what at $r click=$clicked"); main.postDelayed(then, 900); return
        }
        if (tries <= 0) return orElse()
        main.postDelayed({ find(what, get, tries - 1, orElse, then) }, 300)
    }

    private fun node(text: String, ok: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }.firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByText(text).firstOrNull { (it.text?.toString() == text || it.contentDescription?.toString() == text) && ok(it) } }

    private fun tap(x: Float, y: Float) = dispatchGesture(GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 60)).build(), null, null)

    /** The divider to the user's saved share for the left app (the launcher pairs use 65 %). */
    private fun ratio() {
        val b = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }?.let { Rect().also(it::getBoundsInScreen) }
            ?: run { Log.e("LazyCar", "split: no divider window"); return }
        val w = resources.displayMetrics.widthPixels
        val to = w * Prefs(this).splitRatio / 100f
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(
            Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()); lineTo(to, b.exactCenterY()) }, 0, 500)).build(), null, null)
    }

    /** Maps and CarApp both visible side by side (not PiP, not full width). */
    private fun isSplit(maps: String): Boolean {
        val w = resources.displayMetrics.widthPixels
        val side = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode &&
            Rect().also(it::getBoundsInScreen).width() < w }.mapNotNull { it.root?.packageName?.toString() }
        return maps in side && packageName in side
    }

    private fun fail(why: String) { Log.e("LazyCar", "split: $why"); busyUntil = 0; Drive.hint(this) }
}
