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
        Accent.apply(this)
        if (p.amoled) theme.applyStyle(R.style.ThemeOverlay_LazyCar_Amoled, true)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
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
            rows += Row("Hotspot join", ::hotspot, listOf("Run" to { TapService.joinHotspot() }),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            rows += Row("GPS", ::gpsPhone)
        }
        rows += Row("Split launch", ::split, listOf("Run" to { TapService.launchApps(this) }))
        if (tablet) rows += Row("Rear camera", ::camera,
            listOf("Open preview" to { startActivity(Intent(this, RearActivity::class.java)) }))
        else rows += Row("Dual audio", ::audio, listOf("Run" to {
            startActivity(Intent(this, GoActivity::class.java).putExtra("shareOnly", true)); loadA2dp()
        }))
        rows += Row("Accessibility service", ::a11y, fix = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
            r.fixBtn = button("Fix") { open(r.fix!!) }.also { it.visibility = View.GONE }
            if (r.fix != null) bar.addView(r.fixBtn)
            if (bar.childCount == 0) bar.visibility = View.GONE
            host.addView(v)
        }
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
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
        }

    private fun refresh() {
        val accent = Accent.color(this)
        for (r in rows) {
            val (st, d) = try { r.eval() } catch (e: Exception) { St.FAIL to "Check failed: ${e.javaClass.simpleName}" }
            r.status.text = st.name
            r.status.setTextColor(when (st) { St.PASS -> accent; St.FAIL -> getColor(R.color.errorRed); St.WAITING -> getColor(R.color.secondaryText) })
            r.detail.text = d
            r.fixBtn.visibility = if (st != St.PASS && r.fix != null) View.VISIBLE else View.GONE
            r.report = "[${st.name}] ${r.title}: $d"
        }
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
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
        val at = GpsService.lastAt
        if (at == 0L || ageSec(at) > 60) return St.WAITING to "Fix: no, waiting for GPS or network location"
        val lm = getSystemService(LocationManager::class.java)
        val gpsFresh = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { ageSec(it.elapsedRealtimeNanos / 1_000_000) < 5 } == true } catch (e: SecurityException) { false }
        val sats = GpsService.sats.takeIf { it >= 0 }?.let { ", $it sats" } ?: ""
        return St.PASS to "Fix: yes, accuracy ${GpsService.acc.toInt()} m$sats, source ${GpsService.source.ifEmpty { if (gpsFresh) "Phone GPS" else "Phone network" }}, packets ${GpsService.packets}"
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

    // ---- hotspot, split, camera, audio ----
    private fun hotspot(): Pair<St, String> {
        val target = p.hotspotSsid.trim()
        val cur = wifiSsid.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val d = "Current: ${cur ?: "unknown"}, target: $target"
        return when {
            cur != null && cur == target -> St.PASS to d
            !accessibilityEnabled() -> St.FAIL to "$d. Accessibility is off, cannot auto join"
            else -> St.WAITING to d
        }
    }

    private fun split(): Pair<St, String> {
        val mode = p.launchMode
        val d = "Mode $mode, A ${if (p.launchA.isEmpty()) "unset" else "set"}, B ${if (p.launchB.isEmpty()) "unset" else "set"}"
        return when {
            mode == "none" || p.launchA.isEmpty() -> St.FAIL to d
            mode == "split" && (p.launchB.isEmpty() || !accessibilityEnabled()) -> St.FAIL to "$d, accessibility ${if (accessibilityEnabled()) "on" else "off"}"
            else -> St.PASS to d
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
        if (l.isEmpty()) return St.WAITING to "No A2DP devices connected"
        val both = p.mac1.isNotEmpty() && p.mac2.isNotEmpty() && l.size >= 2
        return (if (both) St.PASS else St.WAITING) to "Connected (${l.size}): ${l.joinToString()}"
    }

    // ---- health ----
    private fun accessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(':').any { it.substringBefore('/') == packageName }
    }

    private fun a11y() = if (accessibilityEnabled()) St.PASS to "Enabled" else St.FAIL to "Off, auto-tap and hotspot join will not work"

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
