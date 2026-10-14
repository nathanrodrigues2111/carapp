package com.lazyneil.lazycar.gps

import com.lazyneil.lazycar.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

class GpsService : Service(), LocationListener {
    companion object {
        const val PORT = 47474
        private const val NOTIF_ID = 47474   // GoService owns id 1
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)

        // Live status, polled by the UI once a second.
        @Volatile var mode = "idle"
        @Volatile var lastAt = 0L
        @Volatile var acc = 0f
        @Volatile var speed = 0f
        @Volatile var sats = -1
        @Volatile var peer = ""
        @Volatile var error = ""
        @Volatile var packets = 0L      // sent (phone) or received (tablet) since start
        @Volatile var source = ""       // "Phone GPS", "Phone network", "Tablet (phone offline)"
        @Volatile var paused = false    // phone: GNSS off until GO is on or the tablet is linked

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("lazygps", Context.MODE_PRIVATE)

        // Saved manual choice wins; otherwise the tablet receives and the phone sends.
        fun autoMode(ctx: Context) = prefs(ctx).getString("mode", null)
            ?: if (com.lazyneil.lazycar.Role.isTablet(ctx)) "receive" else "send"

        /** Seconds the tablet holds the last phone fix before handing back to its own location. */
        fun holdSec(ctx: Context) = prefs(ctx).getInt("holdSec", 30)

        /** Off by default: this tablet's own GNSS never gets a fix, so the mock stays even when the phone goes quiet. */
        fun handBack(ctx: Context) = prefs(ctx).getBoolean("handBack", false)
        fun setHandBack(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("handBack", on).apply()

        /** Fix still being shared: the phone repeats its last fix for 60 s, the tablet needs a packet within 5 s. */
        fun live() = mode != "idle" && lastAt != 0L &&
            SystemClock.elapsedRealtime() - lastAt <= if (mode == "send") 60000 else 5000

        /** One status line for every screen. */
        fun status() = when {
            error.isNotEmpty() -> error
            mode == "idle" -> "Off"
            paused -> "Paused, waiting for GO or the tablet"
            !live() -> if (mode == "send") "Searching for a GPS fix" else "Searching for the phone"
            else -> (if (mode == "send") "Sharing" else "Receiving") +
                (if (source == "Phone network") ", network only" else "") + ", ${acc.toInt()} m"
        }

        fun startIfIdle(ctx: Context) {
            if (mode == "idle") autoMode(ctx).takeIf { it != "stop" }?.let { launch(ctx, it, save = false) }
        }

        fun launch(ctx: Context, m: String, save: Boolean = true) {
            if (save) prefs(ctx).edit().putString("mode", m).apply()
            val i = Intent(ctx, GpsService::class.java)
            // A location service without the permission would crash the whole app on startForeground.
            if (m != "stop" && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) { error = "Location permission needed"; return }
            try { if (m == "stop") ctx.stopService(i) else ctx.startForegroundService(i.putExtra("mode", m)) }
            catch (e: Exception) { error = e.message ?: e.toString(); Log.w("LazyGPS", "launch $m", e) }
        }
    }

    private lateinit var lm: LocationManager
    private var socket: DatagramSocket? = null
    private var wifiLock: WifiManager.MulticastLock? = null
    // Doze with the screen off otherwise sleeps the CPU and Wi-Fi radio and drops the broadcasts.
    private var wake: android.os.PowerManager.WakeLock? = null
    private var radio: WifiManager.WifiLock? = null
    private var targets = setOf<InetAddress>()
    private var targetsAt = 0L
    private var notifiedAt = 0L
    @Volatile private var running = false

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            sats = (0 until s.satelliteCount).count { s.usedInFix(it) }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val m = intent?.getStringExtra("mode") ?: autoMode(this).takeIf { it != "stop" } ?: return START_NOT_STICKY
        Log.i("LazyGPS", "start $m")
        stopAll()
        lm = getSystemService(LocationManager::class.java)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("gps", "GPS sharing", NotificationManager.IMPORTANCE_LOW))
        mode = m; error = ""; lastAt = 0; sats = -1; peer = ""; packets = 0; source = ""; running = true
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        // With the screen off the CPU and Wi-Fi radio sleep, the 1 Hz repeat stalls and fixes reach the tablet 20 to 30 s apart.
        radio = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "lazygps").apply { setReferenceCounted(false) }
        wake = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "lazycar:gps").apply { setReferenceCounted(false) }
        if (m == "receive") { radio?.acquire(); wake?.acquire() }
        try { if (m == "send") startSend() else startReceive() } catch (e: Exception) { error = e.message ?: e.toString() }
        return START_STICKY
    }

    private fun notification(): Notification {
        val title = if (mode == "send") "Sharing GPS" else "Using phone GPS"
        val text = when {
            lastAt == 0L -> if (mode == "send") "Searching for a GPS fix" else "Searching for the phone"
            else -> "Accuracy ${acc.toInt()} m"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, com.lazyneil.lazycar.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "gps").setContentTitle(title).setContentText(text)
            .setSmallIcon(R.drawable.ic_pin).setContentIntent(open).setOngoing(true).build()
    }

    // Refresh the notification text at most every 5 s.
    private fun gotFix() {
        lastAt = SystemClock.elapsedRealtime()
        if (lastAt - notifiedAt > 5000) {
            notifiedAt = lastAt
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        }
    }

    private var gpsAt = 0L
    private var last: ByteArray? = null
    private var lastFixAt = 0L
    private var sentAt = 0L
    private val tick = android.os.Handler(Looper.getMainLooper())
    // Stationary fused fixes arrive every 20 to 30 s; repeat the last one at 1 Hz (up to 60 s) so Maps never starves.
    private val repeat = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (mode == "send") active(com.lazyneil.lazycar.GoAction.effectiveState(com.lazyneil.lazycar.Prefs(this@GpsService)) == com.lazyneil.lazycar.GoAction.ON
                || com.lazyneil.lazycar.media.MediaListener.clients > 0)
            last?.let { if (now - sentAt > 900 && now - lastFixAt < 60000) send(it) }
            if (running) tick.postDelayed(this, 1000)
        }
    }

    // Send mode: GPS fix every second, broadcast as JSON over UDP. Fused fills in while GPS has no fix (indoors, tunnels).
    private fun startSend() {
        socket = DatagramSocket().apply { broadcast = true }
        paused = true; repeat.run()
    }

    // Battery: 1 Hz GNSS and the wakelock only while GO is on or the tablet holds the media link, checked by the 1 Hz tick.
    private fun active(on: Boolean) {
        if (on != paused) return
        paused = !on
        Log.i("LazyGPS", if (on) "send active" else "send paused")
        if (on) {
            radio?.acquire(); wake?.acquire()
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            // The provider/minTime overload asks fused for BALANCED, which indoors means one 100 m fix every 30 s.
            lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER,
                android.location.LocationRequest.Builder(1000L).setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY).build(),
                mainExecutor, this)
            lm.registerGnssStatusCallback(gnss, android.os.Handler(Looper.getMainLooper()))
        } else {
            lm.removeUpdates(this); lm.unregisterGnssStatusCallback(gnss)
            radio?.release(); wake?.release(); last = null; lastAt = 0; sats = -1
        }
    }

    override fun onLocationChanged(l: Location) {
        val now = SystemClock.elapsedRealtime()
        if (l.provider == LocationManager.GPS_PROVIDER) gpsAt = now else if (now - gpsAt < 3000) return
        val data = JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("alt", l.altitude)
            .put("acc", l.accuracy.toDouble()).put("speed", l.speed.toDouble())
            .put("bearing", l.bearing.toDouble()).put("time", l.time).put("sats", sats).put("src", l.provider).toString().toByteArray()
        acc = l.accuracy; speed = l.speed; gotFix()
        source = if (l.provider == LocationManager.GPS_PROVIDER) "Phone GPS" else "Phone network"
        last = data; lastFixAt = now
        send(data)
    }

    private fun send(data: ByteArray) {
        sentAt = SystemClock.elapsedRealtime(); packets++
        thread {
            val s = socket ?: return@thread
            for (addr in broadcastTargets()) try { s.send(DatagramPacket(data, data.size, addr, PORT)) } catch (_: Exception) {}
        }
    }

    // 255.255.255.255 plus each up interface's subnet broadcast, re-resolved every 5 s so hotspot on/off is picked up.
    @Synchronized private fun broadcastTargets(): Set<InetAddress> {
        val now = SystemClock.elapsedRealtime()
        if (now - targetsAt < 5000) return targets
        val out = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
            }
        } catch (_: Exception) {}
        targets = out; targetsAt = now
        peer = out.filter { it.hostAddress != "255.255.255.255" }.joinToString { it.hostAddress ?: "" }
        return out
    }

    private var mocking = false

    // Phone fixes own gps/network/fused while packets flow; the test providers go away after the hold time so the tablet's own location returns.
    private fun setMocking(on: Boolean) {
        if (on == mocking) return
        mocking = on
        for (p in PROVIDERS) try {
            if (on) {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
                lm.addTestProvider(p, ProviderProperties.Builder()
                    .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE).setPowerUsage(ProviderProperties.POWER_USAGE_LOW).build())
                lm.setTestProviderEnabled(p, true)
            } else lm.removeTestProvider(p)
        } catch (e: Exception) { error = e.message ?: e.toString() }
        if (!on) source = "Tablet (phone offline)"
        // After the 5 s bridge the last fix stays with its real, growing age, so apps can see it is stale.
    }

    // Receive mode: listen forever, feed every packet into the test providers.
    private fun startReceive() {
        mocking = true; setMocking(false)   // drop test providers a previous process left behind
        val handBack = handBack(this)
        if (!handBack) setMocking(true)     // the broken real GNSS never owns gps while receive runs
        // Some Wi-Fi drivers drop broadcast frames without this lock.
        val wm = getSystemService(WifiManager::class.java)
        wifiLock = wm.createMulticastLock("lazygps").apply { setReferenceCounted(false); acquire() }
        val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(PORT)) }
        socket = s
        val holdMs = holdSec(this) * 1000L
        thread {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { s.receive(pkt) } catch (_: java.net.SocketTimeoutException) {
                        val age = SystemClock.elapsedRealtime() - lastAt
                        if (handBack && mocking && age > holdMs) setMocking(false)
                        // Maps calls a fix older than ~2 s "GPS signal lost"; bridge short packet gaps with the last fix.
                        else if (mocking && age < 5000) lastJ?.let { inject(it) }
                        continue
                    }
                    val j = JSONObject(String(pkt.data, 0, pkt.length))
                    setMocking(true)
                    inject(j); lastJ = j
                    acc = j.optDouble("acc", 0.0).toFloat(); speed = j.optDouble("speed", 0.0).toFloat()
                    sats = j.optInt("sats", -1); peer = pkt.address.hostAddress ?: ""; error = ""
                    source = if (j.optString("src", "gps") == "gps") "Phone GPS" else "Phone network"
                    packets++
                    gotFix()
                } catch (e: Exception) { if (running) error = e.message ?: e.toString() }
            }
        }
    }

    private var lastJ: JSONObject? = null

    // Stamped with tablet time (the phone's clock and uptime mean nothing here). Gaps under 5 s get a fresh stamp so
    // Maps does not flash "signal lost"; after that the last fix stays with its real, growing age (re-injecting it with
    // the same stamp is dropped by LocationManager, so a growing accuracy cannot be shown without faking freshness).
    private fun inject(j: JSONObject) {
        for (p in PROVIDERS) {
            lm.setTestProviderLocation(p, Location(p).apply {
                latitude = j.getDouble("lat"); longitude = j.getDouble("lon")
                altitude = j.optDouble("alt", 0.0); accuracy = j.optDouble("acc", 10.0).toFloat()
                speed = j.optDouble("speed", 0.0).toFloat(); bearing = j.optDouble("bearing", 0.0).toFloat()
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                // Maps trusts a fix more when every accuracy field is present.
                verticalAccuracyMeters = accuracy * 1.5f; speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = if (speed > 1f) 10f else 90f
            })
        }
    }

    private fun stopAll() {
        running = false
        tick.removeCallbacks(repeat); last = null; lastJ = null
        socket?.close(); socket = null
        wifiLock?.release(); wifiLock = null
        radio?.release(); radio = null
        wake?.release(); wake = null
        if (::lm.isInitialized) {
            lm.removeUpdates(this)
            lm.unregisterGnssStatusCallback(gnss)
            if (mode == "receive") setMocking(false)
        }
        mode = "idle"; targetsAt = 0; paused = false
    }

    override fun onDestroy() { Log.i("LazyGPS", "destroy"); stopAll(); super.onDestroy() }
}
