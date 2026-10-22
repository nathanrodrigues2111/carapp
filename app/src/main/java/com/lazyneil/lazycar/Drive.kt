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
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/** Tablet in the car: the phone hotspot as a Wi-Fi suggestion, and the phone's GO waking the tablet. */
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

    /** Phone GO: wake the tablet's CarApp ([SplitService] listens). Once a second for 20 s, the tablet ignores repeats.
     *  A sleeping tablet's Wi-Fi drops broadcasts (measured), so besides the broadcasts every host of each small
     *  subnet (hotspot or home Wi-Fi, /24 or smaller) gets a unicast copy: ~254 tiny packets a second. */
    fun wakeTablet(ctx: Context) = thread(name = "wake-send", isDaemon = true) {
        val p = Prefs(ctx); val msg = SplitService.WAKE.toByteArray()
        try { DatagramSocket().use { s ->
            s.broadcast = true
            repeat(20) {
                if (p.shutDown) return@thread
                val to = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
                try { for (ni in NetworkInterface.getNetworkInterfaces()) if (ni.isUp && !ni.isLoopback) for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast ?: continue; to += b
                    if (ia.networkPrefixLength !in 24..30) continue
                    val base = java.nio.ByteBuffer.wrap(ia.address.address).int and (-1 shl (32 - ia.networkPrefixLength))
                    for (h in 1 until (1 shl (32 - ia.networkPrefixLength)) - 1)
                        to += InetAddress.getByAddress(java.nio.ByteBuffer.allocate(4).putInt(base or h).array())
                } } catch (_: Exception) {}
                for (a in to) try { s.send(DatagramPacket(msg, msg.size, a, SplitService.PORT)) } catch (_: Exception) {}
                Thread.sleep(1000)
            }
        } } catch (e: Exception) { Log.e("LazyCar", "wake send: $e") }
    }
}

/**
 * Optional "CarApp helper": the user turns it on once in Android's Accessibility settings, CarApp never turns it on or
 * back on. Tablet only (Role disables it on the phone). It wakes CarApp when the phone presses GO (system bound, so it
 * listens with no notification and no other service, also after Shut down), then: Wi-Fi on (a Quick Settings tile,
 * [tiles]; apps have no Wi-Fi API on Android 16), Bluetooth on (the system dialog, [press]), the car stereo, and a tap
 * on the launcher pair chosen in Settings ([Prefs.goPair]).
 * ZUI keeps its pairs in the launcher's own database (signature permission) and starts them through hidden task APIs,
 * so a tap on the icon is the only route.
 */
class SplitService : AccessibilityService() {
    companion object {
        @Volatile var self: SplitService? = null
        /** Arm the confirm buttons of the system dialog GO is about to open. Nothing when the user has it off. */
        fun press(vararg texts: String, ms: Long = 30_000) { self?.press(texts.toList(), ms) }
        const val PORT = 47478
        const val WAKE = "CARWAKE"
        private const val ZUI_LAUNCHER = "com.zui.launcher"
        // ponytail: English ZUI labels; a localized ZUI needs its pair prefix here.
        const val PAIR = "App pair: "
        /** "App pair: Maps and Spotify" -> "Maps/Spotify". */
        fun label(pair: String) = pair.removePrefix(PAIR).replace(" and ", "/")
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var sock: DatagramSocket? = null

    override fun onServiceConnected() {
        self = this; Ui.backFromGrant(this)
        if (Role.isTablet(this)) thread(name = "wake-listen", isDaemon = true) { listen() }
    }

    /** GO / Shut down opened a system dialog: its confirm button is pressed while armed. Only system dialogs, only these
     *  labels, only for [ms]. Unarmed the helper does nothing but listen for the phone and note the saved pairs. */
    fun press(texts: List<String>, ms: Long) {
        val now = SystemClock.uptimeMillis()
        pressTexts = (if (now < pressUntil) pressTexts else emptyList()) + texts.filter { it !in pressTexts }
        pressUntil = maxOf(pressUntil, now + ms); main.post { pressNow() }
    }
    @Volatile private var pressTexts = emptyList<String>()
    @Volatile private var pressUntil = 0L
    private var pressedAt = 0L
    private var pairsAt = 0L
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (pressTexts.isNotEmpty()) pressNow()
        // The tablet's home screen came up: remember the user's saved pairs for the "Open on GO" setting.
        if (e?.packageName == ZUI_LAUNCHER && SystemClock.uptimeMillis() - pairsAt > 5000) { pairsAt = SystemClock.uptimeMillis(); seePairs() }
    }
    private fun seePairs() {
        val seen = windows.mapNotNull { it.root }.filter { it.packageName == ZUI_LAUNCHER }
            .flatMap { it.findAccessibilityNodeInfosByText(PAIR) }.mapNotNull { it.contentDescription?.toString() }.filter { it.startsWith(PAIR) }
        val p = Prefs(this)
        if (!p.pairs.containsAll(seen)) { p.pairs = p.pairs + seen; Log.e("LazyCar", "pairs: ${p.pairs}") }
    }
    /** Also polled every 500 ms while armed: a dialog that opens right after a click sends no further events. */
    private val pressTick = Runnable { pressNow() }
    private fun pressNow() {
        val now = SystemClock.uptimeMillis()
        main.removeCallbacks(pressTick)
        if (now >= pressUntil) { pressTexts = emptyList(); return }
        main.postDelayed(pressTick, 500)
        if (now - pressedAt < 800) return   // one dialog at a time: the next one opens after this click
        for (w in windows) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg != "android" && pkg != "com.android.systemui" && pkg != "com.android.settings" && pkg != "com.android.permissioncontroller" && !pkg.startsWith("com.oplus")) continue
            for (t in pressTexts) for (n in root.findAccessibilityNodeInfosByText(t)) {
                if (n.text?.toString()?.trim()?.equals(t, true) != true) continue
                if (click(n)) { pressedAt = now; Log.e("LazyCar", "pressed '$t' in $pkg"); return }
            }
        }
    }
    private fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n; while (c != null && !c.isClickable) c = c.parent
        return c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
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
    /** Phone GO: CarApp to the front with the screen on (like any open it ends a Shut down and starts GPS and the link),
     *  then the chosen pair. Repeats within 30 s are the same GO. */
    private fun wake() {
        val now = SystemClock.elapsedRealtime(); if (now - wokeAt < 30_000) return; wokeAt = now
        val pm = getSystemService(android.os.PowerManager::class.java)
        val first = !opened   // a later GO before a Shut down keeps what the first one did and its snapshot
        if (first) wokeScreen = !pm.isInteractive
        opened = true
        Log.e("LazyCar", "wake: phone GO (screen was ${if (wokeScreen) "off" else "on"})")
        GoAction.quietUntil = 0   // a GO right after a Shut down is a real open, never "back from Shut down's dialog"
        // Screen on first: an activity's turnScreenOn does nothing when CarApp's task already exists (onNewIntent).
        @Suppress("DEPRECATION") pm.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "CarApp:wake").acquire(5000)
        startActivity(Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("wake", true))
        if (first) main.postDelayed({ tabletGo() }, 1500)   // past the keyguard CarApp dismissed
    }

    /** Tablet side of GO, each step only when needed: Wi-Fi on, Bluetooth on, the car stereo, then the pair. */
    private fun tabletGo() {
        val p = Prefs(this)
        val bt = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        p.snapWifiWasOn = getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled
        p.snapBtWasOn = bt?.isEnabled == true
        Log.e("LazyCar", "tablet go: wifi=${p.snapWifiWasOn} bt=${p.snapBtWasOn} stereo=${p.mac1.ifEmpty { "none" }}")
        fun stereo(n: Int) {
            if (bt?.isEnabled != true) { if (n > 0) main.postDelayed({ stereo(n - 1) }, 500) else { Log.e("LazyCar", "tablet go: bluetooth still off"); openPair() }; return }
            if (p.mac1.isEmpty()) { openPair(); return }
            GoAction.a2dpConnectedDevices(this) { c ->
                p.snapConn1 = p.mac1 in c
                if (!p.snapConn1) GoAction.connectA2dp(this, p.mac1)
                Log.e("LazyCar", "tablet go: stereo ${if (p.snapConn1) "already connected" else "connect"}")
                openPair()
            }
        }
        fun bluetooth() {
            if (bt != null && !bt.isEnabled) { press(listOf("Allow", "Turn on"), 30_000)
                Ui.openSettings(this, Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), fallback = false) }
            stereo(30)
        }
        if (!p.snapWifiWasOn) tiles(listOf(GoAction.WIFI to true), done = { bluetooth() }) else bluetooth()
    }

    /** One Quick Settings pass: each tile whose description contains a label goes to its wanted state. Checks first, so
     *  a tile already right is left alone. Tiles not found in 6 s are logged. */
    private fun tiles(want: List<Pair<String, Boolean>>, done: () -> Unit) {
        val left = want.toMutableList(); val until = SystemClock.uptimeMillis() + 6000
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        fun step() {
            val roots = windows.mapNotNull { it.root }.filter { it.packageName == "com.android.systemui" }
            for ((label, on) in left.toList()) {
                val n = roots.firstNotNullOfOrNull { r -> r.findAccessibilityNodeInfosByText(label).firstOrNull { it.isCheckable && it.contentDescription?.contains(label, true) == true } } ?: continue
                Log.e("LazyCar", "tile '$label' ${if (n.isChecked == on) "already $on" else "${n.isChecked} -> $on ok=${click(n)}"}")
                left.remove(label to on)
            }
            if (left.isNotEmpty() && SystemClock.uptimeMillis() < until) { main.postDelayed({ step() }, 400); return }
            left.forEach { Log.e("LazyCar", "tile '${it.first}' not found") }
            main.postDelayed({ performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE); done() }, 500)
        }
        main.postDelayed({ step() }, 600)
    }

    /** The chosen saved pair: home screen, tap its icon (polls 3 s). Not on the visible home page = a hint. */
    private fun openPair() {
        val pair = Prefs(this).goPair; if (pair.isEmpty()) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        fun find(n: Int) {
            val node = windows.mapNotNull { it.root }.filter { it.packageName == ZUI_LAUNCHER }
                .flatMap { it.findAccessibilityNodeInfosByText(PAIR) }.firstOrNull { it.contentDescription?.toString() == pair }
            when {
                node != null && click(node) -> { openedPair = pair; Log.e("LazyCar", "pair: opened $pair") }
                n > 0 -> main.postDelayed({ find(n - 1) }, 300)
                else -> { Log.e("LazyCar", "pair: $pair not on the home screen")
                    Toast.makeText(this, "Put the ${label(pair)} pair on the first home screen page so GO can open it", Toast.LENGTH_LONG).show() }
            }
        }
        main.postDelayed({ find(10) }, 600)
    }

    // What the phone's GO did here, so its Shut down undoes exactly that (see [close]).
    // ponytail: in memory, a helper restart between GO and Shut down forgets it (nothing is undone then).
    private var opened = false; private var openedPair = ""; private var wokeScreen = false
    /** Phone Shut down: back to square one, only undoing what its GO did: the car stereo, CarApp's screens, the pair
     *  (swiped away in Recents, ZUI has no API to close another app), Wi-Fi and Bluetooth, screen off if it was. */
    fun close() {
        val p = Prefs(this)
        if (!opened) { p.clearSnap(); return }; opened = false
        val pair = openedPair; openedPair = ""
        val lock = wokeScreen; val wifiOff = !p.snapWifiWasOn; val btOff = !p.snapBtWasOn
        Log.e("LazyCar", "close: CarApp${if (pair.isNotEmpty()) " + $pair" else ""}, stereo off: ${!p.snapConn1}, wifi off: $wifiOff, bt off: $btOff, screen off: $wokeScreen")
        if (p.mac1.isNotEmpty() && !p.snapConn1) GoAction.disconnectA2dp(this, p.mac1)
        p.clearSnap()
        fun end() {
            fun done() { getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
                performGlobalAction(GLOBAL_ACTION_HOME); if (lock) main.postDelayed({ performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }, 800) }
            // Bluetooth off is a dialog Android opens only for an app in front: CarApp first, then the dialog.
            fun bt() { if (!btOff) { done(); return }
                startActivity(Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                main.postDelayed({ GoAction.disableBt(this) }, 800); main.postDelayed({ done() }, 4000) }
            performGlobalAction(GLOBAL_ACTION_HOME)
            if (wifiOff) tiles(listOf(GoAction.WIFI to false), done = { bt() }) else bt()
        }
        // The pair is one Recents tile titled "Maps/Spotify": flick it up.
        getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        if (pair.isEmpty()) { main.postDelayed({ end() }, 600); return }
        main.postDelayed({
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            main.postDelayed({
                val title = label(pair)
                val n = windows.mapNotNull { it.root }.firstNotNullOfOrNull { r -> r.findAccessibilityNodeInfosByText(title)
                    .firstOrNull { it.packageName == ZUI_LAUNCHER && it.viewIdResourceName == "$ZUI_LAUNCHER:id/task_title" } }
                if (n == null) Log.e("LazyCar", "close: no $title in Recents") else {
                    // The title is the tile's top strip: flick from the snapshot below it, a long way up.
                    val r = Rect().also { n.getBoundsInScreen(it) }; val y = r.exactCenterY() + 200
                    dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(r.exactCenterX(), y); lineTo(r.exactCenterX(), 0f) }, 0, 200)).build(), null, null)
                    Log.e("LazyCar", "close: swiped $title at $r")
                }
                main.postDelayed({ end() }, 900)
            }, 1200)
        }, 600)
    }
}
