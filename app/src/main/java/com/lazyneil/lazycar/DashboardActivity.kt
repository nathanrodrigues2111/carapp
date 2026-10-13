package com.lazyneil.lazycar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.Slider
import com.lazyneil.lazycar.audio.StreamReceiver
import com.lazyneil.lazycar.gps.GpsService
import com.lazyneil.lazycar.media.LibraryActivity
import com.lazyneil.lazycar.media.PhoneMedia
import kotlin.math.abs

/** Tablet home: the phone's player, Poweramp style. One layout: vertical in the Maps split, side by side when wide. */
class DashboardActivity : AppCompatActivity() {

    private lateinit var root: View
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    private val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun click(id: Int, f: () -> Unit) = findViewById<View>(id).setOnClickListener { f() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        Ui.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        root = findViewById(R.id.root)
        Ui.edge(this, findViewById(R.id.frame))   // backdrop stays full window
        if (resources.configuration.screenWidthDp >= 840) {   // full width: rail left, art left, controls right
            findViewById<LinearLayout>(R.id.frame).orientation = LinearLayout.HORIZONTAL
            findViewById<LinearLayout>(R.id.rail).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.car_rail), -1)
                setPadding(0, dp(24), 0, dp(24)); setBackgroundColor(0x66000000)
            }
            findViewById<View>(R.id.body).apply {
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f); setPadding(dp(48), dp(48), dp(48), dp(48))
            }
            findViewById<LinearLayout>(R.id.body).orientation = LinearLayout.HORIZONTAL
            findViewById<View>(R.id.artFrame).layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            findViewById<LinearLayout>(R.id.side).apply {
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(48) }
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START; setPadding(0, 0, 0, 0)
            }
            for (id in intArrayOf(R.id.npTitle, R.id.npArtist)) findViewById<TextView>(id).gravity = android.view.Gravity.START
        } else for (id in intArrayOf(R.id.npTitle, R.id.npArtist)) findViewById<TextView>(id).gravity = android.view.Gravity.CENTER
        // Keep the art square: the largest square that fits its frame.
        findViewById<View>(R.id.artFrame).addOnLayoutChangeListener { f, l, t, r, b, _, _, _, _ ->
            val side = minOf(r - l, b - t); val box = findViewById<View>(R.id.npArtBox)
            if (box.layoutParams.width != side) f.post { box.layoutParams = box.layoutParams.apply { width = side; height = side } }
        }
        val settings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        click(R.id.settingsBtn, settings); click(R.id.statusRow, settings)
        click(R.id.npLibrary) { startActivity(Intent(this, LibraryActivity::class.java)) }
        click(R.id.setupBanner) {
            if (missing().isNotEmpty()) requestPermissions(missing(), 1)
            else startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        click(R.id.npPrev) { PhoneMedia.cmd("prev") }; click(R.id.npNext) { PhoneMedia.cmd("next") }
        click(R.id.npPlay) { PhoneMedia.cmd("toggle") }
        findViewById<Slider>(R.id.npSeek).addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) { seeking = true }
            override fun onStopTrackingTouch(s: Slider) { seeking = false; PhoneMedia.cmd("seek") { put("pos", s.value.toLong()) } }
        })
        artGestures(findViewById(R.id.npArt))
        Ui.paintAccent(root, Accent.forArt(this, null))
        Ui.blur(findViewById(R.id.npBackdrop))
        Ui.press(*intArrayOf(R.id.npPrev, R.id.npNext, R.id.npPlay, R.id.npLibrary, R.id.settingsBtn)
            .map { findViewById<View>(it) }.toTypedArray())
        Role.applyComponents(this)
        if (missing().isNotEmpty()) requestPermissions(missing(), 1)
    }

    override fun onResume() {
        super.onResume()
        if (!Role.isTablet(this)) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        GpsService.startIfIdle(this)
        TapService.joinHotspot()
        PhoneMedia.start(this)
        PhoneMedia.onChange = { renderNowPlaying() }
        handler.post(tick)
        // Full screen: go to the Maps + CarApp split. In the split isInMultiWindowMode is true and
        // launchDrive skips when already split, and the 15 s gap stops retries, so it cannot loop.
        if (!isInMultiWindowMode && Ui.openOnLaunch(this) && SystemClock.elapsedRealtime() - splitAt > 15_000) {
            splitAt = SystemClock.elapsedRealtime(); TapService.launchDrive(this)
        }
    }
    private companion object { var splitAt = -15_000L }

    override fun onPause() { super.onPause(); handler.removeCallbacks(tick); PhoneMedia.onChange = null }

    private var seeking = false
    private var shownArt: android.graphics.Bitmap? = null
    private var shownPlaying: Boolean? = null
    private var shownTitle = ""; private var titleAt = 0L

    /** Tap = play/pause, swipe left = next, swipe right = previous. */
    private fun artGestures(art: ImageView) {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                PhoneMedia.cmd("toggle"); art.performHapticFeedback(HapticFeedbackConstants.CONFIRM); return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (abs(dx) < dp(80) || abs(dx) < abs(e2.y - e1.y)) return false
                PhoneMedia.cmd(if (dx < 0) "next" else "prev")
                art.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                art.animate().translationX(if (dx < 0) -dp(40).toFloat() else dp(40).toFloat()).setDuration(120)
                    .withEndAction { art.animate().translationX(0f).setDuration(220).start() }.start()
                return true
            }
        })
        art.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
    }

    private fun clock(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

    private fun renderNowPlaying() {
        val np = PhoneMedia.np; val has = PhoneMedia.hasTrack
        Ui.swapText(findViewById(R.id.npTitle), when { !PhoneMedia.connected -> "Phone not connected"; has -> np.optString("title"); else -> "Nothing playing" })
        Ui.swapText(findViewById(R.id.npArtist), when {
            !PhoneMedia.connected -> "Open CarApp on the phone"
            has -> listOf(np.optString("artist"), np.optString("album")).filter { it.isNotEmpty() }.joinToString(" · ")
            else -> "Start music on the phone" })
        if (shownArt !== PhoneMedia.art) {
            shownArt = PhoneMedia.art
            Ui.swapArt(findViewById(R.id.npArt), shownArt)
            findViewById<ImageView>(R.id.npBackdrop).setImageBitmap(shownArt)
            Ui.paintAccent(root, Accent.forArt(this, shownArt))
        }
        // Loader on the art while a new track's art is still coming from the phone (gives up after 5 s).
        if (np.optString("title") != shownTitle) { shownTitle = np.optString("title"); titleAt = SystemClock.elapsedRealtime() }
        Ui.showLoader(findViewById(R.id.npLoader), PhoneMedia.connected && has && PhoneMedia.art == null && SystemClock.elapsedRealtime() - titleAt < 5000)
        val playing = PhoneMedia.playing
        if (shownPlaying != playing) {
            Ui.morph(findViewById<ImageButton>(R.id.npPlay), playing, shownPlaying != null)
            shownPlaying = playing
        }
        val dur = np.optLong("dur"); val pos = PhoneMedia.position()
        findViewById<Slider>(R.id.npSeek).apply {
            isEnabled = has && dur > 0
            if (!seeking) { valueTo = maxOf(dur, 1).toFloat(); value = pos.coerceIn(0, maxOf(dur, 1)).toFloat() }
        }
        findViewById<TextView>(R.id.npPos).text = if (dur > 0) clock(pos) else ""
        findViewById<TextView>(R.id.npDur).text = if (dur > 0) "-" + clock(maxOf(0, dur - pos)) else ""
        for (id in intArrayOf(R.id.npPrev, R.id.npPlay, R.id.npNext, R.id.npLibrary)) findViewById<View>(id).isEnabled = PhoneMedia.connected
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        GpsService.startIfIdle(this); render()
    }

    private fun missing() = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun render() {
        renderNowPlaying()
        val miss = missing()
        // The accessibility service is what turns Wi-Fi on, joins the phone hotspot and opens the Maps split.
        val a11y = (android.provider.Settings.Secure.getString(contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("$packageName/")
        findViewById<TextView>(R.id.setupBanner).apply {
            text = when {
                miss.isNotEmpty() -> "Tap to allow permissions"
                !a11y -> "Tap to turn on CarApp in Accessibility"
                else -> ""
            }
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val off = Ui.attr(this, R.attr.carTextDim)
        val mode = GpsService.mode
        val age = if (GpsService.lastAt == 0L) -1 else (SystemClock.elapsedRealtime() - GpsService.lastAt) / 1000
        val live = mode != "idle" && age in 0..5
        Ui.dot(findViewById(R.id.stGpsDot), when { live -> Ui.GREEN; mode != "idle" -> Ui.AMBER; else -> off }, live)
        val stream = StreamReceiver.running && SystemClock.elapsedRealtime() - StreamReceiver.lastAt <= 2000
        Ui.dot(findViewById(R.id.stStreamDot), when { stream -> Ui.GREEN; StreamReceiver.running -> Ui.AMBER; else -> off }, stream)
        findViewById<TextView>(R.id.speedText).text = if (live) "${(GpsService.speed * 3.6f).toInt()} km/h" else "-- km/h"
    }
}
