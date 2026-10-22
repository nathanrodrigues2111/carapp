package com.lazyneil.lazycar

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var p: Prefs

    // Swipe right anywhere goes back to the main screen (same as the toolbar back button).
    // Fed via dispatchTouchEvent so it never consumes events. ponytail: a fast rightward flick
    // that starts on a control can also trigger it; add a region guard if that proves annoying.
    private val swipeBack by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (dx > 100 * resources.displayMetrics.density && dx > 2 * kotlin.math.abs(e2.y - e1.y) && vx > 0) {
                    finish()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeBack.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        p = Prefs(this)
        Ui.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (keepScroll >= 0) findViewById<android.widget.ScrollView>(R.id.settingsScroll).let { sv ->
            val y = keepScroll; keepScroll = -1; sv.post { sv.scrollTo(0, y) } }

        Ui.edge(this, findViewById(R.id.root))
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topTitle).text = "Settings"
        bindJbl()
        bindDrive()
        buildNav()
        // A tap anywhere on a switch row flips its switch.
        fun rows(v: View) { if (v is android.view.ViewGroup) for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            // Prefs are the truth: a restored stale checked state (recreate, process restart) would fire the listener and save it.
            if (c is MaterialSwitch) c.isSaveEnabled = false
            if (c is MaterialSwitch && v.id != View.NO_ID) v.setOnClickListener { c.toggle() } else rows(c) } }
        rows(findViewById(R.id.root))
        findViewById<View>(R.id.camOpen).setOnClickListener {
            startActivity(Intent(this, com.lazyneil.lazycar.rear.RearActivity::class.java))
        }

        bindDevice()
        findViewById<View>(R.id.shutRow).setOnClickListener {
            startForegroundService(Intent(this, GoService::class.java).putExtra("action", "stop")); toast("Shutting down")
        }
        findViewById<View>(R.id.helpRow).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<View>(R.id.debugRow).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        val v = try { packageManager.getPackageInfo(packageName, 0) } catch (e: Exception) { null }
        findViewById<TextView>(R.id.versionText).text =
            if (v != null) "CarApp v${v.versionName} (${v.longVersionCode})" else "CarApp"
    }

    override fun onResume() {
        super.onResume()
        // A change made on the other device shows at once, at the same scroll position.
        SettingsSync.onRemote = { keepScroll = findViewById<View>(R.id.settingsScroll).scrollY; recreate() }
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        SettingsSync.onRemote = null
        handler.removeCallbacks(tick)
        val ssid = findViewById<TextInputEditText>(R.id.ssidField).text.toString().trim()
        val pass = findViewById<TextInputEditText>(R.id.hotspotPassField).text.toString()
        if (Role.isTablet(this) && ssid.isNotEmpty() && (ssid != p.hotspotSsid || pass != p.hotspotPass)) { p.hotspotSsid = ssid; p.hotspotPass = pass; Drive.join(this) }
    }

    private fun bindDevice() {
        val tablet = Role.isTablet(this)
        // Camera, hotspot join and car stereo are tablet only.
        intArrayOf(R.id.camSection, R.id.camSectionHeader, R.id.carSection, R.id.carSectionHeader)
            .forEach { findViewById<View>(it).visibility = if (tablet) View.VISIBLE else View.GONE }
        findViewById<TextInputEditText>(R.id.ssidField).setText(p.hotspotSsid)
        findViewById<TextInputEditText>(R.id.hotspotPassField).setText(p.hotspotPass)
        findViewById<View>(R.id.camRow).apply {
            visibility = if (tablet) View.VISIBLE else View.GONE
            setOnClickListener { startActivity(Intent(this@SettingsActivity, com.lazyneil.lazycar.rear.RearSettingsActivity::class.java)) }
        }
        val names = mapOf(Role.AUTO to "Auto", Role.PHONE to "Phone", Role.TABLET to "Tablet")
        findViewById<TextView>(R.id.roleValue).text = names[Role.override(this)] +
            if (Role.override(this) == Role.AUTO) " (" + names[Role.detected()] + ")" else ""
        findViewById<View>(R.id.roleRow).setOnClickListener {
            val keys = names.keys.toList()
            MaterialAlertDialogBuilder(this)
                .setTitle("Device role")
                .setSingleChoiceItems(keys.map { names[it] }.toTypedArray(), keys.indexOf(Role.override(this))) { d, w ->
                    d.dismiss()
                    if (keys[w] == Role.override(this)) return@setSingleChoiceItems
                    Role.set(this, keys[w])
                    // Back to a fresh home for the new role.
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                .show()
        }
        val gps = com.lazyneil.lazycar.gps.GpsService
        val m = gps.autoMode(this)
        val target = if (tablet) "receive" else "send"
        findViewById<MaterialSwitch>(R.id.gpsSwitch).apply {
            this@SettingsActivity.findViewById<TextView>(R.id.gpsTitle).text = if (tablet) "Phone GPS" else "Share GPS"
            isChecked = m != "stop"
            setOnCheckedChangeListener { _, on -> gps.launch(this@SettingsActivity, if (on) target else "stop") }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---- Car stereo: the Bluetooth device the tablet connects on GO ----
    private fun bindJbl() {
        if (!Role.isTablet(this)) { intArrayOf(R.id.spkHeader, R.id.spkCard).forEach { findViewById<View>(it).visibility = View.GONE }; return }
        val r = BtDevices.load(this)
        val icon = findViewById<ImageView>(R.id.outJblIcon)
        fun show() {
            val d = r.audio.firstOrNull { it.first == p.mac1 }
            findViewById<TextView>(R.id.outJblName).text = d?.second ?: p.name1.ifEmpty { "None, GO only turns Bluetooth on" }
            icon.setImageResource(d?.third ?: BtDevices.ICON)
        }
        show()
        findViewById<TextView>(R.id.btNote).text = r.note ?: ""
        findViewById<View>(R.id.btBox).visibility = if (r.note != null || r.canEnable) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btEnable).apply {
            visibility = if (r.canEnable) View.VISIBLE else View.GONE
            @Suppress("DEPRECATION") setOnClickListener { Ui.openSettings(this@SettingsActivity, Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        findViewById<View>(R.id.outJblRow).setOnClickListener {
            if (r.audio.isEmpty()) { toast(r.note ?: "No paired devices available"); return@setOnClickListener }
            MaterialAlertDialogBuilder(this).setTitle("Car stereo")
                .setSingleChoiceItems(r.audio.map { it.second }.toTypedArray(), r.audio.indexOfFirst { it.first == p.mac1 }) { d, w ->
                    p.mac1 = r.audio[w].first; p.name1 = r.audio[w].second; show(); d.dismiss() }
                .setNeutralButton("Clear") { _, _ -> p.mac1 = ""; p.name1 = ""; show() }
                .show()
        }
    }

    // ---- Drive: the saved launcher pair GO opens (tablet) ----
    private fun bindDrive() {
        val tablet = Role.isTablet(this)
        intArrayOf(R.id.driveHeader, R.id.driveCard).forEach { findViewById<View>(it).visibility = if (tablet) View.VISIBLE else View.GONE }
        if (!tablet) return
        fun show() {
            findViewById<TextView>(R.id.pairValue).text = p.goPair.let { if (it.isEmpty()) "None" else SplitService.label(it) }
            findViewById<TextView>(R.id.pairSub).text = when {
                !Ui.helperOn(this) -> "Needs CarApp helper on in Accessibility"
                p.pairs.isEmpty() -> "Save a pair on the home screen, then show the home screen once"
                else -> "A pair you saved on the home screen"
            }
        }
        show()
        findViewById<View>(R.id.pairRow).setOnClickListener {
            if (!Ui.helperOn(this)) { Ui.openHelper(this); return@setOnClickListener }
            val opts = listOf("") + p.pairs.sorted()
            MaterialAlertDialogBuilder(this).setTitle("Open on GO")
                .setSingleChoiceItems(opts.map { if (it.isEmpty()) "None" else SplitService.label(it) }.toTypedArray(), opts.indexOf(p.goPair)) { d, w ->
                    p.goPair = opts[w]; show(); d.dismiss() }
                .show()
        }
    }

    /** Tablet, wide: section list on the left, tap scrolls the right pane to that section. */
    private fun buildNav() {
        if (!Role.isTablet(this) || resources.configuration.screenWidthDp < 840) return
        val nav = findViewById<android.widget.LinearLayout>(R.id.navList)
        val scroll = findViewById<android.widget.ScrollView>(R.id.settingsScroll)
        val content = scroll.getChildAt(0) as android.view.ViewGroup
        nav.visibility = View.VISIBLE
        val icons = mapOf("Car stereo" to R.drawable.ic_speaker, "Display" to R.drawable.ic_theme_light, "GPS" to R.drawable.ic_pin, "Drive" to R.drawable.ic_car,
            "Camera" to R.drawable.ic_record, "Connection" to R.drawable.ic_tablet, "Advanced" to R.drawable.ic_settings)
        val on = Ui.attr(this, R.attr.carText); val dim = Ui.attr(this, R.attr.carTextDim)
        var picked: TextView? = null
        fun select(v: TextView) {
            picked = v; val accent = Accent.now(this)
            for (j in 0 until nav.childCount) (nav.getChildAt(j) as TextView).let { t -> val sel = t === v
                t.isActivated = sel; t.setTextColor(if (sel) on else dim)
                t.compoundDrawablesRelative[0]?.setTint(if (sel) accent else dim) }
        }
        for (i in 0 until content.childCount - 1) {
            val h = content.getChildAt(i) as? TextView ?: continue
            if (content.getChildAt(i + 1) !is android.widget.LinearLayout || h.visibility != View.VISIBLE || h.id == R.id.versionText) continue
            nav.addView(TextView(this).apply {
                text = h.text; setTextAppearance(R.style.T_Title); typeface = android.graphics.Typeface.DEFAULT
                gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0); compoundDrawablePadding = dp(16)
                setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(icons[h.text.toString()] ?: R.drawable.ic_settings)?.mutate(), null, null, null)
                setBackgroundResource(R.drawable.bg_nav_item)
                setOnClickListener { select(this); scroll.smoothScrollTo(0, h.top) }
            }, android.widget.LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(4) })
        }
        (nav.getChildAt(0) as? TextView)?.let { select(it) }
        nav.tag = Runnable { picked?.let { select(it) } }   // repainted by Ui.paintAccent when the accent changes
    }

    // ---- live GPS and camera status (moved here from the tablet home) ----
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { renderLive(); handler.postDelayed(this, 1000) } }

    private var shownAccent = 0
    private fun renderLive() {
        Accent.now(this).let { if (it != shownAccent) { shownAccent = it; Ui.paintAccent(findViewById(R.id.root), it) } }
        val g = com.lazyneil.lazycar.gps.GpsService
        val live = g.live()
        Ui.status(findViewById(R.id.gpsLive), if (Role.isTablet(this)) g.status() else "Sends this phone's location to the tablet", when { live -> Ui.GREEN; g.mode != "idle" -> Ui.AMBER; else -> Ui.attr(this, R.attr.carTextDim) })
        if (!Role.isTablet(this)) return
        val cam = getSystemService(android.hardware.usb.UsbManager::class.java).deviceList.values
            .firstOrNull { com.lazyneil.lazycar.rear.UsbDevices.isVideoDevice(it) }
        Ui.status(findViewById(R.id.camLive), if (cam != null) "Connected" else "Ready, opens in reverse", if (cam != null) Ui.GREEN else Ui.attr(this, R.attr.carTextDim))
    }

    private companion object { var keepScroll = -1 }   // survives the recreate after a remote change
}
