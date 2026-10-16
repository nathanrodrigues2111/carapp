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
        p.shutDown = false; com.lazyneil.lazycar.media.MediaListener.wake(ctx)
        // Snapshot the pre-GO music level NOW, before BT-enable / A2DP connect makes OnePlus reset it.
        p.snapMusicVol = try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { -1 }
        setState(ctx, p, STARTING)
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        val state = try { adapter?.state ?: BluetoothAdapter.STATE_OFF } catch (e: Exception) { BluetoothAdapter.STATE_OFF }
        p.snapBtWasOn = adapter != null && state == BluetoothAdapter.STATE_ON
        log("snapshot: btWasOn=${p.snapBtWasOn}")
        // Tablet mode: the tablet plays the speaker, the phone needs no Bluetooth.
        if (adapter != null && state != BluetoothAdapter.STATE_ON && !com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(ctx)) {
            enableBtThenRun(ctx, adapter, p)
            return BT_WAIT_MS + 1500L + sequenceDuration(p)
        }
        log("skipped BT enable (on, or Tablet mode)")
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
            // Tablet mode: the JBL belongs to the tablet; the phone only connects it if it falls back.
            val tab = com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(ctx)
            val need1 = !tab && p.mac1.isNotEmpty() && p.mac1 !in connected
            val need3 = p.headunit && p.mac3.isNotEmpty() && p.mac3 !in connected
            if (need1) connectA2dp(ctx, p.mac1) else if (p.mac1.isNotEmpty()) log("skipped connect ${p.mac1} (${if (tab) "Tablet mode" else "connected"})")
            if (need3) main.postDelayed({ connectA2dp(ctx, p.mac3) }, 800)

            val settle = if (need1) 2000L else 0L
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Not am.isMusicActive: our own StreamSender monitor track counts there.
            val players = com.lazyneil.lazycar.audio.TabletSpeaker.players(ctx)
            val musicActive = players.isNotEmpty()
            // Snapshot pre-GO state (connected = probe result before we connect anything) so STOP can
            // restore exactly what it found: only undo what GO turned on.
            p.snapConn1 = p.mac1.isNotEmpty() && p.mac1 in connected   // pre-GO owner: Shut down gives it back
            handoff(ctx, p, tab)
            p.snapConn3 = p.mac3.isNotEmpty() && p.mac3 in connected
            p.snapMusicWasActive = musicActive
            log("snapshot: music=$musicActive ($players) conn1=${p.snapConn1} conn3=${p.snapConn3}")
            var doneAt = 0L
            if (need3) doneAt = 800L

            // Order: BT -> connects -> player -> play. Hotspot and mobile data are the user's (no public API).
            val netAt = settle

            if (tab) { main.postDelayed({ goTablet(ctx, p) }, netAt); doneAt = maxOf(doneAt, netAt + TABLET_ACK_MS + 2500L) }
            // GO never opens a player's screen: playback starts in the background below (session key or CarPlayer).

                        // Start volume: OnePlus can reset STREAM_MUSIC (often to max) as the A2DP sink connects,
            // so a single set won't hold. Run a watchdog from just
            // after connect through play that keeps re-asserting the target (setStreamVolume, then
            // adjust nudges if the absolute-volume path ignores it), reacting to VOLUME_CHANGED too.
            // Runs in the background; it must NOT gate the ON transition (a warm all-on GO stays <2s).
            // GoService stays alive until endAt, which covers this window.
            if (p.volOn && !tab) main.postDelayed({ enforceStartVolume(ctx, p.vol, 12000L) }, maxOf(settle, 300L))

            val playAt = netAt + (if (musicActive) 0L else 1000L)
            if (tab) {}
            else if (musicActive) log("skipped MEDIA_PLAY (music active)")
            else { main.postDelayed({ playMedia(ctx, p.playerPkg) }, playAt + 1500); doneAt = maxOf(doneAt, playAt + 1500) }

            if (p.headunit) { main.postDelayed({ launchHeadunit(ctx, p.headunitIp) }, playAt + 2000); doneAt = maxOf(doneAt, playAt + 2000) }
            endAt = SystemClock.uptimeMillis() + maxOf(doneAt + 300L, if (p.volOn) maxOf(settle, 300L) + 12000L else 0L)
            finish = { if (p.state == STARTING) { setState(ctx, p, ON); step("GO sequence", t0); com.lazyneil.lazycar.audio.TabletSpeaker.onGo(ctx); launchApps(ctx) } }
            main.postDelayed({ finish() }, doneAt + 300L)
        }
    }

    /** The speaker lives on exactly one device (multipoint on both kills the tablet's Dolby tuning): Tablet mode = the
     *  phone lets go and the tablet connects it, Phone mode = the tablet lets go (the phone connects it in GO). */
    fun handoff(ctx: Context, p: Prefs, toTablet: Boolean) {
        if (p.mac1.isEmpty()) return
        log("speaker ${p.mac1} -> ${if (toTablet) "tablet" else "phone"}")
        if (toTablet) disconnectA2dp(ctx, p.mac1)
        com.lazyneil.lazycar.media.MediaListener.speakerTo(p.mac1, p.name1, toTablet)
        if (!toTablet && p.state == ON) main.postDelayed({ connectA2dp(ctx, p.mac1) }, 1500)   // a mode change while ON
    }

    private var finish: () -> Unit = {}
    @Volatile private var acked = false
    const val TABLET_ACK_MS = 5000L

    /** Tablet mode: ask the tablet to play; no ack in 5 s = today's phone flow with a note. */
    private fun goTablet(ctx: Context, p: Prefs) {
        acked = false; ackP = p
        // The link may still be connecting (app just started): resend every 500 ms until the tablet answers.
        val at = SystemClock.uptimeMillis()
        main.post(object : Runnable { override fun run() {
            if (acked || p.state != STARTING || SystemClock.uptimeMillis() - at > TABLET_ACK_MS) return
            com.lazyneil.lazycar.media.MediaListener.goTablet(true); main.postDelayed(this, 500) } })
        main.postDelayed({
            if (acked || p.state != STARTING) return@postDelayed
            // CarApp on the tablet is the player: no other app is started in its place.
            log("tablet no ack"); toast(ctx, "Tablet not reachable. Open CarApp on the tablet"); finish()
        }, TABLET_ACK_MS)
    }
    /** The tablet answered "go": ON now. */
    fun tabletAcked() = main.post { log("tablet acked"); acked = true; ackP?.snapMusicVol = -1; finish() }   // phone volume untouched: STOP leaves it
    private var ackP: Prefs? = null

    /** GO opened the system "Turn on Bluetooth?" dialog ([GoActivity]); the user taps Allow. Waits for STATE_ON, settles
     *  1.5 s, runs the rest. Gives up after [BT_WAIT_MS]: GO goes back to idle, nothing else changed. */
    const val BT_WAIT_MS = 30000L
    private fun enableBtThenRun(ctx: Context, adapter: BluetoothAdapter, p: Prefs) {
        val started = AtomicBoolean(false)
        fun go(recv: BroadcastReceiver?) {
            if (!started.compareAndSet(false, true)) return
            recv?.let { try { ctx.unregisterReceiver(it) } catch (e: Exception) {} }
            log("BT on -> running sequence")
            main.postDelayed({ runSequence(ctx, p) }, 1500)
        }
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) go(this)
            }
        }
        ctx.registerReceiver(recv, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        log("BT off: waiting for the user to allow it")
        if (adapter.isEnabled) go(recv)
        main.postDelayed({
            if (!started.get()) {
                try { ctx.unregisterReceiver(recv) } catch (e: Exception) {}
                setState(ctx, p, IDLE)
                log("BT enable timeout")
            }
        }, BT_WAIT_MS)
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
        volTick?.let { main.removeCallbacks(it) }; volTick = null
        volReceiver?.let { try { ctx.applicationContext.unregisterReceiver(it) } catch (e: Exception) {} }
        volReceiver = null
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
    fun stop(ctx: Context, fromPeer: Boolean = false): Long {
        val p = Prefs(ctx)
        p.shutDown = true
        if (Role.isTablet(ctx)) return stopTablet(ctx, p, fromPeer)
        // First, while the hotspot (restored below) still carries the link: the tablet restores its own side.
        if (!fromPeer) com.lazyneil.lazycar.media.MediaListener.shutDownTablets()
        setState(ctx, p, STOPPING)
        com.lazyneil.lazycar.audio.TabletSpeaker.onStop(ctx)
        if (com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(ctx)) com.lazyneil.lazycar.media.MediaListener.goTablet(false)

        if (!p.snapMusicWasActive) { log("restore: music playing -> paused"); pauseMedia(ctx) }
        else log("restore: music was already playing -> left")
        // CarApp's own player: paused above if GO started it; its service ends either way (Shut down = no services).
        if (com.lazyneil.lazycar.audio.CarPlayer.session != null) {
            if (!p.snapMusicWasActive) com.lazyneil.lazycar.audio.CarPlayer.send(ctx, "pause")
            main.postDelayed({ ctx.stopService(Intent(ctx, com.lazyneil.lazycar.audio.CarPlayer::class.java)) }, 500) }

        // Restore the pre-GO STREAM_MUSIC level if GO changed it; the start volume watchdog must not undo it.
        unregisterVol(ctx)
        if (p.volOn && p.snapMusicVol >= 0) try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_MUSIC, p.snapMusicVol, 0)
            log("restore: music volume -> index ${p.snapMusicVol}")
        } catch (e: Exception) {}

        // The tablet dropped a speaker it did not have before (its own Shut down above); a phone that had it gets it back.
        if (p.mac1.isNotEmpty() && p.snapConn1 && com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(ctx))
            main.postDelayed({ connectA2dp(ctx, p.mac1) }, 1500)
        for ((mac, wasConnected) in listOf(p.mac1 to p.snapConn1, p.mac3 to p.snapConn3))
            if (mac.isNotEmpty() && !wasConnected) { log("restore: $mac connected -> disconnected"); disconnectA2dp(ctx, mac) }
            else if (mac.isNotEmpty()) log("restore: $mac was already connected -> left")

        // BT off is the system "Turn off Bluetooth?" dialog, the user taps it.
        if (!p.snapBtWasOn) { log("restore: bt on -> off"); disableBt(ctx); if (fromPeer) main.postDelayed({ btOffNotice(ctx) }, 3600L) }
        else log("restore: bt was already on -> left")

        com.lazyneil.lazycar.gps.GpsService.launch(ctx, "stop", save = false)
        val end = 1800L
        main.postDelayed({ setState(ctx, p, IDLE); p.clearSnap(); com.lazyneil.lazycar.media.MediaListener.sleep() }, end)
        clearNotifications(ctx, end + 1500L)
        return end + 500L
    }

    /** Tablet side of Shut down: undo what its GO changed, stop its services and the phone link. */
    private fun stopTablet(ctx: Context, p: Prefs, fromPeer: Boolean): Long {
        log("tablet shut down (from phone: $fromPeer)")
        if (!fromPeer) com.lazyneil.lazycar.media.PhoneMedia.send(org.json.JSONObject().put("c", "shutdown"))
        ctx.stopService(Intent(ctx, com.lazyneil.lazycar.audio.StreamReceiver::class.java))
        main.postDelayed({ ctx.stopService(Intent(ctx, com.lazyneil.lazycar.audio.CarPlayer::class.java)) }, 500)   // after its pause
        com.lazyneil.lazycar.media.PhoneMedia.speakerRestore(ctx)
        com.lazyneil.lazycar.gps.GpsService.launch(ctx, "stop", save = false)
        com.lazyneil.lazycar.media.PhoneMedia.stop()
        p.clearSnap()
        clearNotifications(ctx, 2500L)
        return 1500L
    }

    /** After Shut down no CarApp notification stays: every service is stopped by then (GoService ends at its endAt). */
    private fun clearNotifications(ctx: Context, at: Long) = main.postDelayed({
        if (Prefs(ctx).shutDown) ctx.getSystemService(android.app.NotificationManager::class.java).cancelAll()
    }, at)

    /** Shut down came from the tablet with the phone in the background: Android blocks the "Turn off Bluetooth?" dialog,
     *  so one tap-to-finish notification (posted after the Shut down clear) opens it from the user's own tap. */
    private fun btOffNotice(ctx: Context) {
        if (adapterOf(ctx)?.isEnabled != true) return
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("restore", "Shut down steps", android.app.NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val pi = android.app.PendingIntent.getActivity(ctx, 48, Intent(ctx, GoActivity::class.java).putExtra("action", "btoff"),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(BT_NOTICE_ID, android.app.Notification.Builder(ctx, "restore").setSmallIcon(R.drawable.ic_car_glyph)
            .setContentTitle("Bluetooth was off before Start").setContentText("Tap to turn it off")
            .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(10 * 60_000L).build())
        log("bt off notice posted")
    }
    const val BT_NOTICE_ID = 47476

    /** The system REQUEST_DISABLE dialog (no silent disable on Android 13+). Opens only while CarApp was just in front
     *  (app, widget, notification all go through [GoActivity]); from the tablet's Shut down Android blocks it and BT stays on. */
    fun disableBt(ctx: Context) {
        val adapter = adapterOf(ctx) ?: return
        val done = AtomicBoolean(false)
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF && done.compareAndSet(false, true)) {
                    try { ctx.unregisterReceiver(this) } catch (e: Exception) {}
                    ctx.getSystemService(android.app.NotificationManager::class.java).cancel(BT_NOTICE_ID)   // done: no stale notice
                    log("BT off")
                }
            }
        }
        ctx.registerReceiver(recv, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // ACTION_REQUEST_DISABLE is not a public SDK constant; its action string is stable.
        Ui.openSettings(ctx, Intent("android.bluetooth.adapter.action.REQUEST_DISABLE"), fallback = false)
        if (!adapter.isEnabled && done.compareAndSet(false, true)) {
            try { ctx.unregisterReceiver(recv) } catch (e: Exception) {}
        }
        main.postDelayed({
            if (!done.get()) { try { ctx.unregisterReceiver(recv) } catch (e: Exception) {}; log("BT disable timeout") }
        }, BT_WAIT_MS)
    }

    /** On app open: clears a stuck busy state. ON stays ON until Shut down (which restores the snapshot), even when
     *  the speaker went away: dropping to IDLE here would leave GO's changes behind with no way to undo them. */
    fun deriveState(ctx: Context, p: Prefs, done: (Int) -> Unit) {
        val es = effectiveState(p)
        if (es != p.state) setState(ctx, p, es)
        done(es)
    }

    private fun pauseMedia(ctx: Context) = mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
    /** Pause whatever holds the media keys (e.g. a video app), then PLAY straight to the chosen player's receiver. */
    fun playMedia(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName) { com.lazyneil.lazycar.audio.CarPlayer.send(ctx, "play"); return }
        if (pkg.isEmpty()) { mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY); return }
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
        Handler(Looper.getMainLooper()).postDelayed({ mediaTo(ctx, pkg, KeyEvent.KEYCODE_MEDIA_PLAY) }, 400)
    }
    /** One media key straight to [pkg]'s button receiver. */
    fun mediaTo(ctx: Context, pkg: String, code: Int) {
        val t = SystemClock.uptimeMillis()
        for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(t, t, a, code, 0)))
        log("sent key $code to $pkg")
    }
    fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** After GO: the app chosen in Settings (Widget tap opens). Split screen is the launcher's pair icon, the user taps it. */
    fun launchApps(ctx: Context) {
        val p = Prefs(ctx)
        if (p.launchMode == "none" || p.launchA.isEmpty()) return
        launchPlayer(ctx, p.launchA)
    }

    fun launchPlayer(ctx: Context, pkg: String) {
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
