package com.lazyneil.lazycar

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole "get in car" sequence, and its undo (STOP). Runs from a foreground GoService so the
 * postDelayed chain and the BT-enable receiver survive independent of any activity. Every step is
 * idempotent: anything already satisfied (BT on, speaker connected, music playing) is skipped.
 * All BT work is best-effort reflection on the profile proxy; failures Toast, never crash.
 */
object GoAction {
    const val IDLE = 0; const val STARTING = 1; const val ON = 2; const val STOPPING = 3
    const val ACTION_STATE = "com.lazyneil.lazycar.STATE"   // sticky-less local ping so an open MainActivity re-renders
    private const val STUCK_MS = 30000L
    val main = Handler(Looper.getMainLooper())

    private fun log(m: String) = android.util.Log.e("LazyCar", m)
    private fun step(name: String, since: Long) = log("step $name took ${SystemClock.uptimeMillis() - since}ms")

    /** Set while a Start/Stop sequence runs in this process; a fresh process (killed mid-sequence) has none. */
    @Volatile private var busy = false
    /** Ms (uptime) the running sequence needs; GoService stays alive until then. */
    @Volatile var endAt = 0L

    /** Current state, with recovery: STARTING/STOPPING with no sequence running, or older than 30s, is IDLE. */
    fun effectiveState(p: Prefs): Int {
        val s = p.state
        if ((s == STARTING || s == STOPPING) && (!busy || System.currentTimeMillis() - p.stateTs > STUCK_MS)) return IDLE
        return s
    }
    private fun setState(ctx: Context, p: Prefs, st: Int) {
        busy = st == STARTING || st == STOPPING
        p.state = st; GoWidget.refresh(ctx)
        ctx.sendBroadcast(Intent(ACTION_STATE).setPackage(ctx.packageName))
        log("state -> $st")
        // Busy states auto-expire to IDLE after 30s (effectiveState); re-render the widget then too,
        // so a killed/late sequence never leaves the spinner stuck. ponytail: handler only - fires if
        // the process is still alive at +30s (it is, during any real sequence); no AlarmManager.
        if (st == STARTING || st == STOPPING) main.postDelayed({
            GoWidget.refresh(ctx); ctx.sendBroadcast(Intent(ACTION_STATE).setPackage(ctx.packageName))
        }, STUCK_MS + 500L)
    }

    fun sequenceDuration(p: Prefs): Long {
        return 6500L
    }

    /** Entry for GO. Returns ms the caller (service) should stay alive for the delayed steps. */
    fun run(ctx: Context): Long {
        val p = Prefs(ctx)
        // Snapshot the pre-GO music level NOW, before BT-enable / A2DP connect makes OnePlus reset it.
        p.snapMusicVol = try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { -1 }
        setState(ctx, p, STARTING)
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        val state = try { adapter?.state ?: BluetoothAdapter.STATE_OFF } catch (e: Exception) { BluetoothAdapter.STATE_OFF }
        p.snapBtWasOn = adapter != null && state == BluetoothAdapter.STATE_ON
        log("snapshot: btWasOn=${p.snapBtWasOn}")
        if (adapter != null && state != BluetoothAdapter.STATE_ON) {
            enableBtThenRun(ctx, adapter, p, prompt = state != BluetoothAdapter.STATE_TURNING_ON)
            return 12000L + 1500L + sequenceDuration(p)
        }
        log("skipped BT enable (already on)")
        runSequence(ctx, p)
        return sequenceDuration(p)
    }

    /**
     * Idempotent: connect only what's disconnected, launch/play only if music isn't already active,
     * Transitions to ON right
     * after the last scheduled step, so a fully-satisfied GO (nothing to do) finishes in ~0.3s.
     */
    fun runSequence(ctx: Context, p: Prefs) {
        val t0 = SystemClock.uptimeMillis()
        a2dpConnectedDevices(ctx) { connected ->
            step("probe A2DP connections", t0)
            val need1 = p.mac1.isNotEmpty() && p.mac1 !in connected
            val need3 = p.headunit && p.mac3.isNotEmpty() && p.mac3 !in connected
            if (need1) connectA2dp(ctx, p.mac1) else if (p.mac1.isNotEmpty()) log("skipped connect ${p.mac1} (connected)")
            if (need3) main.postDelayed({ connectA2dp(ctx, p.mac3) }, 800)

            val settle = if (need1) 2000L else 0L
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Not am.isMusicActive: our own StreamSender monitor track counts there.
            val players = com.lazyneil.lazycar.audio.TabletSpeaker.players(ctx)
            val musicActive = players.isNotEmpty()
            // Snapshot pre-GO state (connected = probe result before we connect anything) so STOP can
            // restore exactly what it found: only undo what GO turned on.
            p.snapConn1 = p.mac1.isNotEmpty() && p.mac1 in connected
            p.snapConn3 = p.mac3.isNotEmpty() && p.mac3 in connected
            p.snapMusicWasActive = musicActive
            log("snapshot: music=$musicActive ($players) conn1=${p.snapConn1} conn3=${p.snapConn3}")
            var doneAt = 0L
            if (need3) doneAt = 800L

            // Order: BT -> connects -> mobile data + hotspot -> player -> play. Both network
            // tiles are toggled in one Quick Settings session before the player/switcher.
            var netAt = settle
            if (p.mobileData || p.hotspot) {
                main.postDelayed({ setNetwork(if (p.mobileData) true else null, if (p.hotspot) true else null, record = true) }, netAt)
                doneAt = maxOf(doneAt, netAt + 6000L); netAt += 6500L
            }

            if (musicActive) log("skipped player launch (music active)")
            else { main.postDelayed({ launchPlayer(ctx, p.playerPkg) }, netAt); doneAt = maxOf(doneAt, netAt) }

                        // Start volume: OnePlus can reset STREAM_MUSIC (often to max) as the A2DP sink connects,
            // so a single set won't hold. Run a watchdog from just
            // after connect through play that keeps re-asserting the target (setStreamVolume, then
            // adjust nudges if the absolute-volume path ignores it), reacting to VOLUME_CHANGED too.
            // Runs in the background; it must NOT gate the ON transition (a warm all-on GO stays <2s).
            // GoService stays alive until endAt, which covers this window.
            if (p.volOn) main.postDelayed({ enforceStartVolume(ctx, p.vol, 12000L) }, maxOf(settle, 300L))

            val playAt = netAt + (if (musicActive) 0L else 1000L)
            if (musicActive) log("skipped MEDIA_PLAY (music active)")
            else { main.postDelayed({ playMedia(ctx, p.playerPkg) }, playAt + 1500); doneAt = maxOf(doneAt, playAt + 1500) }

            if (p.headunit) { main.postDelayed({ launchHeadunit(ctx, p.headunitIp) }, playAt + 2000); doneAt = maxOf(doneAt, playAt + 2000) }
            endAt = SystemClock.uptimeMillis() + maxOf(doneAt + 300L, if (p.volOn) maxOf(settle, 300L) + 12000L else 0L)
            main.postDelayed({ if (p.state == STARTING) { setState(ctx, p, ON); step("GO sequence", t0); com.lazyneil.lazycar.audio.TabletSpeaker.onGo(ctx); TapService.launchApps(ctx) } }, doneAt + 300L)
        }
    }

    /**
     * Android 16 forbids a silent adapter.enable(); we try it, then pop the system REQUEST_ENABLE
     * dialog and let TapService click its confirm button. On STATE_ON we settle 1.5s then run the
     * rest. Gives up after 12s. ponytail: relies on the accessibility service to auto-confirm.
     */
    private fun enableBtThenRun(ctx: Context, adapter: BluetoothAdapter, p: Prefs, prompt: Boolean) {
        val started = AtomicBoolean(false)
        fun go(recv: BroadcastReceiver?) {
            if (!started.compareAndSet(false, true)) return
            recv?.let { try { ctx.unregisterReceiver(it) } catch (e: Exception) {} }
            TapService.disarmButtons()
            log("BT on -> running sequence")
            main.postDelayed({ runSequence(ctx, p) }, 1500)
        }
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) go(this)
            }
        }
        ctx.registerReceiver(recv, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        if (prompt) {
            log("BT off: requesting enable")
            try { @Suppress("DEPRECATION") adapter.enable() } catch (e: Exception) { log("adapter.enable() ${e.message}") }
            TapService.armButtons(listOf("Allow", "Turn on", "Yes", "OK", "Enable"))
            try {
                ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { log("REQUEST_ENABLE ${e.message}") }
        } else log("BT turning on; waiting for STATE_ON")
        if (adapter.isEnabled) go(recv)
        main.postDelayed({
            if (!started.get()) {
                try { ctx.unregisterReceiver(recv) } catch (e: Exception) {}
                TapService.disarmButtons(); setState(ctx, p, IDLE)
                log("BT enable timeout")
            }
        }, 12000)
    }

    /** Live-apply the start volume to the speaker (slider released while ON, or volumeOnly). */
    fun applyVolumesNow(ctx: Context) {
        val p = Prefs(ctx)
        log("volumeOnly: enforcing start volume ${p.vol}%")
        enforceStartVolume(ctx, p.vol, 3000L)
    }

    @Volatile private var volReceiver: BroadcastReceiver? = null

    private var volTick: Runnable? = null
    /**
     * Drive STREAM_MUSIC to [pct] and HOLD it against OnePlus' group/A2DP absolute-volume resets for
     * [budgetMs]: set it now, then re-check every 1s (max 5 corrections) and re-assert if it drifted
     * by more than one index. A VOLUME_CHANGED receiver reacts to resets instantly during the window.
     * Each correction logs before/after. STREAM_MUSIC is the shared level in OnePlus Audio sharing.
     */
    private fun enforceStartVolume(ctx: Context, pct: Int, budgetMs: Long) {
        if (com.lazyneil.lazycar.audio.StreamSender.shared) { com.lazyneil.lazycar.media.Volumes.setJblLocal(ctx, pct); return }   // music is held at 0
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = VolumeMath.toStreamIndex(pct, max)
        // A2DP absolute volume quantizes coarsely (steps of ~max/16 on this ROM), so exact-match is
        // impossible. Accept within one step; that still prevents the group's reset-to-max "blast".
        val tol = maxOf(2, max / 16)
        val startAt = SystemClock.uptimeMillis()
        val total = java.util.concurrent.atomic.AtomicInteger(0)   // safety cap against pathological loops
        fun cur() = try { am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { target }
        fun correct(reason: String) {
            val before = cur()
            if (kotlin.math.abs(before - target) <= tol) return   // already close enough; no fight, no storm
            if (total.incrementAndGet() > 40) return
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            var mid = cur()
            if (kotlin.math.abs(mid - target) > tol) {   // exact set ignored: nudge toward target
                var guard = 0
                while (kotlin.math.abs(mid - target) > tol && guard < 30) {
                    val dir = if (mid < target) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                    val next = cur(); if (next == mid) break; mid = next; guard++
                }
            }
            log("volume correct ($reason): $before -> ${cur()} target=$target±$tol/$max ($pct%)")
        }
        unregisterVol(ctx)
        volReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { correct("ext-change") }
        }
        try { androidx.core.content.ContextCompat.registerReceiver(ctx.applicationContext, volReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED) } catch (e: Exception) {}
        log("volume: enforce target=$target±$tol/$max ($pct%), start=${cur()}")
        correct("initial")
        val tick = object : Runnable {
            override fun run() {
                correct("watchdog")
                if (SystemClock.uptimeMillis() - startAt < budgetMs) main.postDelayed(this, 800)
                else { log("volume watchdog done: got=${cur()} target=$target±$tol (corrections=${total.get()})"); unregisterVol(ctx) }
            }
        }
        volTick = tick; main.postDelayed(tick, 800)
    }

    private fun unregisterVol(ctx: Context) {
        volReceiver?.let { try { ctx.applicationContext.unregisterReceiver(it) } catch (e: Exception) {} }
        volTick?.let { main.removeCallbacks(it) }; volTick = null
        volReceiver = null
    }

    /**
     * Drive mobile data and/or the Wi-Fi hotspot to [wantOn] via their Quick Settings tiles (there's
     * no public setter for either without a privileged permission). [record] snapshots each tile's
     * pre-state so STOP can restore it. GO passes both wanted-on; STOP passes only what it must undo.
     */
    fun setNetwork(data: Boolean?, hotspot: Boolean?, record: Boolean) {
        val t = mutableListOf<TapService.Tile>()
        // The mobile-data tile desc is "SIM1, Mobile data off" when off but only "SIM1, " when on,
        // so match on "SIM1" (present in both) with "Mobile data" as a fallback for other ROMs.
        if (data != null) t += TapService.Tile(listOf("SIM1", "Mobile data"), data, if (record) "snapData" else "", "mobile data")
        if (hotspot != null) t += TapService.Tile(listOf("hotspot"), hotspot, if (record) "snapHotspot" else "", "hotspot")
        if (t.isNotEmpty()) { log("network: ${t.joinToString { "${it.feature}->${it.wantOn}" }}"); TapService.armTiles(t) }
    }

    // ---- Bluetooth profile-proxy helper (dedup for connect / disconnect / getConnected) ----

    private fun adapterOf(ctx: Context) =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()

    private fun withProxy(ctx: Context, adapter: BluetoothAdapter, profile: Int, action: (BluetoothProfile) -> Unit) {
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(pr: Int, proxy: BluetoothProfile) {
                try { action(proxy) } finally { adapter.closeProfileProxy(pr, proxy) }
            }
            override fun onServiceDisconnected(pr: Int) {}
        }, profile)
    }

    /** Reflected A2DP/HEADSET connect(device); Toasts once if both profiles fail. */
    fun connectA2dp(ctx: Context, mac: String) {
        val adapter = adapterOf(ctx) ?: run { toast(ctx, "No Bluetooth adapter"); return }
        val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) { toast(ctx, "Bad MAC $mac"); return }
        val name = try { device.name ?: mac } catch (e: SecurityException) { mac }
        profileAction(ctx, adapter, device, "connect", name, mac)
    }

    /** Reflected A2DP/HEADSET disconnect(device); best-effort, never toasts. */
    fun disconnectA2dp(ctx: Context, mac: String) {
        val adapter = adapterOf(ctx) ?: return
        val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) { return }
        profileAction(ctx, adapter, device, "disconnect", null, mac)
    }

    private fun profileAction(ctx: Context, adapter: BluetoothAdapter, device: BluetoothDevice,
                              method: String, toastName: String?, mac: String) {
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        val pending = java.util.concurrent.atomic.AtomicInteger(profiles.size)
        val anyOk = AtomicBoolean(false)
        for (profile in profiles) withProxy(ctx, adapter, profile) { proxy ->
            try {
                val m = proxy.javaClass.getMethod(method, BluetoothDevice::class.java)
                m.isAccessible = true
                val r = m.invoke(proxy, device)
                if (r == null || r == true) anyOk.set(true)
            } catch (e: Exception) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                android.util.Log.w("LazyCar", "$method $mac", cause)
            } finally {
                if (pending.decrementAndGet() == 0 && !anyOk.get() && toastName != null)
                    toast(ctx, "Connect failed ($toastName)")
            }
        }
    }

    /** Async list of currently A2DP-connected device MACs. */
    private fun a2dpConnectedDevices(ctx: Context, done: (List<String>) -> Unit) {
        val adapter = adapterOf(ctx)
        if (adapter == null || !adapter.isEnabled) { done(emptyList()); return }
        try {
            withProxy(ctx, adapter, BluetoothProfile.A2DP) { proxy ->
                val list = try { proxy.connectedDevices.map { it.address } } catch (e: Exception) { emptyList() }
                main.post { done(list) }
            }
        } catch (e: Exception) { done(emptyList()) }
    }

    /**
     * Undo GO by restoring the pre-GO snapshot: only turn off / disconnect / pause what GO itself
     * turned on. Anything that was already on before GO is left exactly as it was. Returns ms alive.
     */
    fun stop(ctx: Context): Long {
        val p = Prefs(ctx)
        setState(ctx, p, STOPPING)
        com.lazyneil.lazycar.audio.TabletSpeaker.onStop(ctx)

        if (!p.snapMusicWasActive) { log("restore: music playing -> paused"); pauseMedia(ctx) }
        else log("restore: music was already playing -> left")

        // Restore the pre-GO STREAM_MUSIC level if GO changed it; the start volume watchdog must not undo it.
        unregisterVol(ctx)
        if (p.volOn && p.snapMusicVol >= 0) try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_MUSIC, p.snapMusicVol, 0)
            log("restore: music volume -> index ${p.snapMusicVol}")
        } catch (e: Exception) {}

        for ((mac, wasConnected) in listOf(p.mac1 to p.snapConn1, p.mac3 to p.snapConn3))
            if (mac.isNotEmpty() && !wasConnected) { log("restore: $mac connected -> disconnected"); disconnectA2dp(ctx, mac) }
            else if (mac.isNotEmpty()) log("restore: $mac was already connected -> left")

        // Network tiles (one QS session) then BT-off (its own dialog) open system UI: serialize them.
        val offData = if (p.mobileData && !p.snapDataWasOn) false else null
        val offHotspot = if (p.hotspot && !p.snapHotspotWasOn) false else null
        if (p.hotspot) log(if (offHotspot != null) "restore: hotspot on -> off" else "restore: hotspot was already on -> left")
        if (p.mobileData) log(if (offData != null) "restore: data on -> off" else "restore: data was already on -> left")
        var at = 0L
        if (offData != null || offHotspot != null) { main.postDelayed({ setNetwork(offData, offHotspot, record = false) }, at); at += 6500L }
        if (!p.snapBtWasOn) { log("restore: bt on -> off"); main.postDelayed({ disableBt(ctx) }, at); at += 12500L }
        else log("restore: bt was already on -> left")

        // Only kill the player if GO launched it (music wasn't already playing before GO).
        if (!p.snapMusicWasActive) try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .killBackgroundProcesses(p.playerPkg) } catch (e: Exception) {}
        main.postDelayed({ setState(ctx, p, IDLE) }, maxOf(1800L, at))
        return maxOf(2500L, at + 500L)
    }

    /**
     * Turn BT off the same way we turn it on: try the silent disable, then pop the system
     * REQUEST_DISABLE dialog and let TapService confirm it (com.oplus.wirelesssettings). Waits for
     * STATE_OFF; on timeout it only logs. No toasts - either it happens or it's in the log.
     */
    private fun disableBt(ctx: Context) {
        val adapter = adapterOf(ctx) ?: return
        val done = AtomicBoolean(false)
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF && done.compareAndSet(false, true)) {
                    try { ctx.unregisterReceiver(this) } catch (e: Exception) {}
                    TapService.disarmButtons(); log("BT off")
                }
            }
        }
        ctx.registerReceiver(recv, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        try { @Suppress("DEPRECATION") adapter.disable() } catch (e: Exception) { log("adapter.disable() ${e.message}") }
        TapService.armButtons(listOf("Allow", "Turn off", "Turn Off", "OK", "Disable"))
        // ACTION_REQUEST_DISABLE is not a public SDK constant; its action string is stable.
        try { ctx.startActivity(Intent("android.bluetooth.adapter.action.REQUEST_DISABLE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { log("REQUEST_DISABLE ${e.message}") }
        if (!adapter.isEnabled && done.compareAndSet(false, true)) {
            try { ctx.unregisterReceiver(recv) } catch (e: Exception) {}; TapService.disarmButtons()
        }
        main.postDelayed({
            if (!done.get()) { try { ctx.unregisterReceiver(recv) } catch (e: Exception) {}; TapService.disarmButtons(); log("BT disable timeout") }
        }, 12000)
    }

    /** Real-state probe on app open: clears a stuck busy state, and ON drops to IDLE once the speaker is gone.
     *  Never promotes IDLE to ON: the speaker being connected does not mean Start ran. */
    fun deriveState(ctx: Context, p: Prefs, done: (Int) -> Unit) {
        val es = effectiveState(p)
        if (es != p.state) setState(ctx, p, es)
        if (es != ON || p.mac1.isEmpty()) { done(es); return }
        a2dpConnectedDevices(ctx) { connected ->
            if (p.mac1 !in connected && p.state == ON) setState(ctx, p, IDLE)
            done(p.state)
        }
    }

    private fun pauseMedia(ctx: Context) = mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
    /** Pause whatever holds the media keys (e.g. a video app), then PLAY straight to the chosen player's receiver. */
    private fun playMedia(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) { mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY); return }
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
        Handler(Looper.getMainLooper()).postDelayed({
            val t = SystemClock.uptimeMillis()
            for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(t, t, a, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
            log("sent MEDIA_PLAY to $pkg")
        }, 400)
    }
    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun launchPlayer(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) { toast(ctx, "No player chosen"); return }
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast(ctx, "Player not found: $pkg"); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i)
    }

    // phone-side Android Auto path, first installed wins
    private const val PKG_WIRELESS = "com.andrerinas.wirelesshelper"
    private const val PKG_OPENHU = "com.andrerinas.headunitrevived"
    private const val PKG_GEARHEAD = "com.google.android.projection.gearhead"

    /** Returns (package, label) of the first installed AA path, or null. */
    fun detectHeadunit(ctx: Context): Pair<String, String>? {
        val order = listOf(
            PKG_WIRELESS to "Open Headunit Wireless Helper",
            PKG_OPENHU to "Open Headunit",
            PKG_GEARHEAD to "Android Auto"
        )
        return order.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it.first) != null }
    }

    private fun launchHeadunit(ctx: Context, ip: String) {
        val target = detectHeadunit(ctx) ?: run { toast(ctx, "No head unit app installed on phone"); return }
        val pkg = target.first
        try {
            val i = if (pkg == PKG_OPENHU && ip.isNotEmpty())
                Intent(Intent.ACTION_VIEW, Uri.parse("headunit://connect?ip=$ip")).setPackage(pkg)
            else ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (i == null) { toast(ctx, "Head unit app not launchable"); return }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i)
            toast(ctx, "Started ${target.second}")
        } catch (e: android.content.ActivityNotFoundException) { toast(ctx, "Head unit app not found") }
    }

    private fun toast(ctx: Context, msg: String) =
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}
