package com.lazyneil.lazycar

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var p: Prefs

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Swipe left anywhere on the screen opens Settings (same target as the cog button).
    // Fed via dispatchTouchEvent so it never consumes events; scrolling, row taps and the
    // tiles keep working.
    private val swipeLeft by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (dx < -dp(100) && -dx > 2 * kotlin.math.abs(e2.y - e1.y) && vx < 0) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeLeft.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        if (Role.isTablet(this)) {   // tablet home is the camera + GPS dashboard
            super.onCreate(savedInstanceState)
            startActivity(Intent(this, DashboardActivity::class.java)
                .putExtra("launcher", intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER))); finish(); return
        }
        Role.applyComponents(this)
        p = Prefs(this)
        Ui.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val scroll = findViewById<View>(R.id.scroll)
        val go = findViewById<MaterialButton>(R.id.goButton)
        Ui.edge(this, scroll, 0, dp(16))   // backdrop stays full window
        // Required first; notifications are optional and asked on their own, once, after these.
        val want = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) ActivityCompat.requestPermissions(this, want.toTypedArray(), 1) else askNotifications()
        if (savedInstanceState == null) opened()   // a recreate (accent change) is not an open

        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.debugBtn).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
        Ui.paintAccent(findViewById(R.id.root), Accent.now(this))
        Ui.press(go, findViewById(R.id.settingsBtn), findViewById(R.id.debugBtn))
        go.setOnClickListener { startActivity(Intent(this, GoActivity::class.java)) }   // GoActivity derives GO vs Shut down
    }

    private var stateReceiver: BroadcastReceiver? = null

    // Re-render GO/STOP/spinner the moment GoAction changes state while we are open.
    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { refreshGoButton() }
        }
        ContextCompat.registerReceiver(this, stateReceiver,
            IntentFilter(GoAction.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        stateReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        stateReceiver = null
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // Back from GoActivity (Shut down) is a resume, not an open: nothing restarts after a Shut down.
        if (!p.shutDown) LinkService.wake(this)
        refreshSetup()
        handler.post(tick)
        refreshGoButton()
        GoAction.deriveState(applicationContext, p) { refreshGoButton() }   // match reality after reboot
    }

    override fun onPause() {
        super.onPause()
        if (!::p.isInitialized) return
        handler.removeCallbacks(tick)
    }

    private fun refreshGoButton() {
        val btn = findViewById<MaterialButton>(R.id.goButton)
        val accent = Accent.now(this)
        val onAccent = Accent.onColor(accent)
        val busy = ContextCompat.getColor(this, R.color.stateBusy)
        val secondary = ContextCompat.getColor(this, R.color.secondaryText)
        // Fill and icon colors fade to the new state in 200 ms.
        fun paint(bg: Int, fg: Int) {
            val from = btn.backgroundTintList?.defaultColor ?: bg; val fromFg = btn.iconTint?.defaultColor ?: fg
            btn.setTextColor(fg)
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 200; addUpdateListener { a ->
                val f = a.animatedFraction; val ev = android.animation.ArgbEvaluator()
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(ev.evaluate(f, from, bg) as Int)
                if (btn.icon != null && btn.text.isEmpty()) btn.iconTint = android.content.res.ColorStateList.valueOf(ev.evaluate(f, fromFg, fg) as Int)
            } }.start()
        }
        btn.isEnabled = true; btn.icon = null
        fun busySpinner() {
            btn.isEnabled = false; paint(ContextCompat.getColor(this, R.color.card), secondary)
            val spec = com.google.android.material.progressindicator.CircularProgressIndicatorSpec(this, null)
            spec.indicatorSize = dp(24); spec.trackThickness = dp(3); spec.indicatorColors = intArrayOf(secondary)
            btn.icon = com.google.android.material.progressindicator.IndeterminateDrawable.createCircularDrawable(this, spec)
            btn.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; btn.iconSize = dp(24); btn.iconPadding = dp(12)
        }
        val hint = findViewById<TextView>(R.id.goHint); val sub = findViewById<TextView>(R.id.goSub)
        val big = btn.layoutParams.height > dp(120)   // round hero: spinner replaces the label
        // GO: solid accent, dark label, accent glow.
        val hero = findViewById<View>(R.id.hero)
        val size = maxOf(dp(160), minOf(dp(200), (resources.displayMetrics.widthPixels * 0.6f).toInt(), (hero.height * 0.85f).toInt().takeIf { it > 0 } ?: dp(200)))
        if (hero.height == 0) hero.post { refreshGoButton() }
        if (btn.layoutParams.width != size) { btn.layoutParams = btn.layoutParams.apply { width = size; height = size }; btn.cornerRadius = size / 2
            findViewById<View>(R.id.goGlow).layoutParams = findViewById<View>(R.id.goGlow).layoutParams.apply { width = size * 4 / 3; height = size * 4 / 3 } }
        // Accent glow behind GO.
        findViewById<View>(R.id.goGlow).background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT; gradientRadius = size / 3f
            colors = intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x40), androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x14), 0)
        }
        val state = GoAction.effectiveState(p)
        btn.strokeWidth = 0; btn.elevation = dp(8).toFloat()
        // OFF: the same button at 40% (no glow); ON: full opacity with the soft glow. 200 ms fade.
        val off = state == GoAction.IDLE
        // OFF: neutral grey button with a thin ring and a grey icon, no glow. ON: solid accent with the soft glow.
        btn.strokeWidth = if (off) dp(2) else 0; btn.strokeColor = android.content.res.ColorStateList.valueOf(0x1FFFFFFF)
        if (off) btn.elevation = 0f
        findViewById<View>(R.id.goGlow).animate().alpha(if (off) 0f else 1f).setDuration(200).start()
        btn.outlineSpotShadowColor = accent; btn.outlineAmbientShadowColor = accent
        when (state) {
            GoAction.ON -> { power(btn, onAccent, "Shut down CarApp"); paint(accent, onAccent)
                hint.text = "Running"; sub.text = "Tap to shut down" }
            GoAction.STARTING -> { btn.text = if (big) "" else "STARTING"; busySpinner(); hint.text = "Starting"; sub.text = "Hotspot, data, location, tablet" }
            GoAction.STOPPING -> { btn.text = if (big) "" else "STOPPING"; busySpinner(); hint.text = "Stopping"; sub.text = "Restoring your settings" }
            else -> { power(btn, 0x99FFFFFF.toInt(), "Start CarApp"); paint(ContextCompat.getColor(this, R.color.card), 0x99FFFFFF.toInt()); hint.text = "Ready"; sub.text = "Tap to start the car setup" }
        }
        if (big && (state == GoAction.STARTING || state == GoAction.STOPPING)) { btn.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; btn.iconPadding = 0; btn.iconSize = dp(48) }
    }

    /** Power icon in place of the GO / ON label, tinted like the label was. */
    private fun power(b: MaterialButton, tint: Int, desc: String) {
        b.text = ""; b.icon = ContextCompat.getDrawable(this, R.drawable.ic_power); b.iconTint = android.content.res.ColorStateList.valueOf(tint)
        b.iconSize = dp(72); b.iconPadding = 0; b.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; b.contentDescription = desc
    }

    // Anything missing shows as one calm line on the home screen; tapping it opens the right screen. No pop-ups.
    private fun refreshSetup() {
        fun perm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        val item: Pair<String, () -> Unit>? = when {
            !perm(Manifest.permission.ACCESS_FINE_LOCATION) -> "Precise location is off. Tap to allow" to {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1) }
            Ui.offerHelper(this) -> "Let CarApp turn on hotspot, mobile data and location for you: tap, then Downloaded apps, CarApp helper, turn on" to { Ui.openHelper(this) }
            else -> null
        }
        val card = findViewById<View>(R.id.setupCard)
        card.visibility = if (item == null) View.GONE else View.VISIBLE
        item ?: return
        findViewById<TextView>(R.id.setupTitle).text = item.first
        findViewById<View>(R.id.setupBody).visibility = View.GONE; findViewById<View>(R.id.setupBtn).visibility = View.GONE
        card.setOnClickListener { item.second() }
    }

    /** The user opened the app (launcher, back from another app): that ends a Shut down. GPS starts with GO. */
    private fun opened() { if (android.os.SystemClock.uptimeMillis() < GoAction.quietUntil) return; p.shutDown = false; LinkService.wake(this) }
    override fun onRestart() { super.onRestart(); opened() }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); opened() }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (GoAction.effectiveState(p) == GoAction.ON) com.lazyneil.lazycar.gps.GpsService.startIfIdle(this)
        if (rc == 1) askNotifications()
    }

    private fun askNotifications() {
        val sp = getSharedPreferences("lazycar", MODE_PRIVATE)
        if (sp.getBoolean("notifAsked", false) || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        sp.edit().putBoolean("notifAsked", true).apply()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { renderGps(); refreshSetup(); handler.postDelayed(this, 1000) } }

    /** One status tile: states are Off / Waiting / Connected (or a plain on/off word). */
    private fun tile(id: Int, icon: Int, title: String, state: String, color: Int, live: Boolean) {
        val t = findViewById<View>(id)
        t.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
        t.findViewById<TextView>(R.id.tileTitle).text = title
        t.findViewById<TextView>(R.id.tileSub).text = state
        Ui.dot(t.findViewById(R.id.tileDot), color, live)
    }

    /** Status tiles, once a second. */
    private fun renderGps() {
        val off = Ui.attr(this, R.attr.carTextDim)
        val g = com.lazyneil.lazycar.gps.GpsService
        val live = g.live()   // a stationary indoor fused fix comes every 20 s or more; it is still a fix
        tile(R.id.tileGps, R.drawable.ic_pin, "GPS", when { g.mode == "idle" -> "Off"; live -> "Connected"; else -> "Searching" },
            when { live -> Ui.GREEN; g.mode != "idle" -> Ui.AMBER; else -> off }, live)
        val data = GoAction.dataOn(this)
        tile(R.id.tileBt, R.drawable.ic_share, "Data", if (data) "On" else "Off", if (data) Ui.GREEN else off, false)
        val tab = LinkService.clients > 0; val waiting = LinkService.bound
        val ap = GoAction.hotspotOn(this)
        tile(R.id.tileHotspot, R.drawable.ic_share, "Hotspot", when { ap == true -> "On"; ap == false -> "Off"; tab -> "Tablet joined"; else -> "Unknown" },
            if (tab || ap == true) Ui.GREEN else off, false)
        tile(R.id.tileTablet, R.drawable.ic_tablet, "Tablet", when { tab -> "Connected"; waiting -> "Searching"; else -> "Off" },
            when { tab -> Ui.GREEN; waiting -> Ui.AMBER; else -> off }, tab)
    }
}
