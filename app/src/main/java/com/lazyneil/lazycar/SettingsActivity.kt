package com.lazyneil.lazycar

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var p: Prefs
    private var selMac3 = ""; private var selName3 = ""
    private var allDevices = listOf<Triple<String, String, Int>>()

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
        selMac3 = p.mac3; selName3 = p.name3

        Ui.edge(this, findViewById(R.id.root))
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topTitle).text = "Settings"
        Ui.bindOutputs(this, findViewById(R.id.root))
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

        val extra = findViewById<View>(R.id.headunitExtra)
        findViewById<MaterialSwitch>(R.id.headunitSwitch).apply {
            isChecked = p.headunit
            extra.visibility = if (p.headunit) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on -> p.headunit = on; extra.visibility = if (on) View.VISIBLE else View.GONE }
        }
        val d = GoAction.detectHeadunit(this)
        findViewById<TextView>(R.id.headunitDetected).text =
            "Detected: " + (d?.second ?: "none")
        findViewById<TextInputEditText>(R.id.ipField).setText(p.headunitIp)

        bindDevice()
        bindLaunch()
        buildHeadunitPicker()
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
        p.headunitIp = findViewById<TextInputEditText>(R.id.ipField).text.toString().trim()
        val ssid = findViewById<TextInputEditText>(R.id.ssidField).text.toString().trim()
        val pass = findViewById<TextInputEditText>(R.id.hotspotPassField).text.toString()
        if (Role.isTablet(this) && ssid.isNotEmpty() && (ssid != p.hotspotSsid || pass != p.hotspotPass)) { p.hotspotSsid = ssid; p.hotspotPass = pass; Drive.join(this) }
    }

    private fun bindDevice() {
        val tablet = Role.isTablet(this)
        // Phone-only car audio controls are hidden on the tablet, the camera row on the phone.
        intArrayOf(R.id.rowHeadunit, R.id.headunitExtra)
            .forEach { if (tablet) findViewById<View>(it).visibility = View.GONE }
        // The camera section is tablet only.
        intArrayOf(R.id.camSection, R.id.camSectionHeader).forEach { findViewById<View>(it).visibility = if (tablet) View.VISIBLE else View.GONE }
        if (!SHOW_HEADUNIT) intArrayOf(R.id.rowHeadunit, R.id.headunitExtra)
            .forEach { findViewById<View>(it).visibility = View.GONE }
        intArrayOf(R.id.ssidBox, R.id.hotspotPassBox).forEach { (findViewById<View>(it).parent as View).visibility = if (tablet) View.VISIBLE else View.GONE }
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
        val anchor = findViewById<View>(R.id.audioAnchor)
        com.lazyneil.lazycar.audio.TabletSpeaker.addSettings(this, anchor)
        buildPlayFrom(anchor)
    }

    // ---- Audio: where the music plays from. Tablet mode swaps the speaker switch for the player choice; stored values stay. ----
    private fun buildPlayFrom(anchor: View) {
        val parent = anchor.parent as android.view.ViewGroup
        val sp = getSharedPreferences("lazycar", MODE_PRIVATE)
        val fromTablet = com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(this)
        val modes = listOf("phone", "tablet")
        fun row() = android.widget.LinearLayout(this, null, 0, R.style.SetRow_Stack)
        fun text(style: Int, t: String) = TextView(this, null, 0, style).apply { text = t }
        val mode = row()
        mode.addView(text(R.style.SetTitle, "Play audio from")); mode.addView(text(R.style.SetSub, "Which device plays the music"))
        val seg = android.widget.LinearLayout(this)
        mode.addView(seg, android.widget.LinearLayout.LayoutParams(-1, resources.getDimensionPixelSize(R.dimen.seg_h)).apply { topMargin = dp(12) })
        Ui.segmented(seg, listOf("Phone", "Tablet"), if (fromTablet) 1 else 0) {
            if (modes[it] == (if (com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(this)) "tablet" else "phone")) return@segmented
            sp.edit().putString("play_from", modes[it]).apply()
            if (!Role.isTablet(this)) com.lazyneil.lazycar.Prefs(this).let { p -> if (GoAction.effectiveState(p) == GoAction.ON) {
                if (modes[it] == "phone") GoActivity.phoneDialogs(this)   // Bluetooth on + consent, like GO in Phone mode
                GoAction.handoff(this, p, modes[it] == "tablet") } }   // hand the speaker over while ON
            keepScroll = findViewById<View>(R.id.settingsScroll).scrollY; recreate()
        }
        var at = parent.indexOfChild(anchor) + 1
        // Added at the anchor, ahead of the sync slider TabletSpeaker put there.
        parent.addView(mode, at++)
        // Tablet mode: the phone stream switch is meaningless; its slider becomes the speaker copy level.
        findViewById<View>(R.id.outTabSwitch).visibility = if (fromTablet) View.GONE else View.VISIBLE
        if (!fromTablet) return
        findViewById<View>(R.id.outTabSlider).visibility = View.VISIBLE
        // The tablet always plays with CarApp's own player: no player choice.
    }

    // ---- launch on widget tap ----
    private val apps by lazy {
        val pm = packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()   // CarApp included: the Drive split pairs Maps with it
            .map { Triple(it, try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { it },
                try { pm.getApplicationIcon(it) } catch (e: Exception) { null }) }
            .sortedBy { it.second.lowercase() }
    }

    private fun bindLaunch() {
        fun render(iconId: Int, valueId: Int, pkg: String, prefix: String) {
            val app = apps.firstOrNull { it.first == pkg }
            findViewById<ImageView>(iconId).setImageDrawable(app?.third)
            findViewById<TextView>(valueId).text = prefix + (app?.second ?: "choose an app")
        }
        fun renderAll() {
            val mode = p.launchMode
            val split = mode == "split"
            render(R.id.launchAIcon, R.id.launchAValue, p.launchA, if (split) "Left: " else "")
            render(R.id.launchBIcon, R.id.launchBValue, p.launchB, "Right: ")
            findViewById<View>(R.id.launchApps).visibility = if (mode == "none") View.GONE else View.VISIBLE
            findViewById<View>(R.id.launchBRow).visibility = if (split) View.VISIBLE else View.GONE
            findViewById<View>(R.id.splitExtra).visibility = if (split) View.VISIBLE else View.GONE
        }
        fun pick(title: String, set: (String) -> Unit) {
            val adapter = object : android.widget.ArrayAdapter<Triple<String, String, android.graphics.drawable.Drawable?>>(this, 0, apps) {
                override fun getView(pos: Int, cv: View?, parent: android.view.ViewGroup): View {
                    val row = cv as? TextView ?: TextView(this@SettingsActivity).apply {
                        setPadding(dp(16), dp(14), dp(16), dp(14)); textSize = 16f; compoundDrawablePadding = dp(16)
                    }
                    val item = apps[pos]
                    row.text = item.second
                    item.third?.setBounds(0, 0, dp(36), dp(36))
                    row.setCompoundDrawables(item.third, null, null, null)
                    return row
                }
            }
            MaterialAlertDialogBuilder(this).setTitle(title)
                .setAdapter(adapter) { d, w -> set(apps[w].first); renderAll(); d.dismiss() }
                .show()
        }
        val modes = listOf("none", "one", "split")
        Ui.segmented(findViewById(R.id.launchMode), listOf("None", "One app", "Split pair"), modes.indexOf(p.launchMode).coerceAtLeast(0)) {
            p.launchMode = modes[it]; renderAll() }
        findViewById<View>(R.id.swapBtn).setOnClickListener { val a = p.launchA; p.launchA = p.launchB; p.launchB = a; renderAll() }
        // The user's saved launcher pairs, one tap away when installed.
        val presets = listOf("Drive: Maps + CarApp" to ("com.google.android.apps.maps" to packageName),
            "Maps + Spotify" to ("com.google.android.apps.maps" to "com.spotify.music"))
            .filter { (_, pr) -> apps.any { it.first == pr.first } && apps.any { it.first == pr.second } }
        findViewById<View>(R.id.launchPresetRow).apply {
            visibility = if (presets.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingsActivity).setTitle("Split pair preset")
                    .setItems(presets.map { it.first }.toTypedArray()) { _, w ->
                        p.launchA = presets[w].second.first; p.launchB = presets[w].second.second; renderAll()
                    }.show()
            }
        }
        findViewById<View>(R.id.launchARow).setOnClickListener {
            pick(if (p.launchMode == "split") "Left app" else "App to open") { p.launchA = it }
        }
        findViewById<View>(R.id.launchBRow).setOnClickListener { pick("Right app") { p.launchB = it } }
        renderAll()
    }

    private fun buildHeadunitPicker() {
        val r = BtDevices.load(this)
        allDevices = r.all
        renderDev3()
        findViewById<View>(R.id.dev3Row).setOnClickListener {
            if (allDevices.isEmpty()) { toast(r.note ?: "No paired devices available"); return@setOnClickListener }
            val names = allDevices.map { it.second }.toTypedArray()
            val checked = allDevices.indexOfFirst { it.first == selMac3 }
            MaterialAlertDialogBuilder(this)
                .setTitle("Head unit tablet")
                .setSingleChoiceItems(names, checked) { dlg, which ->
                    selMac3 = allDevices[which].first; selName3 = allDevices[which].second
                    p.mac3 = selMac3; p.name3 = selName3; renderDev3(); dlg.dismiss()
                }
                .setNeutralButton("Clear") { _, _ ->
                    selMac3 = ""; selName3 = ""; p.mac3 = ""; p.name3 = ""; renderDev3()
                }
                .show()
        }
    }

    private fun renderDev3() {
        val icon = findViewById<ImageView>(R.id.dev3Icon)
        val value = findViewById<TextView>(R.id.dev3Value)
        if (selMac3.isEmpty()) { icon.setImageResource(BtDevices.ICON); value.text = "Head unit tablet" }
        else {
            icon.setImageResource(allDevices.firstOrNull { it.first == selMac3 }?.third ?: BtDevices.ICON)
            value.text = allDevices.firstOrNull { it.first == selMac3 }?.second ?: selName3
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()




    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---- Audio: which Bluetooth speaker GO connects (phone) ----
    private fun bindJbl() {
        if (Role.isTablet(this)) return
        val r = BtDevices.load(this)
        val name = findViewById<TextView>(R.id.outJblName)
        val icon = findViewById<ImageView>(R.id.outJblIcon)
        fun show() {
            val d = r.audio.firstOrNull { it.first == p.mac1 }
            findViewById<TextView>(R.id.outJblChip).tag = d?.second ?: p.name1   // device name, shown with the status
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
            MaterialAlertDialogBuilder(this).setTitle("Bluetooth speaker")
                .setSingleChoiceItems(r.audio.map { it.second }.toTypedArray(), r.audio.indexOfFirst { it.first == p.mac1 }) { d, w ->
                    p.mac1 = r.audio[w].first; p.name1 = r.audio[w].second; show(); d.dismiss() }
                .setNeutralButton("Clear") { _, _ -> p.mac1 = ""; p.name1 = ""; show() }
                .show()
        }
    }

    // ---- Drive: open the Maps + CarApp split by itself (tablet) ----
    private fun bindDrive() {
        val tablet = Role.isTablet(this)
        intArrayOf(R.id.driveHeader, R.id.driveCard).forEach { findViewById<View>(it).visibility = if (tablet) View.VISIBLE else View.GONE }
        // Lower music for directions: pref "duckNav" (lazycar, default on), shared; audio reads it.
        findViewById<MaterialSwitch>(R.id.duckSwitch).apply {
            isChecked = Ui.duckNav(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Ui.setOpen(this@SettingsActivity, "duckNav", on) }
        }
        findViewById<MaterialSwitch>(R.id.openMapsSwitch).apply {
            isChecked = Ui.openWithMaps(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Ui.setOpen(this@SettingsActivity, "openWithMaps", on) }
        }
    }

    /** Tablet, wide: section list on the left, tap scrolls the right pane to that section. */
    private fun buildNav() {
        if (!Role.isTablet(this) || resources.configuration.screenWidthDp < 840) return
        val nav = findViewById<android.widget.LinearLayout>(R.id.navList)
        val scroll = findViewById<android.widget.ScrollView>(R.id.settingsScroll)
        val content = scroll.getChildAt(0) as android.view.ViewGroup
        nav.visibility = View.VISIBLE
        val icons = mapOf("Audio" to R.drawable.ic_speaker, "Display" to R.drawable.ic_theme_light, "GPS" to R.drawable.ic_pin, "Drive" to R.drawable.ic_car,
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
