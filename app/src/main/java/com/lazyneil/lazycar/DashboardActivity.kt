package com.lazyneil.lazycar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.lazyneil.lazycar.gps.GpsService
import com.lazyneil.lazycar.rear.RearActivity
import com.lazyneil.lazycar.rear.UsbDevices

/** Tablet home: one big card for the phone GPS link, one for the reverse camera. */
class DashboardActivity : AppCompatActivity() {

    private lateinit var p: Prefs
    private var amoledApplied = false
    private var accentApplied = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    private val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        p = Prefs(this)
        amoledApplied = p.amoled; accentApplied = p.accent
        Accent.apply(this)
        if (p.amoled) theme.applyStyle(R.style.ThemeOverlay_LazyCar_Amoled, true)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        findViewById<ImageView>(R.id.brandIcon).setColorFilter(Accent.color(this))
        findViewById<View>(R.id.settingsBtn).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val openCam = View.OnClickListener { startActivity(Intent(this, RearActivity::class.java)) }
        findViewById<View>(R.id.camCard).setOnClickListener(openCam)
        findViewById<MaterialButton>(R.id.camOpen).setOnClickListener(openCam)
        findViewById<MaterialButton>(R.id.setupBtn).setOnClickListener { requestPermissions(missing(), 1) }
        Role.applyComponents(this)
        if (missing().isNotEmpty()) requestPermissions(missing(), 1)
    }

    override fun onResume() {
        super.onResume()
        if (!Role.isTablet(this)) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        if (p.amoled != amoledApplied || p.accent != accentApplied) { recreate(); return }
        GpsService.startIfIdle(this)
        handler.post(tick)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        GpsService.startIfIdle(this); render()
    }

    private fun missing() = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun dot(id: Int, color: Int) { findViewById<View>(id).backgroundTintList = ColorStateList.valueOf(color) }

    private fun render() {
        val miss = missing()
        findViewById<View>(R.id.setupCard).visibility = if (miss.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.setupBody).text = "Allow " + miss.joinToString(", ") {
            it.substringAfterLast('.').lowercase().replace('_', ' ') }

        val green = 0xFF2EB872.toInt(); val amber = 0xFFF2A93B.toInt(); val off = getColor(R.color.secondaryText)

        val mode = GpsService.mode
        val age = if (GpsService.lastAt == 0L) -1 else (SystemClock.elapsedRealtime() - GpsService.lastAt) / 1000
        val live = mode != "idle" && age in 0..5
        dot(R.id.gpsDot, when { live -> green; mode != "idle" -> amber; else -> off })
        findViewById<TextView>(R.id.gpsState).text = when {
            mode == "idle" -> "Off"; live -> if (mode == "send") "Sending" else "Phone GPS live"
            else -> if (mode == "send") "No fix" else "No phone signal" }
        findViewById<TextView>(R.id.gpsDetail).text = when {
            GpsService.error.isNotEmpty() -> GpsService.error
            mode == "idle" -> "Turn it on in Settings"
            age < 0 -> if (mode == "send") "Waiting for a GPS fix" else "Waiting for the phone. Is its hotspot on?"
            else -> "Last fix ${age}s ago" + if (GpsService.peer.isNotEmpty()) " from ${GpsService.peer}" else ""
        }
        findViewById<TextView>(R.id.gpsStats).apply {
            visibility = if (mode != "idle" && age >= 0) View.VISIBLE else View.GONE
            text = "${GpsService.acc.toInt()} m accuracy    ${(GpsService.speed * 3.6f).toInt()} km/h" +
                if (GpsService.sats >= 0) "    ${GpsService.sats} satellites" else ""
        }

        val cam = getSystemService(UsbManager::class.java).deviceList.values.firstOrNull { UsbDevices.isVideoDevice(it) }
        dot(R.id.camDot, if (cam != null) green else off)
        findViewById<TextView>(R.id.camState).text = if (cam != null) "Camera connected" else "Standby"
        findViewById<TextView>(R.id.camDetail).text = if (cam != null) (cam.productName ?: "USB webcam")
            else "Opens by itself when you shift into reverse"
    }
}
