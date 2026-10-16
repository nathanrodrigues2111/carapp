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
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    // Always needed: precise location (coarse must be asked with it). Camera asks on rearview, the mic when a phone copy plays.
    private val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private var micAsked = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun click(id: Int, f: () -> Unit) = findViewById<View>(id).setOnClickListener { f() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        Ui.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        launcherOpen = savedInstanceState == null && intent.getBooleanExtra("launcher", false)
        opening = savedInstanceState == null
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
            findViewById<TextView>(R.id.npTitle).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.t_track_wide))
        } else for (id in intArrayOf(R.id.npTitle, R.id.npArtist)) findViewById<TextView>(id).gravity = android.view.Gravity.CENTER
        // Keep the art square: the largest square that fits its frame. Every size change (text lines, banner,
        // split resize) goes through this one path and animates; only the first layout snaps.
        findViewById<View>(R.id.artFrame).addOnLayoutChangeListener { f, l, t, r, b, _, _, _, _ ->
            val side = minOf(r - l, b - t); val box = findViewById<View>(R.id.npArtBox)
            if (side <= 0 || side == artSide) return@addOnLayoutChangeListener
            artSide = side
            f.post {
                sizeAnim?.cancel()
                fun set(v: Int) { box.layoutParams = box.layoutParams.apply { width = v; height = v } }
                if (box.layoutParams.width <= 0) set(side)
                else sizeAnim = android.animation.ValueAnimator.ofInt(box.layoutParams.width, side).setDuration(300).apply {
                    interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                    addUpdateListener { set(it.animatedValue as Int) }; start()
                }
            }
        }
        val settings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        click(R.id.settingsBtn, settings); click(R.id.statusRow, settings)
        click(R.id.npLibrary) { startActivity(Intent(this, LibraryActivity::class.java)) }
        click(R.id.setupBanner) {
            when {
                missing().isNotEmpty() -> requestPermissions(missing(), 1)
                !com.lazyneil.lazycar.media.Files.has(this) -> com.lazyneil.lazycar.media.Files.pick(this)
                !mockAllowed() -> Ui.openSettings(this, Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        }
        click(R.id.npPrev) { PhoneMedia.cmd("prev") }; click(R.id.npNext) { PhoneMedia.cmd("next") }
        click(R.id.npPlay) { PhoneMedia.cmd("toggle") }
        findViewById<Slider>(R.id.npSeek).addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) { seeking = true }
            override fun onStopTrackingTouch(s: Slider) { seeking = false; seekAt = SystemClock.elapsedRealtime(); PhoneMedia.cmd("seek") { put("pos", s.value.toLong()) } }
        })
        // While dragging only the time label follows the finger; one seek goes out on release.
        findViewById<Slider>(R.id.npSeek).addOnChangeListener { s, v, user -> if (user) findViewById<TextView>(R.id.npPos).text = clock(v.toLong())
            .also { findViewById<TextView>(R.id.npDur).text = "-" + clock(maxOf(0, s.valueTo.toLong() - v.toLong())) } }
        artGestures(findViewById(R.id.npArt))
        // Marquee runs only while selected; twice per new title, then it rests (forever = redraws at the display rate).
        findViewById<TextView>(R.id.npTitle).apply { isSelected = true; marqueeRepeatLimit = 2 }
        Ui.paintAccent(root, Accent.forArt(this, null))
        Ui.blur(findViewById(R.id.npBackdrop))
        Ui.press(*intArrayOf(R.id.npPrev, R.id.npNext, R.id.npPlay, R.id.npLibrary, R.id.settingsBtn)
            .map { findViewById<View>(it) }.toTypedArray())
        Role.applyComponents(this)
        // One CarApp task: each launcher pair launch starts CarApp in a new task, so drop the older ones.
        getSystemService(android.app.ActivityManager::class.java).appTasks
            .filter { it.taskInfo.taskId != taskId }.forEach { try { it.finishAndRemoveTask() } catch (_: Exception) {} }
        // Notifications are optional: asked once with location, the app works without them.
        if (missing().isNotEmpty()) requestPermissions(missing() + Manifest.permission.POST_NOTIFICATIONS, 1)
    }

    override fun onResume() {
        super.onResume()
        if (!Role.isTablet(this)) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        if (opening) { opening = false; Prefs(this).shutDown = false }   // an open ends a Shut down, a resume (back from GoActivity) does not
        com.lazyneil.lazycar.media.Files.refresh(this)   // new music files show without a restart
        if (!Prefs(this).shutDown) {
            GpsService.startIfIdle(this)
            Drive.join(this)
            PhoneMedia.start(this)
            StreamReceiver.sync(this)
        }
        handler.post(tick)
        // A launcher (or Drive widget) open outside the split: remind the user of the Maps + CarApp pair icon.
        if (launcherOpen && !isInMultiWindowMode && SystemClock.elapsedRealtime() - splitAt > 15_000) {
            splitAt = SystemClock.elapsedRealtime(); Drive.hint(this)
        }
        launcherOpen = false
    }
    private var launcherOpen = false
    private var opening = true   // onCreate, onRestart, onNewIntent: the user opened CarApp
    override fun onRestart() { super.onRestart(); opening = true }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); opening = true; launcherOpen = i.getBooleanExtra("launcher", false) }
    private companion object { var splitAt = -15_000L }

    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    // Listen while visible (onStart / onStop), so a split focus change cannot drop the listener.
    private val npListener: () -> Unit = { renderNowPlaying() }
    override fun onStart() { super.onStart(); PhoneMedia.listeners += npListener }
    override fun onStop() { super.onStop(); PhoneMedia.listeners -= npListener }

    private var seeking = false; private var seekAt = 0L
    private var shownArt: android.graphics.Bitmap? = null
    private var artSide = 0; private var sizeAnim: android.animation.Animator? = null

    /** Crossfade, never a blank frame. Art is center cropped square first so both layers share one shape. */
    private fun crossfade(iv: ImageView, b: android.graphics.Bitmap?) {
        val to = b?.let { android.graphics.drawable.BitmapDrawable(resources, minOf(it.width, it.height).let { s -> android.media.ThumbnailUtils.extractThumbnail(it, s, s) }) }
            ?: android.graphics.drawable.ColorDrawable(0)
        val from = iv.drawable.let { if (it is android.graphics.drawable.TransitionDrawable) it.getDrawable(1) else it } ?: android.graphics.drawable.ColorDrawable(0)
        iv.setImageDrawable(android.graphics.drawable.TransitionDrawable(arrayOf(from, to)).apply { isCrossFadeEnabled = true; startTransition(260) })
    }
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
        val np = PhoneMedia.np; val has = PhoneMedia.hasTrack; val alive = PhoneMedia.alive
        // Any phone traffic (link, audio, GPS) = connected. No traffic: "Connecting..." for 10 s, then not connected.
        Ui.swapText(findViewById(R.id.npTitle), when { has -> np.optString("title"); alive -> "Nothing playing"
            PhoneMedia.reconnecting -> "Connecting..."; else -> "Phone not connected" })
        Ui.swapText(findViewById(R.id.npArtist), when {
            has -> listOf(np.optString("artist"), np.optString("album")).filter { it.isNotEmpty() }.joinToString(" · ")
            alive -> "Start music on the phone"
            PhoneMedia.reconnecting -> ""
            else -> "Phone not reachable. Same Wi-Fi and notification access on?" })
        // Last track stays during the grace period, slightly dimmed.
        findViewById<View>(R.id.npArtBox).let { val a = if (alive) 1f else 0.6f; if (it.alpha != a) it.animate().alpha(a).setDuration(250).start() }
        if (shownArt !== PhoneMedia.art) {
            shownArt = PhoneMedia.art
            crossfade(findViewById(R.id.npArt), shownArt)
            crossfade(findViewById(R.id.npBackdrop), shownArt)
            Ui.paintAccent(root, Accent.forArt(this, shownArt))
        }
        // Loader on the art while a new track's art is still coming from the phone (gives up after 5 s).
        if (np.optString("title") != shownTitle) { shownTitle = np.optString("title"); titleAt = SystemClock.elapsedRealtime() }
        Ui.showLoader(findViewById(R.id.npLoader), alive && has && PhoneMedia.art == null && SystemClock.elapsedRealtime() - titleAt < 5000)
        val playing = PhoneMedia.playing
        if (shownPlaying != playing) {
            Ui.morph(findViewById<ImageButton>(R.id.npPlay), playing, shownPlaying != null)
            shownPlaying = playing
        }
        for (id in intArrayOf(R.id.npPrev, R.id.npPlay, R.id.npNext, R.id.npLibrary)) findViewById<View>(id).isEnabled = alive
        val dur = np.optLong("dur"); val pos = PhoneMedia.position()
        // Hands off while dragging and for 1.5 s after release, so a stale phone position cannot pull the thumb back.
        if (seeking || SystemClock.elapsedRealtime() - seekAt < 1500) return
        findViewById<Slider>(R.id.npSeek).apply {
            isEnabled = has && dur > 0
            valueTo = maxOf(dur, 1).toFloat(); value = pos.coerceIn(0, maxOf(dur, 1)).toFloat()
        }
        findViewById<TextView>(R.id.npPos).text = if (dur > 0) clock(pos) else ""
        findViewById<TextView>(R.id.npDur).text = if (dur > 0) "-" + clock(maxOf(0, dur - pos)) else ""
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        GpsService.startIfIdle(this); render()
    }

    private fun mockAllowed() = getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(
        android.app.AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED

    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (com.lazyneil.lazycar.media.Files.picked(this, rc, data)) render()
    }

    private fun missing() = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun render() {
        renderNowPlaying()
        // Just in time: the mic keeps the speaker copy in sync, needed only once a phone copy is arriving.
        if (!micAsked && StreamReceiver.kbps > 0 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micAsked = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }
        val miss = missing()
        findViewById<TextView>(R.id.setupBanner).apply {
            text = when {
                miss.isNotEmpty() -> "Precise location is off. Tap to allow"
                !com.lazyneil.lazycar.media.Files.has(this@DashboardActivity) -> "No music folder yet. Tap to pick one"
                !com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(this@DashboardActivity) && PhoneMedia.alive && PhoneMedia.np.optBoolean("spkOff") ->
                    "Tablet speaker is off for the phone's player. On the phone, tap the Tablet speaker line to share its audio"
                !mockAllowed() -> "Phone GPS for Maps: in Developer options, Select mock location app, pick CarApp. Tap to open"
                else -> ""
            }
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val off = Ui.attr(this, R.attr.carTextDim)
        val mode = GpsService.mode
        val live = GpsService.live()
        Ui.dot(findViewById(R.id.stGpsDot), when { live -> Ui.GREEN; mode != "idle" -> Ui.AMBER; else -> off }, live)
        val stream = StreamReceiver.running && SystemClock.elapsedRealtime() - StreamReceiver.lastAt <= 2000
        Ui.dot(findViewById(R.id.stStreamDot), when { stream -> Ui.GREEN; StreamReceiver.running -> Ui.AMBER; else -> off }, stream)
        findViewById<TextView>(R.id.speedText).text = if (live) "${(GpsService.speed * 3.6f).toInt()} km/h" else "-- km/h"
    }
}
