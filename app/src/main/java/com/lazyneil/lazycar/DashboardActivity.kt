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
import com.google.android.material.slider.Slider
import com.lazyneil.lazycar.audio.StreamReceiver
import com.lazyneil.lazycar.gps.GpsService
import com.lazyneil.lazycar.media.LibraryActivity
import com.lazyneil.lazycar.media.PhoneMedia
import com.lazyneil.lazycar.rear.RearActivity
import com.lazyneil.lazycar.rear.UsbDevices

/** Tablet home: phone Now Playing on top, then the phone GPS link and the reverse camera. */
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
        findViewById<MaterialButton>(R.id.setupBtn).setOnClickListener {
            if (missing().isNotEmpty()) requestPermissions(missing(), 1)
            else startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setupNowPlaying()
        findViewById<android.view.ViewGroup>(R.id.npVol).let { com.lazyneil.lazycar.media.Volumes.attach(this, it, 0) }
        Role.applyComponents(this)
        if (missing().isNotEmpty()) requestPermissions(missing(), 1)
    }

    override fun onResume() {
        super.onResume()
        if (!Role.isTablet(this)) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        if (p.amoled != amoledApplied || p.accent != accentApplied) { recreate(); return }
        GpsService.startIfIdle(this)
        TapService.joinHotspot()
        PhoneMedia.start(this)
        PhoneMedia.onChange = { renderNowPlaying() }
        handler.post(tick)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(tick); PhoneMedia.onChange = null }

    private var seeking = false
    private var shownArt: android.graphics.Bitmap? = null

    private fun setupNowPlaying() {
        fun tap(id: Int, c: String, extra: org.json.JSONObject.() -> Unit = {}) =
            findViewById<View>(id).setOnClickListener { PhoneMedia.cmd(c, extra) }
        tap(R.id.npPrev, "prev"); tap(R.id.npNext, "next"); tap(R.id.npPlay, "toggle")
        tap(R.id.npVolDown, "vol") { put("d", -1) }; tap(R.id.npVolUp, "vol") { put("d", 1) }
        findViewById<View>(R.id.npLibrary).setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }
        findViewById<Slider>(R.id.npSeek).addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) { seeking = true }
            override fun onStopTrackingTouch(s: Slider) { seeking = false; PhoneMedia.cmd("seek") { put("pos", s.value.toLong()) } }
        })
    }

    private fun clock(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

    private fun renderNowPlaying() {
        val np = PhoneMedia.np; val has = PhoneMedia.hasTrack
        // Main focus while the phone streams: bigger art.
        val streaming = StreamReceiver.running && SystemClock.elapsedRealtime() - StreamReceiver.lastAt <= 2000
        findViewById<View>(R.id.npArtBox).layoutParams.let { val px = ((if (streaming) 260 else 200) * resources.displayMetrics.density).toInt()
            if (it.width != px) { it.width = px; it.height = px; findViewById<View>(R.id.npArtBox).requestLayout() } }
        findViewById<TextView>(R.id.npLabel).text = if (has) "Now playing on phone, ${np.optString("app")}" else "Now playing"
        findViewById<TextView>(R.id.npTitle).text = when { !PhoneMedia.connected -> "Phone not connected"; has -> np.optString("title"); else -> "Nothing playing" }
        findViewById<TextView>(R.id.npArtist).text = when {
            !PhoneMedia.connected -> "Open CarApp on the phone and allow notification access"
            has -> listOf(np.optString("artist"), np.optString("album")).filter { it.isNotEmpty() }.joinToString("  ")
            else -> "Start music on the phone" }
        if (shownArt !== PhoneMedia.art) { shownArt = PhoneMedia.art; findViewById<ImageView>(R.id.npArt).setImageBitmap(shownArt) }
        findViewById<MaterialButton>(R.id.npPlay).setIconResource(if (PhoneMedia.playing) R.drawable.ic_pause else R.drawable.ic_play)
        val dur = np.optLong("dur"); val pos = PhoneMedia.position()
        findViewById<Slider>(R.id.npSeek).apply {
            isEnabled = has && dur > 0
            if (!seeking) { valueTo = maxOf(dur, 1).toFloat(); value = pos.coerceIn(0, maxOf(dur, 1)).toFloat() }
        }
        findViewById<TextView>(R.id.npPos).text = if (dur > 0) clock(pos) else ""
        findViewById<TextView>(R.id.npDur).text = if (dur > 0) clock(dur) else ""
        for (id in intArrayOf(R.id.npPrev, R.id.npPlay, R.id.npNext, R.id.npVolDown, R.id.npVolUp, R.id.npLibrary))
            findViewById<View>(id).isEnabled = PhoneMedia.connected
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        GpsService.startIfIdle(this); render()
    }

    private fun missing() = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun dot(id: Int, color: Int) { findViewById<View>(id).backgroundTintList = ColorStateList.valueOf(color) }

    private fun render() {
        renderNowPlaying()
        val miss = missing()
        // The accessibility service is what turns Wi-Fi on and joins the phone hotspot.
        val a11y = (android.provider.Settings.Secure.getString(contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("$packageName/")
        findViewById<View>(R.id.setupCard).visibility = if (miss.isEmpty() && a11y) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.setupBody).text = if (miss.isNotEmpty()) "Allow " + miss.joinToString(", ") {
            it.substringAfterLast('.').lowercase().replace('_', ' ') }
            else "Turn on CarApp in Accessibility so it can join the phone hotspot"

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
            else -> "Last fix ${age}s ago" + (if (GpsService.source.isNotEmpty()) ". Source: ${GpsService.source}" else "") + if (GpsService.peer.isNotEmpty()) " from ${GpsService.peer}" else ""
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
