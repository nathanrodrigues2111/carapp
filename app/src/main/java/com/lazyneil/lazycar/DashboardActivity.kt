package com.lazyneil.lazycar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.lazyneil.lazycar.gps.GpsService

/** Tablet home: speed and GPS status, the setup line, Camera and Settings. Opening it starts GPS receive and the link. */
class DashboardActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); handler.postDelayed(this, 1000) } }
    // Precise location (coarse must be asked with it), nearby devices for the car stereo. Camera and its mic ask on rearview.
    private val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT)
    private var opening = true   // onCreate, onRestart, onNewIntent: the user opened CarApp

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        wake(intent)
        opening = savedInstanceState == null
        Ui.edge(this, findViewById(R.id.frame))
        findViewById<View>(R.id.settingsBtn).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.camBtn).setOnClickListener { startActivity(Intent(this, com.lazyneil.lazycar.rear.RearActivity::class.java)) }
        findViewById<View>(R.id.setupBanner).setOnClickListener {
            when {
                missing().isNotEmpty() -> requestPermissions(missing(), 1)
                // Straight to the row: Settings scrolls to and highlights the fragment arg key.
                !mockAllowed() -> Ui.openSettings(this, Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "mock_location_app"))
                Ui.offerHelper(this) -> Ui.openHelper(this)
                dozes() -> try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
        }
        Ui.press(findViewById(R.id.settingsBtn), findViewById(R.id.camBtn))
        Role.applyComponents(this)
        // Notifications are optional: asked once with location, the app works without them.
        if (missing().isNotEmpty()) requestPermissions(missing() + Manifest.permission.POST_NOTIFICATIONS, 1)
    }

    override fun onResume() {
        super.onResume()
        if (!Role.isTablet(this)) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        if (opening) { opening = false; if (SystemClock.uptimeMillis() >= GoAction.quietUntil) Prefs(this).shutDown = false }   // an open ends a Shut down, a resume (back from GoActivity) does not
        if (!Prefs(this).shutDown) { GpsService.startIfIdle(this); Drive.join(this); PhoneLink.start(this) }
        handler.post(tick)
    }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }
    override fun onRestart() { super.onRestart(); opening = true }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); opening = true; wake(i) }

    /** Phone GO woke us (the helper turned the screen on): past a swipe lock. A secure lock still asks the user.
     *  Only for the moment of the wake, so a later screen off stays off. */
    private fun wake(i: Intent) {
        if (!i.getBooleanExtra("wake", false)) return
        setShowWhenLocked(true)
        getSystemService(android.app.KeyguardManager::class.java).requestDismissKeyguard(this, null)
        handler.postDelayed({ setShowWhenLocked(false) }, 5000)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        GpsService.startIfIdle(this); render()
    }

    private fun mockAllowed() = getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(
        android.app.AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    /** Helper on but Doze may hold the phone's wake packet: ask once for the battery exemption. */
    private fun dozes() = Ui.helperOn(this) && !getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun missing() = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun render() {
        findViewById<TextView>(R.id.setupBanner).apply {
            text = when {
                missing().isNotEmpty() -> if (Manifest.permission.BLUETOOTH_CONNECT in missing()) "Nearby devices is off (car stereo). Tap to allow" else "Precise location is off. Tap to allow"
                !mockAllowed() -> "Phone GPS for Maps: tap, then Select mock location app, CarApp"
                Ui.offerHelper(this@DashboardActivity) -> "Open CarApp, the car stereo and your app pair when the phone starts: tap, then CarApp helper, turn on"
                dozes() -> "Let the phone's Start wake this tablet from deep sleep: tap, Allow"
                else -> ""
            }
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val live = GpsService.live()
        Ui.status(findViewById(R.id.gpsText), GpsService.status(), when { live -> Ui.GREEN; GpsService.mode != "idle" -> Ui.AMBER; else -> Ui.attr(this, R.attr.carTextDim) })
        findViewById<TextView>(R.id.speedText).text = if (live) "${(GpsService.speed * 3.6f).toInt()} km/h" else "-- km/h"
    }
}
