package com.lazyneil.lazycar.gps

import com.lazyneil.lazycar.R

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
        private const val NOTIF_ID = com.lazyneil.lazycar.GoService.NOTIF_ID   // one shared CarApp notification
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

        /** Indoors a stationary fused fix comes every 20 s to minutes; the phone repeats it at 1 Hz for this long. */
        const val HOLD_MS = 300_000L

        /** Fix still being shared: the phone repeats its last fix for [HOLD_MS], the tablet needs a packet within 5 s. */
        fun live() = mode != "idle" && lastAt != 0L &&
            SystemClock.elapsedRealtime() - lastAt <= if (mode == "send") HOLD_MS else 5000

        /** One status line for every screen. */
        fun status() = when {
            error.isNotEmpty() -> error
            mode == "idle" -> "Off"
            paused -> "Paused, waiting for Start or the tablet"
            !live() -> if (mode == "send") "Searching for a GPS fix" else "Searching for the phone"
            else -> (if (mode == "send") "Sharing" else "Receiving") +
                (if (source == "Phone network") ", network only" else "") + ", ${acc.toInt()} m"
        }

        @Volatile var degraded = false

        fun startIfIdle(ctx: Context) {
            if (mode == "idle" || degraded) autoMode(ctx).takeIf { it != "stop" }?.let { launch(ctx, it, save = false) }
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
        mode = m; error = ""; lastAt = 0; sats = -1; peer = ""; packets = 0; source = ""; running = true
        // Location type is refused from the background (boot, force-stop restart) without while-in-use eligibility:
        // run under specialUse instead and upgrade on the next startIfIdle (activity resume or GO).
        degraded = try { startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); false }
        catch (e: Exception) {
            Log.w("LazyGPS", "location FGS refused, falling back", e)
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); true
        }
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
        return Notification.Builder(this, com.lazyneil.lazycar.GoService.channel(this)).setContentTitle(title).setContentText(text)
            .setSmallIcon(R.drawable.ic_pin).setContentIntent(open).addAction(com.lazyneil.lazycar.GoService.shutAction(this)).build()
    }

    // Refresh the notification text at most every 5 s.
    private fun gotFix() {
        lastAt = SystemClock.elapsedRealtime()
        // No notify(): the id is shared, GO and the speaker own its text.
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
            val onSats = now - gpsAt <= 10000
            // Zero drops: every fused HIGH_ACCURACY fix is a Wi-Fi scan that stalls the stream, so never while streaming.
            val streaming = com.lazyneil.lazycar.audio.StreamSender.running && com.lazyneil.lazycar.audio.StreamSender.tablet.isNotEmpty()
                && getSystemService(android.media.AudioManager::class.java).isMusicActive
            val precise = lastLoc?.let { it.accuracy <= PRECISE_M } == true
            val holding = precise && (streaming || !needFix && now - preciseAt < REFRESH_MS)
            // Tunnel: the last satellite fix was moving, dead-reckon along it for up to 30 s, then stand there.
            val reckon = !onSats && lastLoc != null && lastLoc === gpsLoc && gpsLoc!!.speed > 1f
            if (now - sentAt > 900) when {
                onSats -> last?.let { send(it) }
                reckon -> { send(packet(ahead(gpsLoc!!, now - gpsAt), held = now - gpsAt > 30000)); lastAt = now }
                // Same position, speed 0, fresh stamp, as long as the hold lasts.
                holding -> { send(packet(lastLoc!!, held = true)); lastAt = now }
                else -> last?.let { if (now - lastFixAt < HOLD_MS) send(it) }
            }
            if (mode == "send" && !paused) {
                // A registration made while the service had no location capability stays unpermitted ("{na}") and never
                // delivers: re-register when nothing arrived for 60 s.
                if (fusedOn && now - maxOf(fixAt, fusedAt) > 60000) fused(false)
                // Not streaming: one fused fix at Start, on significant motion or after 10 min, then 30 s off.
                val want = !onSats && !streaming && !holding && now - fusedFixAt > 30000
                val why = when {
                    onSats -> "gps: satellites, fused off"
                    reckon -> "gps: no satellites, dead reckoning, fused off"
                    streaming -> "gps: streaming, fused off, holding fix acc=${lastLoc?.accuracy?.toInt()}m"
                    holding -> "gps: still, holding precise fix acc=${lastLoc?.accuracy?.toInt()}m"
                    want -> "gps: not streaming, high accuracy"
                    else -> "gps: fused fix, off for 30 s"
                }
                if (why != lastWhy) { Log.e("LazyCar", "$why age=${(now - lastFixAt) / 1000}s"); lastWhy = why }
                fused(want)
            }
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
            lm.registerGnssStatusCallback(gnss, android.os.Handler(Looper.getMainLooper()))
            needFix = true; arm()
        } else {
            lm.removeUpdates(this); fused(false); lm.unregisterGnssStatusCallback(gnss); disarm()
            radio?.release(); wake?.release(); last = null; lastLoc = null; gpsLoc = null; lastAt = 0; sats = -1
        }
    }

    // Fused only after 10 s without a GNSS fix, HIGH_ACCURACY (Wi-Fi based, ~10 to 20 m indoors; BALANCED is 100 m).
    // 10 s interval: GMS Wi-Fi scans follow it (1 s interval scanned ~7 times a minute and stalled the audio up to 1 s).
    // In the car GNSS works, so no scanning at all.
    // Motion: TYPE_SIGNIFICANT_MOTION (walking or driving, ignores the phone in a hand) or a precise fix older than
    // REFRESH_MS asks fused HIGH_ACCURACY for one new fix of PRECISE_M or better, then it holds again.
    private val PRECISE_M = 30f
    private var gpsLoc: Location? = null
    private var lastWhy = ""

    /** [l] moved [ms] along its speed and bearing, capped at 30 s. */
    private fun ahead(l: Location, ms: Long) = Location(l).apply {
        val d = l.speed * minOf(ms, 30000L) / 1000.0; val b = Math.toRadians(l.bearing.toDouble())
        latitude = l.latitude + d * Math.cos(b) / 111_320.0
        longitude = l.longitude + d * Math.sin(b) / (111_320.0 * Math.cos(Math.toRadians(l.latitude)))
        time = System.currentTimeMillis()
    }
    private val REFRESH_MS = 600_000L
    @Volatile private var needFix = true
    private var preciseAt = 0L
    private var fusedFixAt = 0L
    private val sm by lazy { getSystemService(SensorManager::class.java) }
    private val sig by lazy { sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }
    private val motion = object : TriggerEventListener() {
        override fun onTrigger(e: TriggerEvent) {
            Log.e("LazyCar", "gps: significant motion"); needFix = true; arm()   // one-shot: re-arm
        }
    }
    private fun arm() { sig?.let { sm.requestTriggerSensor(motion, it) } }
    private fun disarm() { sig?.let { sm.cancelTriggerSensor(motion, it) } }

    private val fusedL = android.location.LocationListener { onLocationChanged(it) }
    private var fusedOn = false
    private var fusedAt = 0L    // when fused was (re)registered
    private var fixAt = 0L      // last fix from any provider
    private fun fused(on: Boolean) {
        if (on == fusedOn) return
        fusedOn = on
        if (on) fusedAt = SystemClock.elapsedRealtime()
        // Indoors: share the last fused fix right away instead of waiting for the next one.
        if (on && last == null) try { lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < HOLD_MS * 1_000_000 }?.let { onLocationChanged(it) } } catch (_: SecurityException) {}
        if (on) lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER,
            android.location.LocationRequest.Builder(10_000L).setMinUpdateIntervalMillis(1000L).setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY).build(),
            mainExecutor, fusedL)
        else lm.removeUpdates(fusedL)
    }

    override fun onLocationChanged(l: Location) {
        val now = SystemClock.elapsedRealtime()
        if (l.provider == LocationManager.GPS_PROVIDER) { gpsAt = now; gpsLoc = l } else if (now - gpsAt < 3000) return
        fixAt = now
        // The cached fix seeded on register is not a new fix: it neither ends the request nor counts as precise.
        val fresh = SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos < 5_000_000_000L
        if (fresh && l.provider != LocationManager.GPS_PROVIDER) fusedFixAt = now
        val data = packet(l, held = false)
        acc = l.accuracy; speed = l.speed; gotFix()
        source = if (l.provider == LocationManager.GPS_PROVIDER) "Phone GPS" else "Phone network"
        if (fresh && l.accuracy <= PRECISE_M) { lastLoc = l; preciseAt = now; needFix = false }
        else if (lastLoc?.let { it.accuracy <= PRECISE_M } != true) lastLoc = l   // coarse never replaces a precise fix
        last = data; lastFixAt = now
        send(data)
    }

    private var lastLoc: Location? = null

    private fun packet(l: Location, held: Boolean) = JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("alt", l.altitude)
        .put("acc", l.accuracy.toDouble()).put("speed", if (held) 0.0 else l.speed.toDouble())
        .put("bearing", l.bearing.toDouble()).put("time", if (held) System.currentTimeMillis() else l.time)
        .put("sats", sats).put("src", l.provider).toString().toByteArray()

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

    // Phone fixes own gps/network/fused for the whole receive run; the test providers go away only when it stops.
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
        setMocking(true)     // the tablet's broken GNSS never owns gps: only phone fixes, via the mock providers
        // Some Wi-Fi drivers drop broadcast frames without this lock.
        val wm = getSystemService(WifiManager::class.java)
        wifiLock = wm.createMulticastLock("lazygps").apply { setReferenceCounted(false); acquire() }
        val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(PORT)) }
        socket = s
        thread {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { s.receive(pkt) } catch (_: java.net.SocketTimeoutException) {
                        val age = SystemClock.elapsedRealtime() - lastAt
                        // Maps calls a fix older than ~2 s "GPS signal lost"; bridge short packet gaps with the last fix.
                        if (age < 5000) lastJ?.let { inject(it) }
                        continue
                    }
                    val j = JSONObject(String(pkt.data, 0, pkt.length))
                    setMocking(true); inject(j); lastJ = j
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
        tick.removeCallbacks(repeat); last = null; lastLoc = null; gpsLoc = null; if (::lm.isInitialized) disarm(); lastJ = null
        socket?.close(); socket = null
        wifiLock?.release(); wifiLock = null
        radio?.release(); radio = null
        wake?.release(); wake = null
        if (::lm.isInitialized) {
            lm.removeUpdates(this); fused(false)   // fusedOn surviving a restart kept a dead registration forever
            lm.unregisterGnssStatusCallback(gnss)
            if (mode == "receive") setMocking(false)
        }
        mode = "idle"; targetsAt = 0; paused = false
    }

    override fun onDestroy() { Log.i("LazyGPS", "destroy"); stopAll(); super.onDestroy() }
}
