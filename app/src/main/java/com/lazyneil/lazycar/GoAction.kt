package com.lazyneil.lazycar

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's "get in car" sequence, and its undo (Shut down). Runs from a foreground GoService so the postDelayed chain
 * survives independent of any activity. [GoActivity] asks first for what is off (location, mobile data, hotspot). Bluetooth helpers here serve the tablet's side (car stereo, see [SplitService]).
 */
object GoAction {
    const val IDLE = 0; const val STARTING = 1; const val ON = 2; const val STOPPING = 3
    const val ACTION_STATE = "com.lazyneil.lazycar.STATE"   // sticky-less local ping so an open MainActivity re-renders
    private const val STUCK_MS = 30000L
    val main = Handler(Looper.getMainLooper())

    private fun log(m: String) = android.util.Log.e("LazyCar", m)

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
        // Busy states auto-expire to IDLE after 30s (effectiveState); re-render the widget then too.
        // ponytail: handler only, fires if the process is still alive at +30s (it is, during any real sequence).
        if (busy) main.postDelayed({
            GoWidget.refresh(ctx); ctx.sendBroadcast(Intent(ACTION_STATE).setPackage(ctx.packageName))
        }, STUCK_MS + 500L)
    }

    /** Entry for GO (phone), after [GoActivity] walked through the system screens: link, tablet wake, ON. */
    fun run(ctx: Context): Long {
        val p = Prefs(ctx)
        p.shutDown = false; LinkService.wake(ctx)
        Drive.wakeTablet(ctx)   // the tablet's helper does its side
        setState(ctx, p, STARTING)
        endAt = SystemClock.uptimeMillis() + 1000L
        main.postDelayed({ if (p.state == STARTING) setState(ctx, p, ON) }, 300L)
        return 1000L
    }

    const val HOTSPOT = "hotspot"; const val DATA = "data"; const val LOCATION = "location"
    const val WIFI = "Wi-Fi"   // the tablet's Quick Settings tile (ZUI), any case
    fun locationOn(ctx: Context) = ctx.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
    fun dataOn(ctx: Context) = try { ctx.getSystemService(android.telephony.TelephonyManager::class.java).isDataEnabled } catch (e: Exception) { true }
    /** Hidden WifiManager.isWifiApEnabled; null when this ROM blocks it. */
    fun hotspotOn(ctx: Context): Boolean? = try {
        android.net.wifi.WifiManager::class.java.getMethod("isWifiApEnabled").invoke(ctx.getSystemService(android.net.wifi.WifiManager::class.java)) as Boolean
    } catch (e: Exception) { null }
    /** The system screen where the user flips [item]. */
    fun screen(item: String): Intent = when (item) {
        HOTSPOT -> Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        DATA -> Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        else -> Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }

    // ---- Bluetooth profile-proxy helper (connect / disconnect / getConnected) ----

    private fun adapterOf(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private fun withProxy(ctx: Context, adapter: BluetoothAdapter, profile: Int, action: (BluetoothProfile) -> Unit) {
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(pr: Int, proxy: BluetoothProfile) {
                try { action(proxy) } finally { adapter.closeProfileProxy(pr, proxy) }
            }
            override fun onServiceDisconnected(pr: Int) {}
        }, profile)
    }

    /** Reflected A2DP/HEADSET connect or disconnect(device); [toastName] Toasts once if both profiles fail. */
    private fun profileAction(ctx: Context, mac: String, method: String, toastName: Boolean) {
        val adapter = adapterOf(ctx) ?: return
        val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) { return }
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        val pending = java.util.concurrent.atomic.AtomicInteger(profiles.size)
        val anyOk = AtomicBoolean(false)
        for (profile in profiles) withProxy(ctx, adapter, profile) { proxy ->
            try {
                val r = proxy.javaClass.getMethod(method, BluetoothDevice::class.java).apply { isAccessible = true }.invoke(proxy, device)
                if (r == null || r == true) anyOk.set(true)
            } catch (e: Exception) {
                android.util.Log.w("LazyCar", "$method $mac", (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e)
            } finally {
                if (pending.decrementAndGet() == 0 && !anyOk.get() && toastName) toast(ctx, "Connect failed (${Prefs(ctx).name1.ifEmpty { mac }})")
            }
        }
    }
    fun connectA2dp(ctx: Context, mac: String) = profileAction(ctx, mac, "connect", true)
    fun disconnectA2dp(ctx: Context, mac: String) = profileAction(ctx, mac, "disconnect", false)

    /** Async list of currently A2DP-connected device MACs. */
    fun a2dpConnectedDevices(ctx: Context, done: (List<String>) -> Unit) {
        val adapter = adapterOf(ctx)
        if (adapter == null || !adapter.isEnabled) { done(emptyList()); return }
        try {
            withProxy(ctx, adapter, BluetoothProfile.A2DP) { proxy ->
                val list = try { proxy.connectedDevices.map { it.address } } catch (e: Exception) { emptyList() }
                main.post { done(list) }
            }
        } catch (e: Exception) { done(emptyList()) }
    }

    /** Uptime until which coming back to CarApp is not an open: Shut down's own screens cover CarApp, and their closing
     *  must not undo the Shut down. */
    @Volatile var quietUntil = 0L
    const val BT_WAIT_MS = 30000L

    /** Stop GPS, the link and the tablet side. Returns ms alive. */
    fun stop(ctx: Context, fromPeer: Boolean = false): Long {
        val p = Prefs(ctx)
        p.shutDown = true; quietUntil = SystemClock.uptimeMillis() + BT_WAIT_MS
        if (Role.isTablet(ctx)) return stopTablet(ctx, p, fromPeer)
        // First, while the hotspot still carries the link: the tablet restores its own side.
        if (!fromPeer) LinkService.shutDownTablets()
        setState(ctx, p, STOPPING)
        com.lazyneil.lazycar.gps.GpsService.launch(ctx, "stop", save = false)
        val end = 1800L
        main.postDelayed({ setState(ctx, p, IDLE); p.clearSnap(); LinkService.sleep() }, end)
        clearNotifications(ctx, end + 1500L)
        return end + 6000L
    }
    /** Shut down leaves data and location as they are; it offers to turn off a hotspot GO turned on. */
    fun hotspotByGo(ctx: Context, p: Prefs) = !p.snapApWasOn && hotspotOn(ctx) != false

    /** Tablet side of Shut down: stop its services and the phone link; the helper undoes what the phone's GO did here. */
    private fun stopTablet(ctx: Context, p: Prefs, fromPeer: Boolean): Long {
        log("tablet shut down (from phone: $fromPeer)")
        if (!fromPeer) PhoneLink.send(org.json.JSONObject().put("c", "shutdown"))
        com.lazyneil.lazycar.gps.GpsService.launch(ctx, "stop", save = false)
        main.postDelayed({ PhoneLink.stop() }, 500)   // after the shutdown line went out
        SplitService.self?.close() ?: p.clearSnap()
        clearNotifications(ctx, 2500L)
        return 1500L
    }

    /** After Shut down no CarApp notification stays: every service is stopped by then (GoService ends at its endAt). */
    private fun clearNotifications(ctx: Context, at: Long) = main.postDelayed({
        if (Prefs(ctx).shutDown) ctx.getSystemService(android.app.NotificationManager::class.java).cancelAll()
    }, at)

    /** Tablet: the system "Turn off Bluetooth?" dialog (no silent disable on Android 13+), pressed by the helper. */
    fun disableBt(ctx: Context) {
        SplitService.press("Allow", "Turn off")
        Ui.openSettings(ctx, Intent("android.bluetooth.adapter.action.REQUEST_DISABLE"), fallback = false)   // not a public SDK constant
    }

    /** On app open: clears a stuck busy state. ON stays ON until Shut down (which restores the snapshot). */
    fun deriveState(ctx: Context, p: Prefs, done: (Int) -> Unit) {
        val es = effectiveState(p)
        if (es != p.state) setState(ctx, p, es)
        done(es)
    }

    private fun toast(ctx: Context, msg: String) =
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}
