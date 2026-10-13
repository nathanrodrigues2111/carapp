package com.lazyneil.lazycar

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Collections

/**
 * Zero-tap dual audio. Two jobs:
 *  - buttons: click a system confirm button (the BT "Allow"/"Turn on" enable dialog). Event-driven.
 *  - adds: in the OnePlus output switcher, tick every "Add device to group." checkbox so all
 *    connected speakers join the sharing group, then dismiss. POLLING, not event-driven: the OnePlus
 *    dialog throttles/mislabels its accessibility events, so instead of waiting for the right event
 *    we scan getWindows() every 400ms while armed and click what we find. No privileged permission.
 */
class TapService : AccessibilityService() {
    class Tile(val labels: List<String>, val wantOn: Boolean, val snapKey: String, val feature: String)

    companion object {
        @Volatile private var instance: TapService? = null

        // ---- add-to-group mode (output switcher) ----
        @Volatile var armedUntil: Long = 0L
        @Volatile var ownsPanel: Boolean = false     // true only when LazyCar opened the panel
        @Volatile var finishing: Boolean = false
        private val clicked = Collections.synchronizedSet(mutableSetOf<String>())
        fun arm(windowMs: Long = 8500L) {
            armedUntil = SystemClock.uptimeMillis() + windowMs
            ownsPanel = true; finishing = false; clicked.clear()
            instance?.startAddsPoll()
        }
        fun disarm() { armedUntil = 0L; ownsPanel = false }

        // ---- button mode (BT enable dialog) ----
        @Volatile var buttonTexts: List<String> = emptyList()
        @Volatile var buttonArmedUntil: Long = 0L
        @Volatile private var buttonDumped = false
        fun armButtons(texts: List<String>, windowMs: Long = 12000L) {
            buttonTexts = texts; buttonArmedUntil = SystemClock.uptimeMillis() + windowMs; buttonDumped = false
        }
        fun disarmButtons() { buttonTexts = emptyList(); buttonArmedUntil = 0L }

        // ---- quick-settings tile mode (mobile data / hotspot), all in one QS session ----
        @Volatile var tiles: List<Tile> = emptyList()
        @Volatile var tileArmedUntil: Long = 0L
        /** Open Quick Settings once and drive every [tiles] entry to its wanted state, recording each
         *  tile's pre-state into Prefs[snapKey] (blank key = don't record). */
        fun armTiles(t: List<Tile>, windowMs: Long = 9000L) {
            tiles = t; tileArmedUntil = SystemClock.uptimeMillis() + windowMs
            instance?.startTilePoll()
        }
        fun disarmTiles() { tileArmedUntil = 0L; tiles = emptyList() }

        /** Widget tap: open the chosen app, or two apps in split screen. */
        fun launchApps(ctx: Context) {
            val p = Prefs(ctx)
            val mode = p.launchMode
            if (mode == "none" || p.launchA.isEmpty()) return
            val svc = instance
            if (mode == "one" || p.launchB.isEmpty() || svc == null) startPkg(ctx, p.launchA, 0)   // no accessibility: no split
            else svc.h.post { svc.launchPair(p.launchA, p.launchB, p.splitRatio) }
        }

        private fun startPkg(ctx: Context, pkg: String, flags: Int) {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or flags)) }
            catch (e: Exception) { android.util.Log.e("LazyCar", "launch $pkg: $e") }
        }

        /** Tablet: get onto the phone hotspot. Safe to call often, it restarts the retry window. */
        fun joinHotspot() { instance?.let { it.h.post { it.startJoin() } } }
    }

    // ---- split screen launch ----
    // Best: tap the launcher's own app pair icon ("App pair: Maps and Spotify" on ZUI / Launcher3),
    // which gives exactly the user's saved split (sides and ratio). Apps cannot start a split
    // pair themselves, the shell API behind it is launcher only. Without a matching pair icon,
    // try LAUNCH_ADJACENT, then the split screen global action.
    private fun launchPair(a: String, b: String, ratio: Int) {
        val pm = packageManager
        fun label(pkg: String) = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        val descs = setOf("App pair: ${label(a)} and ${label(b)}", "App pair: ${label(b)} and ${label(a)}")
        performGlobalAction(GLOBAL_ACTION_HOME)
        var tries = 0
        h.postDelayed(object : Runnable {
            override fun run() {
                val icon = (windows ?: emptyList()).mapNotNull { it.root }.firstNotNullOfOrNull { r ->
                    find(r) { it.contentDescription?.toString() in descs }
                }
                if (icon != null) {
                    val ok = (if (icon.isClickable) icon else clickableAncestor(icon) ?: icon).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.e("LazyCar", "split: tapped launcher pair '${icon.contentDescription}' ok=$ok")
                    h.postDelayed({ arrangeSplit(a, ratio, swapped = false, tries = 0) }, 1500); return
                }
                if (++tries < 10) { h.postDelayed(this, 300); return }
                android.util.Log.e("LazyCar", "split: no launcher pair icon, launching side by side")
                launchAdjacent(a, b, ratio)
            }
        }, 700)
    }

    private fun launchAdjacent(a: String, b: String, ratio: Int) {
        startPkg(this, a, 0)
        val adjacent = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        h.postDelayed({ startPkg(this, b, adjacent) }, 1500)
        h.postDelayed({
            if (inSplit()) { android.util.Log.e("LazyCar", "split: adjacent launch worked"); return@postDelayed }
            android.util.Log.e("LazyCar", "split: adjacent ignored, using split screen action")
            startPkg(this, a, 0)
            h.postDelayed({ performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }, 1200)
            h.postDelayed({ startPkg(this, b, adjacent) }, 2600)
            h.postDelayed({ arrangeSplit(a, ratio, swapped = false, tries = 0) }, 4500)
        }, 3500)
    }

    /** The launcher pair keeps its own sides and ratio; fix both with divider gestures:
     *  double-tap swaps the apps, a drag moves the divider (it snaps to 50/50, 2:1 or 1:2). */
    private fun arrangeSplit(left: String, ratio: Int, swapped: Boolean, tries: Int) {
        val apps = (windows ?: emptyList())
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> w.root?.packageName?.toString()?.let { it to android.graphics.Rect().also { r -> w.getBoundsInScreen(r) } } }
            .filter { it.first != "com.android.systemui" && it.second.height() > 300 }   // skip status bar chips
            .distinctBy { it.first }
        if (apps.size < 2) {
            if (tries < 6) h.postDelayed({ arrangeSplit(left, ratio, swapped, tries + 1) }, 500)
            else android.util.Log.e("LazyCar", "split: not side by side")
            return
        }
        val l = apps.minBy { it.second.left }; val r = apps.maxBy { it.second.left }
        val x = (l.second.right + r.second.left) / 2f; val y = l.second.exactCenterY()
        android.util.Log.e("LazyCar", "split: windows ${apps.map { "${it.first} ${it.second.toShortString()}" }} divider x=$x y=$y")
        if (l.first != left && !swapped) {
            android.util.Log.e("LazyCar", "split: swapping sides")
            gesture(listOf(tap(x, y, 0), tap(x, y, 140)))
            h.postDelayed({ arrangeSplit(left, ratio, swapped = true, tries = 0) }, 1500); return
        }
        val target = r.second.right * ratio / 100f
        if (kotlin.math.abs(x - target) > r.second.right * 0.05f) {
            android.util.Log.e("LazyCar", "split: moving divider to $ratio%")
            val path = android.graphics.Path().apply { moveTo(x, y); lineTo(target, y) }
            gesture(listOf(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 700)))
        }
        h.postDelayed({ android.util.Log.e("LazyCar", "split: done, left=${l.first}") }, 1200)
    }

    private fun tap(x: Float, y: Float, at: Long) = android.accessibilityservice.GestureDescription.StrokeDescription(
        android.graphics.Path().apply { moveTo(x, y) }, at, 50)

    private fun gesture(strokes: List<android.accessibilityservice.GestureDescription.StrokeDescription>) {
        val b = android.accessibilityservice.GestureDescription.Builder()
        strokes.forEach { b.addStroke(it) }
        dispatchGesture(b.build(), null, null)
    }

    private fun inSplit() = (windows ?: emptyList())
        .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        .mapNotNull { it.root?.packageName }.distinct().size >= 2

    // ---- tablet: join the phone hotspot ----
    // Android 16 blocks setWifiEnabled for normal apps, so this drives the Wi-Fi panel instead:
    // tap the toggle if Wi-Fi is off, then tap the hotspot row. Checked every 15 s for 3 min,
    // the panel only opens when Wi-Fi is off or the hotspot is visible but not joined.
    @Volatile private var wifiSsid = ""
    private var joinCb: ConnectivityManager.NetworkCallback? = null
    private var joinTries = 0
    private var panelUntil = 0L
    private var panelTapped = false
    private fun target() = Prefs(this).hotspotSsid.trim()

    private fun startJoin() {
        if (!Role.isTablet(this) || target().isEmpty()) return
        if (joinCb == null) try {
            joinCb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                    wifiSsid = (c.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"") ?: ""
                }
                override fun onLost(n: Network) { wifiSsid = "" }
            }.also {
                getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), it)
            }
        } catch (e: Exception) { android.util.Log.e("LazyCar", "wifi callback: $e") }
        joinTries = 0
        h.removeCallbacks(joinTick)
        h.postDelayed(joinTick, 1500)   // let the callback report the current network first
    }

    private val joinTick = object : Runnable {
        override fun run() {
            val wm = getSystemService(WifiManager::class.java)
            val t = target()
            if (wm.isWifiEnabled && wifiSsid == t) { android.util.Log.e("LazyCar", "join: on hotspot"); return }
            if (joinTries++ >= 12) { android.util.Log.e("LazyCar", "join: gave up"); return }
            @Suppress("DEPRECATION") val visible = wm.isWifiEnabled && wm.scanResults.any { it.SSID == t }
            android.util.Log.e("LazyCar", "join: try $joinTries wifi=${wm.isWifiEnabled} visible=$visible")
            if (!wm.isWifiEnabled || visible) openWifiPanel()
            h.postDelayed(this, 15000)
        }
    }

    private fun openWifiPanel() {
        if (SystemClock.uptimeMillis() < panelUntil) return
        try {
            startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { android.util.Log.e("LazyCar", "join: panel $e"); return }
        panelUntil = SystemClock.uptimeMillis() + 10000; panelTapped = false
        h.removeCallbacks(panelPoll); h.postDelayed(panelPoll, 600)
    }

    private val panelPoll = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() >= panelUntil) { closePanel(); return }
            val root = windows?.mapNotNull { it.root }?.firstOrNull { it.packageName == "com.android.settings" }
            if (root != null) {
                val sw = find(root) { it.isCheckable && it.className?.contains("Switch") == true }
                if (sw != null && !sw.isChecked) {
                    val ok = (if (sw.isClickable) sw else clickableAncestor(sw) ?: sw).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.e("LazyCar", "join: tapped Wi-Fi toggle ok=$ok")
                } else if (sw != null && wifiSsid == target()) { closePanel(); return }
                else if (sw != null && !panelTapped) {
                    val row = find(root) { it.text?.toString() == target() }
                    if (row != null) {
                        val ok = (clickableAncestor(row) ?: row).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        android.util.Log.e("LazyCar", "join: tapped hotspot row ok=$ok")
                        panelTapped = true
                        panelUntil = SystemClock.uptimeMillis() + 2500   // give the connect a moment, then close
                    }
                }
            }
            h.postDelayed(this, 400)
        }
    }

    private fun closePanel() {
        h.removeCallbacks(panelPoll)
        panelUntil = 0L
        val settingsOnTop = windows?.any { it.isActive && it.root?.packageName == "com.android.settings" } == true
        if (settingsOnTop) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private val h = Handler(Looper.getMainLooper())
    private var pollN = 0
    private var dialogSeen = false
    private var reSent = false
    private var tileOpened = false
    private var tileFinished = false
    private val tileDone = Collections.synchronizedSet(mutableSetOf<String>())

    override fun onServiceConnected() { instance = this; startJoin() }   // the system binds us at boot
    override fun onDestroy() {
        if (instance === this) instance = null
        joinCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        super.onDestroy()
    }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        handleButtons(e)   // adds are handled by the poll loop, not events
    }

    /** Click a system confirm button (BT enable dialog etc.) once, then disarm. */
    private fun handleButtons(e: AccessibilityEvent?) {
        if (buttonTexts.isEmpty()) return
        if (SystemClock.uptimeMillis() >= buttonArmedUntil) { buttonTexts = emptyList(); return }
        val pkg = e?.packageName?.toString() ?: return
        if (!(pkg == "com.android.settings" || pkg == "com.android.systemui" ||
              pkg.startsWith("com.oplus") || pkg == "com.android.permissioncontroller")) return
        val root = rootInActiveWindow ?: return
        if (!buttonDumped) { buttonDumped = true; android.util.Log.e("LazyCar", "BUTTON DIALOG pkg=$pkg"); dump(root, 0) }
        val btn = find(root) { n ->
            n.isClickable && n.text?.toString()?.trim()?.let { t -> buttonTexts.any { t.equals(it, true) || t.contains(it, true) } } == true
        } ?: return
        val ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        android.util.Log.e("LazyCar", "TapService clicked confirm '${btn.text}' ok=$ok")
        disarmButtons()
    }

    // ---- polling add-to-group ----

    fun startAddsPoll() {
        pollN = 0; dialogSeen = false; reSent = false
        h.removeCallbacks(pollRunnable)
        h.post(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (armedUntil == 0L || finishing) return
            if (SystemClock.uptimeMillis() >= armedUntil) { finishPoll(); return }
            pollN++
            var winCount = 0; var adds = 0; var removes = 0; var sawDialog = false
            val names = Prefs(applicationContext).let { p -> listOf(p.name1, p.name2, p.name3) }.map { it.trim() }.filter { it.isNotEmpty() }
            val seen = mutableSetOf<String>()
            for (w in (windows ?: emptyList())) {
                val root = w.root ?: continue
                winCount++
                if (root.packageName != "com.android.systemui") continue
                if (find(root) { it.viewIdResourceName?.let { id -> id.endsWith("/media_output_dialog") || id.endsWith("/list_result") } == true } != null) sawDialog = true
                forEach(root) { n ->
                    // Match by desc first, then by tick state, so an id or wording change in a OnePlus update does not break it.
                    if (n.viewIdResourceName?.endsWith("/check_box_area") != true && !n.isCheckable) return@forEach
                    val title = rowTitle(n) ?: return@forEach
                    if (!seen.add(title)) return@forEach
                    when (groupState(n, title, names)) {
                        1 -> {
                            sawDialog = true; adds++
                            if (title !in clicked) {
                                val target = if (n.isClickable) n else clickableAncestor(n) ?: n
                                val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                clicked.add(title)
                                android.util.Log.e("LazyCar", "TapService poll#$pollN click add '$title' ok=$ok")
                            }
                        }
                        -1 -> { sawDialog = true; removes++ }
                    }
                }
            }
            if (sawDialog) dialogSeen = true
            else if (pollN == 6) (windows ?: emptyList()).forEach { w ->   // diagnostics for the next OnePlus UI change
                android.util.Log.e("LazyCar", "WINDOW type=${w.type} title=${w.title} pkg=${w.root?.packageName}"); dump(w.root, 0) }
            android.util.Log.e("LazyCar", "TapService poll#$pollN windows=$winCount add=$adds remove=$removes clicked=${clicked.size}")

            // Dialog never showed up -> re-open it once (~1.6s in).
            if (!dialogSeen && pollN >= 4 && !reSent) { reSent = true; reopenSwitcher() }

            // Done when everything connected is grouped (no Add rows, >=2 Remove). Window is fallback.
            if (dialogSeen && adds == 0 && removes >= 2) { finishPoll(); return }
            h.postDelayed(this, 400)
        }
    }

    private fun finishPoll() {
        if (finishing) return
        finishing = true
        Prefs(applicationContext).grouped = clicked.isNotEmpty() || dialogSeen
        android.util.Log.e("LazyCar", "TapService poll done (clicked=${clicked.size}, grouped=${Prefs(applicationContext).grouped})")
        val owned = ownsPanel
        disarm()
        h.postDelayed({
            if (owned) performGlobalAction(GLOBAL_ACTION_BACK)
            if (clicked.isNotEmpty()) Toast.makeText(applicationContext, "Dual audio on", Toast.LENGTH_LONG).show()
        }, 800)
    }

    // ---- quick-settings tile toggle (mobile data / hotspot) ----

    fun startTilePoll() {
        tileOpened = false; tileFinished = false; tileDone.clear()
        h.removeCallbacks(tileRunnable); h.post(tileRunnable)
    }

    private val tileRunnable = object : Runnable {
        override fun run() {
            if (tileArmedUntil == 0L || tileFinished) return
            if (SystemClock.uptimeMillis() >= tileArmedUntil) {
                (tiles.map { it.feature } - tileDone).forEach { android.util.Log.e("LazyCar", "tile '$it' not found (timeout)") }
                finishTile(); return
            }
            if (!tileOpened) { tileOpened = true; performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }
            val roots = (windows ?: emptyList()).mapNotNull { it.root }.filter { it.packageName == "com.android.systemui" }
            for (t in tiles) {
                if (t.feature in tileDone) continue
                var node: AccessibilityNodeInfo? = null
                for (root in roots) { node = find(root) { n ->
                    n.isCheckable && n.contentDescription?.toString()?.let { d -> t.labels.any { d.contains(it, true) } } == true
                }; if (node != null) break }
                val n = node ?: continue
                val on = n.isChecked
                when (t.snapKey) {                        // record pre-toggle state for STOP to restore
                    "snapData" -> Prefs(applicationContext).snapDataWasOn = on
                    "snapHotspot" -> Prefs(applicationContext).snapHotspotWasOn = on
                }
                if (on != t.wantOn) {
                    val target = if (n.isClickable) n else clickableAncestor(n) ?: n
                    val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.e("LazyCar", "tile '${t.feature}' was ${onoff(on)} -> ${onoff(t.wantOn)} ok=$ok")
                } else android.util.Log.e("LazyCar", "tile '${t.feature}' already ${onoff(on)} -> left")
                tileDone.add(t.feature)
            }
            if (tiles.all { it.feature in tileDone }) { finishTile(); return }
            h.postDelayed(this, 400)
        }
    }

    private fun finishTile() {
        if (tileFinished) return
        tileFinished = true; disarmTiles()
        h.postDelayed({ performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }, 500)
    }

    private fun onoff(b: Boolean) = if (b) "on" else "off"

    private fun reopenSwitcher() {
        val pkg = Prefs(applicationContext).playerPkg
        android.util.Log.e("LazyCar", "TapService re-sending output dialog for $pkg")
        try {
            sendBroadcast(Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui").putExtra("package_name", pkg))
        } catch (e: Exception) { android.util.Log.e("LazyCar", "re-send failed ${e.message}") }
    }

    /** 1 = can be added to the group, -1 = already grouped, 0 = neither (phone speaker, the active device alone). */
    private fun groupState(n: AccessibilityNodeInfo, title: String, names: List<String>): Int {
        val d = n.contentDescription?.toString() ?: ""
        if (d.contains("remove", true) && d.contains("group", true)) return -1
        if (d.contains("add", true) && d.contains("group", true)) return 1
        val box = if (n.isCheckable) n else find(n) { it.isCheckable || it.viewIdResourceName?.endsWith("/check_box") == true }
        if (box?.isChecked == true) return -1
        val row = rowOf(n)
        val inUse = row != null && find(row) { it.viewIdResourceName?.endsWith("/subtitle") == true &&
            it.text?.toString()?.contains("in use", true) == true } != null
        val known = names.any { title.contains(it, true) || it.contains(title, true) }
        // Post-update OnePlus UI: no add/remove desc, box not checkable; an "In use" row is already playing.
        return if (inUse) -1 else if (known) 1 else 0
    }

    private fun rowOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var row: AccessibilityNodeInfo? = n
        while (row != null && row.viewIdResourceName?.endsWith("/main_content") != true) row = row.parent
        return row
    }

    /** Title text of the device row (id .../main_content) that contains node [n]. */
    private fun rowTitle(n: AccessibilityNodeInfo): String? {
        val r = rowOf(n) ?: return null
        return find(r) { it.viewIdResourceName?.endsWith("/title") == true }?.text?.toString()
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = n.parent
        while (p != null) { if (p.isClickable) return p; p = p.parent }
        return null
    }

    private fun forEach(n: AccessibilityNodeInfo?, action: (AccessibilityNodeInfo) -> Unit) {
        if (n == null) return
        action(n)
        for (i in 0 until n.childCount) forEach(n.getChild(i), action)
    }

    private fun find(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) find(n.getChild(i), pred)?.let { return it }
        return null
    }

    private fun dump(n: AccessibilityNodeInfo?, d: Int) {
        if (n == null) return
        android.util.Log.e("LazyCar", "NODE " + "  ".repeat(d) +
            "[${n.className}] text='${n.text}' desc='${n.contentDescription}' click=${n.isClickable} id=${n.viewIdResourceName}")
        for (i in 0 until n.childCount) dump(n.getChild(i), d + 1)
    }
}
