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
        selMac3 = p.mac3; selName3 = p.name3

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        Ui.bindOutputs(this, findViewById(R.id.root))
        bindJbl()
        bindDrive()
        buildNav()
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

        val mobile = findViewById<MaterialSwitch>(R.id.mobileSwitch)
        findViewById<MaterialSwitch>(R.id.hotspotSwitch).apply {
            isChecked = p.hotspot
            mobile.visibility = if (p.hotspot) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on -> p.hotspot = on; mobile.visibility = if (on) View.VISIBLE else View.GONE }
        }
        mobile.apply {
            isChecked = p.mobileData
            setOnCheckedChangeListener { _, on -> p.mobileData = on }
        }

        bindDevice()
        bindLaunch()
        buildHeadunitPicker()
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
        refreshA11y()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        p.headunitIp = findViewById<TextInputEditText>(R.id.ipField).text.toString().trim()
        val ssid = findViewById<TextInputEditText>(R.id.ssidField).text.toString().trim()
        if (Role.isTablet(this) && ssid.isNotEmpty() && ssid != p.hotspotSsid) { p.hotspotSsid = ssid; TapService.joinHotspot() }
    }

    private fun bindDevice() {
        val tablet = Role.isTablet(this)
        // Phone-only car audio controls are hidden on the tablet, the camera row on the phone.
        intArrayOf(R.id.headunitSwitch, R.id.headunitDetected,
            R.id.headunitExtra, R.id.hotspotSwitch, R.id.hotspotCaption, R.id.mobileSwitch, R.id.a11yRow)
            .forEach { if (tablet) findViewById<View>(it).visibility = View.GONE }
        // The camera section is tablet only.
        intArrayOf(R.id.camSection, R.id.camSectionHeader).forEach { findViewById<View>(it).visibility = if (tablet) View.VISIBLE else View.GONE }
        if (!SHOW_HEADUNIT) intArrayOf(R.id.headunitSwitch, R.id.headunitDetected, R.id.headunitExtra)
            .forEach { findViewById<View>(it).visibility = View.GONE }
        findViewById<View>(R.id.ssidBox).visibility = if (tablet) View.VISIBLE else View.GONE
        findViewById<TextInputEditText>(R.id.ssidField).setText(p.hotspotSsid)
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
            text = if (tablet) "Use phone GPS" else "Share GPS with tablet"
            isChecked = m != "stop"
            setOnCheckedChangeListener { _, on -> gps.launch(this@SettingsActivity, if (on) target else "stop") }
        }
        findViewById<TextView>(R.id.gpsCaption).text = if (tablet)
            "Receives the phone's location over its hotspot and feeds it to every app"
        else "Broadcasts this phone's location to the head unit tablet over the hotspot"
        com.lazyneil.lazycar.audio.TabletSpeaker.addSettings(this, findViewById(R.id.audioAnchor))
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
        val modes = mapOf(R.id.modeNone to "none", R.id.modeOne to "one", R.id.modeSplit to "split")
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.launchMode).apply {
            check(modes.entries.first { it.value == p.launchMode }.key)
            addOnButtonCheckedListener { _, id, checked -> if (checked) { p.launchMode = modes.getValue(id); renderAll() } }
        }
        val ratios = mapOf(R.id.ratio50 to 50, R.id.ratio65 to 65, R.id.ratio35 to 35)
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.ratioGroup).apply {
            check(ratios.entries.firstOrNull { it.value == p.splitRatio }?.key ?: R.id.ratio65)
            addOnButtonCheckedListener { _, id, checked -> if (checked) p.splitRatio = ratios.getValue(id) }
        }
        findViewById<View>(R.id.swapBtn).setOnClickListener { val a = p.launchA; p.launchA = p.launchB; p.launchB = a; renderAll() }
        // The user's saved launcher pairs, one tap away when installed.
        val presets = listOf("Drive: Maps + CarApp" to ("com.google.android.apps.maps" to packageName),
            "Maps + Spotify" to ("com.google.android.apps.maps" to "com.spotify.music"),
            "Maps + Poweramp" to ("com.google.android.apps.maps" to "com.maxmpz.audioplayer"))
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

    private fun refreshA11y() {
        val on = accessibilityEnabled()
        val icon = findViewById<ImageView>(R.id.a11yIcon)
        val text = findViewById<TextView>(R.id.a11yText)
        val row = findViewById<View>(R.id.a11yRow)
        if (on) {
            icon.setImageResource(R.drawable.ic_check_circle)
            icon.setColorFilter(Accent.color(this))
            text.text = "Auto-tap ready"
            row.setOnClickListener(null); row.isClickable = false
        } else {
            icon.setImageResource(R.drawable.ic_error_outline)
            icon.setColorFilter(getColor(R.color.errorRed))
            text.text = "Auto-tap off. Tap to set up"
            row.isClickable = true
            row.setOnClickListener { openAccessibilitySettings() }
        }
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

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---- Audio: which Bluetooth speaker GO connects (phone) ----
    private fun bindJbl() {
        if (Role.isTablet(this)) return
        val r = BtDevices.load(this)
        val name = findViewById<TextView>(R.id.outJblName)
        val icon = findViewById<ImageView>(R.id.outJblIcon)
        fun show() {
            val d = r.audio.firstOrNull { it.first == p.mac1 }
            name.text = d?.second ?: p.name1.ifEmpty { "JBL (Bluetooth)" }
            icon.setImageResource(d?.third ?: BtDevices.ICON)
        }
        show()
        findViewById<TextView>(R.id.btNote).apply { text = r.note ?: ""; visibility = if (r.note != null) View.VISIBLE else View.GONE }
        findViewById<View>(R.id.btEnable).apply {
            visibility = if (r.canEnable) View.VISIBLE else View.GONE
            @Suppress("DEPRECATION") setOnClickListener { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
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
        intArrayOf(R.id.openBootSwitch, R.id.openGoSwitch, R.id.driveCaption).forEach { findViewById<View>(it).visibility = if (tablet) View.VISIBLE else View.GONE }
        findViewById<MaterialSwitch>(R.id.openBootSwitch).apply {
            isChecked = Ui.openOnBoot(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Ui.setOpen(this@SettingsActivity, "openOnBoot", on) }
        }
        findViewById<MaterialSwitch>(R.id.openGoSwitch).apply {
            isChecked = Ui.openOnGo(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Ui.setOpen(this@SettingsActivity, "openOnGo", on) }
        }
    }

    /** Tablet, wide: section list on the left, tap scrolls the right pane to that section. */
    private fun buildNav() {
        if (!Role.isTablet(this) || resources.configuration.screenWidthDp < 840) return
        val nav = findViewById<android.widget.LinearLayout>(R.id.navList)
        val scroll = findViewById<android.widget.ScrollView>(R.id.settingsScroll)
        val content = scroll.getChildAt(0) as android.view.ViewGroup
        nav.visibility = View.VISIBLE
        for (i in 0 until content.childCount - 1) {
            val h = content.getChildAt(i) as? TextView ?: continue
            if (content.getChildAt(i + 1) !is android.widget.LinearLayout || h.visibility != View.VISIBLE || h.id == R.id.versionText) continue
            nav.addView(TextView(this).apply {
                text = h.text; textSize = 16f; setTextColor(Ui.attr(this@SettingsActivity, R.attr.carText))
                gravity = android.view.Gravity.CENTER_VERTICAL; minHeight = dp(56); setPadding(dp(16), 0, dp(16), 0)
                setBackgroundResource(R.drawable.bg_pill_ripple)
                setOnClickListener { scroll.smoothScrollTo(0, h.top) }
            }, android.widget.LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(4) })
        }
    }

    // ---- live GPS and camera status (moved here from the tablet home) ----
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { renderLive(); handler.postDelayed(this, 1000) } }

    private fun renderLive() {
        val g = com.lazyneil.lazycar.gps.GpsService
        val age = if (g.lastAt == 0L) -1 else (android.os.SystemClock.elapsedRealtime() - g.lastAt) / 1000
        val send = g.mode == "send"
        findViewById<TextView>(R.id.gpsLive).text = when {
            g.error.isNotEmpty() -> g.error
            g.mode == "idle" -> "Off"
            age < 0 -> if (send) "Waiting for a GPS fix" else "Waiting for the phone. Is its hotspot on?"
            else -> "${if (age <= 5) "Live" else "Last fix ${age}s ago"}. ${g.acc.toInt()} m, ${(g.speed * 3.6f).toInt()} km/h" +
                (if (g.sats >= 0) ", ${g.sats} satellites" else "") + (if (g.source.isNotEmpty()) ". ${g.source}" else "")
        }
        if (!Role.isTablet(this)) return
        val cam = getSystemService(android.hardware.usb.UsbManager::class.java).deviceList.values
            .firstOrNull { com.lazyneil.lazycar.rear.UsbDevices.isVideoDevice(it) }
        findViewById<TextView>(R.id.camLive).text = if (cam != null) "Connected: ${cam.productName ?: "USB webcam"}"
            else "Standby. Opens by itself when you shift into reverse"
    }
}
