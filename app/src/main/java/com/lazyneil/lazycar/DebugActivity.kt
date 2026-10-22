package com.lazyneil.lazycar

import android.app.AppOpsManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.lazyneil.lazycar.gps.GpsService
import com.lazyneil.lazycar.rear.RearActivity
import com.lazyneil.lazycar.rear.UsbDevices

/** Self-test screen, opened from Settings only. Each row shows PASS / FAIL / WAITING plus one line of detail. */
class DebugActivity : AppCompatActivity() {

    private enum class St { PASS, FAIL, WAITING }

    private class Row(
        val title: String,
        val eval: () -> Pair<St, String>,
        val buttons: List<Pair<String, () -> Unit>> = emptyList(),
        val fix: Intent? = null,
        val toggle: Pair<() -> Boolean, (Boolean) -> Unit>? = null,   // a switch in the button bar
    ) {
        lateinit var status: TextView
        lateinit var detail: TextView
        lateinit var fixBtn: MaterialButton
        var report = ""
    }

    private lateinit var p: Prefs
    private val rows = mutableListOf<Row>()
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 1000) }
    }
    @Volatile private var wifiSsid = ""
    private var wifiCb: ConnectivityManager.NetworkCallback? = null
    private var a2dp: List<String>? = null   // null = not loaded or unavailable
    private var a2dpNote = "Checking"

    override fun onCreate(savedInstanceState: Bundle?) {
        p = Prefs(this)
        Ui.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        Ui.edge(this, findViewById(R.id.root))
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topTitle).text = "Debug"
        findViewById<MaterialButton>(R.id.copyBtn).setOnClickListener {
            val text = rows.joinToString("\n") { it.report }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CarApp debug", text))
            Toast.makeText(this, R.string.debug_copied, Toast.LENGTH_SHORT).show()
        }

        val tablet = Role.isTablet(this)
        if (tablet) {
            rows += Row("GPS", ::gpsTablet)
            rows += Row("Maps test", { St.WAITING to "Tap a button, Maps should show your location" }, listOf(
                "My location" to { maps("geo:0,0?q=my+location") },
                "Navigate" to { maps("google.navigation:q=petrol+pump") }))
            rows += Row("Hotspot join", ::hotspot, listOf("Suggest again" to { com.lazyneil.lazycar.Drive.join(this) }),
                Intent(Settings.ACTION_WIFI_SETTINGS))
        } else {
            rows += Row("GPS", ::gpsPhone)
        }
        if (tablet) rows += Row("Rear camera", ::camera,
            listOf("Open preview" to { startActivity(Intent(this, RearActivity::class.java)) }))
        if (tablet) rows += Row("Car stereo", ::audio, listOf("Refresh" to { loadA2dp() }))
        else rows += Row("Hotspot, data, location", {
            val st = "hotspot ${GoAction.hotspotOn(this) ?: "unknown"}, mobile data ${GoAction.dataOn(this)}, location ${GoAction.locationOn(this)}"
            (if (GoAction.locationOn(this)) St.PASS else St.WAITING) to st })
        rows += if (tablet) Row("Phone link", {
            if (PhoneLink.connected) St.PASS to "Connected to ${PhoneLink.phone}" else St.WAITING to "Looking for the phone on Wi-Fi" })
        else Row("Tablet link", {
            if (!LinkService.bound) St.WAITING to "Off until the app is opened or GO"
            else St.PASS to "Listening, ${LinkService.clients} tablet(s)" })
        rows += Row("CarApp helper", { if (Ui.helperOn(this)) St.PASS to "On" else St.WAITING to "Off, the dialogs and the pair are one tap each" },
            fix = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        rows += Row("Battery optimisation", ::battery, fix = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        if (tablet) rows += Row("Mock location app", ::mock, fix = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        rows += Row("Boot receiver", ::boot)

        val host = findViewById<LinearLayout>(R.id.rows)
        val inf = LayoutInflater.from(this)
        for (r in rows) {
            val v = inf.inflate(R.layout.item_debug_row, host, false)
            v.findViewById<TextView>(R.id.dbgTitle).text = r.title
            r.status = v.findViewById(R.id.dbgStatus)
            r.detail = v.findViewById(R.id.dbgDetail)
            val bar = v.findViewById<LinearLayout>(R.id.dbgButtons)
            for ((label, action) in r.buttons) {
                bar.addView(button(label) { action(); h.postDelayed({ refresh() }, 500) })
            }
            r.toggle?.let { (get, set) -> bar.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                isChecked = get(); contentDescription = r.title
                setOnCheckedChangeListener { _, on -> set(on); h.postDelayed({ refresh() }, 500) } }) }
            r.fixBtn = button("Fix") { open(r.fix!!) }.also { it.visibility = View.GONE }
            if (r.fix != null) bar.addView(r.fixBtn)
            if (bar.childCount == 0) bar.visibility = View.GONE
            host.addView(v)
        }
        Ui.paintAccent(findViewById(R.id.root), Accent.now(this))   // switches in the live accent
    }

    override fun onStart() {
        super.onStart()
        try {
            wifiCb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                    wifiSsid = (c.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"") ?: ""
                }
                override fun onLost(n: Network) { wifiSsid = "" }
            }.also {
                getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), it)
            }
        } catch (_: Exception) { wifiCb = null }
        loadA2dp()
        h.post(tick)
    }

    override fun onStop() {
        h.removeCallbacks(tick)
        wifiCb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        wifiCb = null
        super.onStop()
    }

    private fun button(label: String, onClick: () -> Unit) =
        Ui.pillButton(this, label, when (label) { "Fix" -> 0; "Refresh" -> R.drawable.ic_refresh; else -> R.drawable.ic_play }, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = resources.getDimensionPixelSize(R.dimen.sp2) }
        }

    private fun refresh() {
        for (r in rows) {
            val (st, d) = try { r.eval() } catch (e: Exception) { St.FAIL to "Check failed: ${e.javaClass.simpleName}" }
            Ui.status(r.status, st.name.lowercase().replaceFirstChar { it.uppercase() },
                when (st) { St.PASS -> Ui.GREEN; St.FAIL -> getColor(R.color.error); St.WAITING -> Ui.AMBER })
            r.detail.text = d
            r.fixBtn.visibility = if (st != St.PASS && r.fix != null) View.VISIBLE else View.GONE
            r.report = "[${st.name}] ${r.title}: $d"
        }
    }

    private fun open(i: Intent) {
        Ui.openSettings(this, i)
    }

    private fun maps(uri: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        try { startActivity(Intent(i).setPackage("com.google.android.apps.maps")) }
        catch (e: Exception) {
            try { startActivity(i) } catch (e2: Exception) { Toast.makeText(this, "Maps not available", Toast.LENGTH_SHORT).show() }
        }
    }

    // ---- GPS ----
    private fun ageSec(elapsedMs: Long) = (SystemClock.elapsedRealtime() - elapsedMs) / 1000

    private fun lastKnown(lm: LocationManager): Location? = try {
        GpsService.PROVIDERS.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
    } catch (e: SecurityException) { null }

    private fun gpsPhone(): Pair<St, String> {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return St.FAIL to "Location permission missing"
        if (GpsService.mode != "send") return St.FAIL to "Sharing is off" + GpsService.error.let { if (it.isEmpty()) "" else ", $it" }
        if (!GpsService.live()) return St.WAITING to "Fix: no, waiting for GPS or network location"
        val lm = getSystemService(LocationManager::class.java)
        val gpsFresh = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { ageSec(it.elapsedRealtimeNanos / 1_000_000) < 5 } == true } catch (e: SecurityException) { false }
        val sats = GpsService.sats.takeIf { it >= 0 }?.let { ", $it sats" } ?: ""
        return St.PASS to "${GpsService.status()}. Fix: yes, accuracy ${GpsService.acc.toInt()} m$sats, source ${GpsService.source.ifEmpty { if (gpsFresh) "Phone GPS" else "Phone network" }}, packets ${GpsService.packets}"
    }

    private fun gpsTablet(): Pair<St, String> {
        val lm = getSystemService(LocationManager::class.java)
        val on = GpsService.PROVIDERS.map { lm.isProviderEnabled(it) }
        val mock = GpsService.PROVIDERS.indices.joinToString(" ") { "${listOf("gps", "network", "fused")[it]} ${if (on[it]) "on" else "off"}" }
        val k = lastKnown(lm)
        val age = k?.let { ageSec(it.elapsedRealtimeNanos / 1_000_000) }
        val fresh = GpsService.lastAt != 0L && ageSec(GpsService.lastAt) <= 10
        val src = if (fresh) GpsService.source.ifEmpty { "Phone GPS" } else "Tablet (phone offline)"
        val last = if (k == null) "no location yet" else "last ${age}s ago, ${k.accuracy.toInt()} m"
        val d = "Mock $mock. $last. Source: $src. Packets ${GpsService.packets}"
        return when {
            GpsService.mode != "receive" -> St.FAIL to "Receive is off. $d"
            on.any { !it } -> St.FAIL to d
            fresh -> St.PASS to d
            else -> St.WAITING to d
        }
    }

    // ---- hotspot, camera, car stereo ----
    private fun hotspot(): Pair<St, String> {
        val target = p.hotspotSsid.trim()
        val cur = wifiSsid.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val d = "Current: ${cur ?: "unknown"}, target: $target"
        return when {
            cur != null && cur == target -> St.PASS to d
            else -> St.WAITING to d
        }
    }

    private fun camera(): Pair<St, String> {
        val devs = getSystemService(UsbManager::class.java).deviceList.values
        val cams = devs.count { UsbDevices.isVideoDevice(it) }
        return if (cams > 0) St.PASS to "USB camera attached: yes ($cams)" else St.FAIL to "USB camera attached: no (${devs.size} USB devices)"
    }

    private fun loadA2dp() {
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            a2dp = null; a2dpNote = "Bluetooth permission missing"; return
        }
        val ad = getSystemService(BluetoothManager::class.java)?.adapter
        if (ad == null || !ad.isEnabled) { a2dp = null; a2dpNote = "Bluetooth is off"; return }
        try {
            ad.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(pr: Int, proxy: BluetoothProfile) {
                    a2dp = try { proxy.connectedDevices.map { it.name ?: "unnamed" } } catch (e: SecurityException) { null }
                    a2dpNote = "A2DP unavailable"
                    ad.closeProfileProxy(pr, proxy)
                }
                override fun onServiceDisconnected(pr: Int) {}
            }, BluetoothProfile.A2DP)
        } catch (e: Exception) { a2dp = null; a2dpNote = "A2DP unavailable" }
    }

    private fun audio(): Pair<St, String> {
        val l = a2dp ?: return St.FAIL to a2dpNote
        val spk = if (p.mac1.isEmpty()) "No car stereo chosen" else if (l.isEmpty()) "Not connected" else "Connected: ${l.joinToString()}"
        return (if (p.mac1.isNotEmpty() && l.isNotEmpty()) St.PASS else St.WAITING) to spk
    }

    // ---- health ----
    private fun battery() =
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) St.PASS to "Exempt"
        else St.FAIL to "Optimised, Android may kill background services"

    private fun mock(): Pair<St, String> {
        val ops = getSystemService(AppOpsManager::class.java)
        val ok = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        return if (ok) St.PASS to "Allowed" else St.FAIL to "Not selected as mock location app"
    }

    private fun boot(): Pair<St, String> {
        // Needs BootReceiver to store "bootAt" in the lazycar prefs, see report.
        val at = getSharedPreferences("lazycar", Context.MODE_PRIVATE).getLong("bootAt", 0L)
        return if (at == 0L) St.WAITING to "Not recorded yet"
        else St.PASS to "Last fired " + android.text.format.DateFormat.format("d MMM HH:mm", at)
    }
}
