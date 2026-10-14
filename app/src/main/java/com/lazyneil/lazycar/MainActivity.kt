package com.lazyneil.lazycar

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var p: Prefs
    private var players = listOf<Triple<String, String, Drawable?>>()   // pkg,label,icon

    private var selPlayer = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Swipe left anywhere on the screen opens Settings (same target as the cog button).
    // Fed via dispatchTouchEvent so it never consumes events; scrolling, row taps and the
    // volume slider keep working. ponytail: a fast leftward flick that starts on the volume
    // slider can also trigger it; add a slider-region guard if that proves annoying.
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
            startActivity(Intent(this, DashboardActivity::class.java)); finish(); return
        }
        Role.applyComponents(this)
        p = Prefs(this)
        Ui.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selPlayer = p.playerPkg

        val scroll = findViewById<View>(R.id.scroll)
        val go = findViewById<MaterialButton>(R.id.goButton)
        val root = findViewById<View>(R.id.root)
        Ui.edge(this, scroll, 0, dp(16))   // backdrop stays full window
        val want = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) ActivityCompat.requestPermissions(this, want.toTypedArray(), 1)
        com.lazyneil.lazycar.gps.GpsService.startIfIdle(this)
        if (Role.isTablet(this)) com.lazyneil.lazycar.audio.StreamReceiver.sync(this)

        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.debugBtn).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
        findViewById<View>(R.id.stripPlay).setOnClickListener { session()?.let { c ->
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play() } }
        findViewById<View>(R.id.npNext).setOnClickListener { session()?.transportControls?.skipToNext() }
        findViewById<View>(R.id.npPrev)?.setOnClickListener { session()?.transportControls?.skipToPrevious() }
        Ui.paintAccent(root, Accent.forArt(this, null))
        Ui.blur(findViewById(R.id.npBackdrop))
        Ui.press(go, findViewById(R.id.stripPlay), findViewById(R.id.npNext), findViewById(R.id.npPrev),
            findViewById(R.id.settingsBtn), findViewById(R.id.debugBtn))

        buildPlayers()

        go.setOnClickListener {
            if (GoAction.effectiveState(p) == GoAction.IDLE) save()   // GO saves the form; STOP just runs
            startActivity(Intent(this, GoActivity::class.java))       // GoActivity derives GO vs STOP
        }
    }

    private var btReceiver: BroadcastReceiver? = null
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
        refreshSetup()
        handler.post(tick)
        refreshGoButton()
        GoAction.deriveState(applicationContext, p) { refreshGoButton() }   // match reality after reboot
        if (btReceiver == null) {
            btReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { refreshGoButton() }
            }
            registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::p.isInitialized) return
        handler.removeCallbacks(tick)
        save()
        btReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        btReceiver = null
    }

    private fun refreshGoButton() {
        val btn = findViewById<MaterialButton>(R.id.goButton)
        val accent = Accent.now(this)
        Ui.paintAccent(findViewById(R.id.root), accent)
        val onAccent = Accent.onColor(accent)
        val busy = ContextCompat.getColor(this, R.color.stateBusy)
        val secondary = ContextCompat.getColor(this, R.color.secondaryText)
        fun paint(bg: Int, fg: Int) {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(bg); btn.setTextColor(fg)
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
        findViewById<ImageButton>(R.id.stripPlay).backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.attr(this, R.attr.carText))
        findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.npProgress).setIndicatorColor(accent)
        val big = btn.layoutParams.height > dp(120)   // round hero: spinner replaces the label
        // GO = the tablet play button, bigger: solid live accent, dark label, accent glow, same press.
        val hero = findViewById<View>(R.id.hero)
        val size = maxOf(dp(160), minOf(dp(200), (resources.displayMetrics.widthPixels * 0.6f).toInt(), (hero.height * 0.85f).toInt().takeIf { it > 0 } ?: dp(200)))
        if (hero.height == 0) hero.post { refreshGoButton() }
        if (btn.layoutParams.width != size) { btn.layoutParams = btn.layoutParams.apply { width = size; height = size }; btn.cornerRadius = size / 2
            findViewById<View>(R.id.goGlow).layoutParams = findViewById<View>(R.id.goGlow).layoutParams.apply { width = size * 4 / 3; height = size * 4 / 3 } }
        // Accent glow behind GO, the strip's play button and progress, all from the art accent.
        findViewById<View>(R.id.goGlow).background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT; gradientRadius = size / 3f
            colors = intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x40), androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x14), 0)
        }
        val state = GoAction.effectiveState(p)
        btn.strokeWidth = 0; btn.elevation = dp(8).toFloat()
        // OFF: the same button at 40% (no glow); ON: full opacity with the soft glow. 200 ms fade.
        val off = state == GoAction.IDLE
        btn.animate().alpha(if (off) 0.4f else 1f).setDuration(200).start()
        findViewById<View>(R.id.goGlow).animate().alpha(if (off) 0f else 1f).setDuration(200).start()
        btn.outlineSpotShadowColor = accent; btn.outlineAmbientShadowColor = accent
        when (state) {
            GoAction.ON -> { power(btn, onAccent, "Stop CarApp"); paint(accent, onAccent)
                hint.text = "Running"; sub.text = "Tap to stop" }
            GoAction.STARTING -> { btn.text = if (big) "" else "STARTING"; busySpinner(); hint.text = "Starting"; sub.text = "Connecting the speaker and the tablet" }
            GoAction.STOPPING -> { btn.text = if (big) "" else "STOPPING"; busySpinner(); hint.text = "Stopping"; sub.text = "Restoring your settings" }
            else -> { power(btn, onAccent, "Start CarApp"); paint(accent, onAccent); hint.text = "Ready"; sub.text = "Tap to start the car setup" }
        }
        if (big && (state == GoAction.STARTING || state == GoAction.STOPPING)) { btn.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; btn.iconPadding = 0; btn.iconSize = dp(48) }
    }

    /** Power icon in place of the GO / ON label, tinted like the label was. */
    private fun power(b: MaterialButton, tint: Int, desc: String) {
        b.text = ""; b.icon = ContextCompat.getDrawable(this, R.drawable.ic_power); b.iconTint = android.content.res.ColorStateList.valueOf(tint)
        b.iconSize = dp(72); b.iconPadding = 0; b.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; b.contentDescription = desc
    }

    private fun refreshSetup() {
        val a11y = accessibilityEnabled()
        val btOk = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

        val card = findViewById<View>(R.id.setupCard)
        val title = findViewById<TextView>(R.id.setupTitle)
        val body = findViewById<TextView>(R.id.setupBody)
        val btn = findViewById<MaterialButton>(R.id.setupBtn)
        when {
            !a11y -> {
                title.text = "Setup needed"
                body.text = "1. Tap Open settings\n" +
                    "2. Find CarApp under Installed apps / Downloaded services\n" +
                    "3. Turn it on, then tap Allow"
                btn.text = "Open settings"
                btn.setOnClickListener { openAccessibilitySettings() }
                card.visibility = View.VISIBLE
            }
            !btOk -> {
                title.text = "Bluetooth permission needed"
                body.text = "1. Tap Grant permission\n" +
                    "2. Allow Nearby devices\n" +
                    "3. Your paired speakers appear under OUTPUT"
                btn.text = "Grant permission"
                btn.setOnClickListener {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
                }
                card.visibility = View.VISIBLE
            }
            !com.lazyneil.lazycar.media.MediaListener.granted(this) -> {
                title.text = "Tablet controls need notification access"
                body.text = "1. Tap Open settings\n" +
                    "2. Turn on CarApp media control\n" +
                    "3. The tablet can then show and control what plays here"
                btn.text = "Open settings"
                btn.setOnClickListener { startActivity(com.lazyneil.lazycar.media.MediaListener.settingsIntent()) }
                card.visibility = View.VISIBLE
            }
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                title.text = "Music library permission"
                body.text = "Lets the tablet browse the songs stored on this phone"
                btn.text = "Grant permission"
                btn.setOnClickListener { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_AUDIO), 2) }
                card.visibility = View.VISIBLE
            }
            else -> card.visibility = View.GONE
        }
    }

    private fun openAccessibilitySettings() {
        val cn = ComponentName(this, TapService::class.java).flattenToString()
        try {
            val args = Bundle().apply { putString(":settings:fragment_args_key", cn) }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", cn)
                .putExtra(":settings:show_fragment_args", args))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun accessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(':').any { it.substringBefore('/') == packageName }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        com.lazyneil.lazycar.gps.GpsService.startIfIdle(this)
    }

    // ---- players ----
    private fun buildPlayers() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        players = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .map { pkg ->
                Triple(pkg,
                    (try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }) +
                        if (pkg == "com.spotify.music") " (tablet speaker unsupported, app blocks audio copy)" else "",
                    try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null })
            }
            .sortedBy { it.second.lowercase() }
        renderPlayer()
        findViewById<View>(R.id.playerRow).setOnClickListener { showPlayerPicker() }
    }

    private fun renderPlayer() {
        val cur = players.firstOrNull { it.first == selPlayer }
        findViewById<ImageView>(R.id.playerIcon).setImageDrawable(cur?.third)
        playerLabel = cur?.second?.substringBefore(" (") ?: "Choose a player"
        findViewById<View>(R.id.playerRow).contentDescription = "Player: $playerLabel. Tap to change"
    }

    private fun showPlayerPicker() {
        if (players.isEmpty()) { toast("No music players found"); return }
        val adapter = object : ArrayAdapter<Triple<String, String, Drawable?>>(this, 0, players) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val row = cv as? TextView ?: TextView(this@MainActivity).apply {
                    val d = resources.displayMetrics.density
                    setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
                    textSize = 16f
                    compoundDrawablePadding = (16 * d).toInt()
                }
                val item = players[pos]
                row.text = item.second
                val sz = (36 * resources.displayMetrics.density).toInt()
                item.third?.setBounds(0, 0, sz, sz)
                row.setCompoundDrawables(item.third, null, null, null)
                return row
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Player")
            .setAdapter(adapter) { d, which -> selPlayer = players[which].first; renderPlayer(); save(); d.dismiss() }
            .show()
    }

    private fun save() {
        p.playerPkg = selPlayer
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---- now playing strip + GPS chip (phone side, straight from the media session) ----
    private var playerLabel = ""
    private var shownArt: android.graphics.Bitmap? = null
    private var shownPlaying: Boolean? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { renderStrip(); renderGps(); handler.postDelayed(this, 1000) }
    }

    private fun session(): MediaController? = try {
        getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(this, com.lazyneil.lazycar.media.MediaListener::class.java))
            .let { l -> l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull() }
    } catch (e: SecurityException) { null }

    private fun renderStrip() {
        val c = session(); val m = c?.metadata
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        Ui.swapText(findViewById(R.id.npTitle), title.ifEmpty { playerLabel })
        Ui.swapText(findViewById(R.id.npArtist), if (title.isEmpty()) "Nothing playing"
            else listOf(m?.getString(MediaMetadata.METADATA_KEY_ARTIST), m?.getString(MediaMetadata.METADATA_KEY_ALBUM))
                .filter { !it.isNullOrEmpty() }.joinToString(" · "))
        val art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art?.sameAs(shownArt) != true && !(art == null && shownArt == null)) {
            shownArt = art; Accent.art = art
            Ui.swapArt(findViewById(R.id.npArt), art)
            findViewById<ImageView>(R.id.npBackdrop).setImageBitmap(art)
            refreshGoButton()   // GO, its glow and the progress follow the art accent
        }
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (shownPlaying != playing) {
            Ui.morph(findViewById(R.id.stripPlay), playing, shownPlaying != null)
            shownPlaying = playing
        }
        for (id in intArrayOf(R.id.stripPlay, R.id.npNext, R.id.npPrev)) findViewById<View>(id)?.isEnabled = c != null
    }

    /** One status tile: states are Off / Waiting / Connected (or a plain on/off word). */
    private fun tile(id: Int, icon: Int, title: String, state: String, color: Int, live: Boolean) {
        val t = findViewById<View>(id)
        t.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
        t.findViewById<TextView>(R.id.tileTitle).text = title
        t.findViewById<TextView>(R.id.tileSub).text = state
        Ui.dot(t.findViewById(R.id.tileDot), color, live)
    }

    /** Status tiles and the strip's progress line, once a second. */
    private fun renderGps() {
        val off = Ui.attr(this, R.attr.carTextDim)
        val g = com.lazyneil.lazycar.gps.GpsService
        val age = if (g.lastAt == 0L) -1 else (android.os.SystemClock.elapsedRealtime() - g.lastAt) / 1000
        val live = g.mode != "idle" && age in 0..5
        tile(R.id.tileGps, R.drawable.ic_pin, "GPS", when { g.mode == "idle" -> "Off"; live -> "Connected"; else -> "Searching" },
            when { live -> Ui.GREEN; g.mode != "idle" -> Ui.AMBER; else -> off }, live)
        val bt = getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        tile(R.id.tileBt, R.drawable.ic_speaker, "Speaker", if (bt != null) "Connected" else "Off", if (bt != null) Ui.GREEN else off, false)
        val on = GoAction.effectiveState(p) == GoAction.ON
        tile(R.id.tileHotspot, R.drawable.ic_share, "Hotspot", when { !p.hotspot -> "Off"; on -> "On"; else -> "Off" }, if (p.hotspot && on) Ui.GREEN else off, false)
        // Same source as Debug's "Media control for the tablet" row: the link's client count.
        val ml = com.lazyneil.lazycar.media.MediaListener
        val tab = ml.clients > 0; val waiting = ml.bound
        tile(R.id.tileTablet, R.drawable.ic_tablet, "Tablet", when { tab -> "Connected"; waiting -> "Searching"; else -> "Off" },
            when { tab -> Ui.GREEN; waiting -> Ui.AMBER; else -> off }, tab)
        val st = session()?.playbackState; val dur = session()?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pos = if (st == null) 0L else st.position + if (st.state == PlaybackState.STATE_PLAYING)
            ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else 0L
        findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.npProgress).progress =
            if (dur > 0) (pos * 1000 / dur).toInt().coerceIn(0, 1000) else 0
    }
}
