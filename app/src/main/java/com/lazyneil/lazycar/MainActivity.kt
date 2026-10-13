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
    private var amoledApplied = false
    private var accentApplied = 0
    private var players = listOf<Triple<String, String, Drawable?>>()   // pkg,label,icon
    private var audioDevices = listOf<Triple<String, String, Int>>()    // mac,name,iconRes

    private var selPlayer = ""
    private var selMac1 = ""; private var selName1 = ""

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
        amoledApplied = p.amoled
        accentApplied = p.accent
        Accent.apply(this)
        if (p.amoled) theme.applyStyle(R.style.ThemeOverlay_LazyCar_Amoled, true)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selPlayer = p.playerPkg
        selMac1 = p.mac1; selName1 = p.name1

        val scroll = findViewById<View>(R.id.scroll)
        val go = findViewById<MaterialButton>(R.id.goButton)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, 0)
            scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, dp(108) + b.bottom)
            (go.layoutParams as MarginLayoutParams).bottomMargin = dp(16) + b.bottom
            go.requestLayout()
            insets
        }
        val want = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) ActivityCompat.requestPermissions(this, want.toTypedArray(), 1)
        com.lazyneil.lazycar.gps.GpsService.startIfIdle(this)
        if (Role.isTablet(this)) com.lazyneil.lazycar.audio.StreamReceiver.sync(this)

        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.helpBtn).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<ImageView>(R.id.brandIcon).setColorFilter(Accent.color(this))

        buildPlayers()
        buildOutputs()

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
        if (p.amoled != amoledApplied || p.accent != accentApplied) { recreate(); return }   // AMOLED/accent changed in Settings
        refreshSetup()
        buildOutputs()                       // reflect BT state changes made in Settings
        refreshGoButton()
        GoAction.deriveState(applicationContext, p) { refreshGoButton() }   // match reality after reboot
        if (btReceiver == null) {
            btReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { buildOutputs(); refreshGoButton() }
            }
            registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::p.isInitialized) return
        save()
        btReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        btReceiver = null
    }

    private fun refreshGoButton() {
        val btn = findViewById<MaterialButton>(R.id.goButton)
        val accent = Accent.color(this)
        val onAccent = Accent.onColor(accent)
        val error = ContextCompat.getColor(this, R.color.errorRed)
        val busy = ContextCompat.getColor(this, R.color.stateBusy)
        val secondary = ContextCompat.getColor(this, R.color.secondaryText)
        fun paint(bg: Int, fg: Int) {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(bg); btn.setTextColor(fg)
        }
        btn.alpha = 1f; btn.isEnabled = true; btn.icon = null
        fun busySpinner() {
            btn.isEnabled = false; paint(busy, secondary)
            val spec = com.google.android.material.progressindicator.CircularProgressIndicatorSpec(this, null)
            spec.indicatorSize = dp(24); spec.trackThickness = dp(3); spec.indicatorColors = intArrayOf(secondary)
            btn.icon = com.google.android.material.progressindicator.IndeterminateDrawable.createCircularDrawable(this, spec)
            btn.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; btn.iconSize = dp(24); btn.iconPadding = dp(12)
        }
        when (GoAction.effectiveState(p)) {
            GoAction.ON -> { btn.text = "STOP"; paint(error, onAccent) }
            GoAction.STARTING -> { btn.text = "Starting..."; busySpinner() }
            GoAction.STOPPING -> { btn.text = "Stopping..."; busySpinner() }
            else -> { btn.text = "GO"; paint(accent, onAccent) }
        }
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
        buildOutputs()
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
        val icon = findViewById<ImageView>(R.id.playerIcon)
        val value = findViewById<TextView>(R.id.playerValue)
        val cur = players.firstOrNull { it.first == selPlayer }
        if (cur != null) { icon.setImageDrawable(cur.third); value.text = cur.second }
        else { icon.setImageDrawable(null); value.text = "Choose a player" }
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

    // ---- output ----
    private fun buildOutputs() {
        val r = BtDevices.load(this)
        audioDevices = r.audio
        val note = findViewById<TextView>(R.id.btNote)
        val enableBtn = findViewById<MaterialButton>(R.id.btEnable)
        if (r.note != null) { note.text = r.note; note.visibility = View.VISIBLE } else note.visibility = View.GONE
        if (r.canEnable) {
            enableBtn.visibility = View.VISIBLE
            enableBtn.setOnClickListener {
                @Suppress("DEPRECATION") startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else enableBtn.visibility = View.GONE

        bindOutput()
        findViewById<TextView>(R.id.tabletValue).text = "Tablet speaker (Wi-Fi): " + com.lazyneil.lazycar.audio.TabletSpeaker.summary(this)

        wireVol(R.id.volBtn, R.id.volSlider, { p.volOn }, { p.volOn = it }, { p.vol }, { p.vol = it })
    }

    private fun bindOutput() {
        val rowId = R.id.out1Row
        val iconId = R.id.out1Icon
        val valueId = R.id.out1Value
        val title = "Bluetooth speaker"
        val getMac = { selMac1 }
        val set = { m: String, n: String -> selMac1 = m; selName1 = n }

        renderDevice(iconId, valueId, title, getMac(), audioDevices)
        findViewById<View>(rowId).setOnClickListener {
            if (audioDevices.isEmpty()) { toast("No paired devices available"); return@setOnClickListener }
            val names = audioDevices.map { it.second }.toTypedArray()
            val checked = audioDevices.indexOfFirst { it.first == getMac() }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, checked) { d, which ->
                    set(audioDevices[which].first, audioDevices[which].second)
                    renderDevice(iconId, valueId, title, audioDevices[which].first, audioDevices); save(); d.dismiss()
                }
                .setNeutralButton("Clear") { _, _ ->
                    set("", ""); renderDevice(iconId, valueId, title, "", audioDevices); save()
                }
                .show()
        }
    }

    private fun wireVol(btnId: Int, sliderId: Int, getOn: () -> Boolean, setOn: (Boolean) -> Unit,
                        getVol: () -> Int, setVol: (Int) -> Unit) {
        val slider = findViewById<Slider>(sliderId)
        val accentTint = android.content.res.ColorStateList.valueOf(Accent.color(this))
        slider.trackActiveTintList = accentTint
        slider.thumbTintList = accentTint
        slider.value = getVol().coerceIn(0, 100).toFloat()
        slider.visibility = if (getOn()) View.VISIBLE else View.GONE
        slider.clearOnChangeListeners()
        slider.addOnChangeListener { _, value, _ -> setVol(value.toInt()) }
        // Dragging while playing applies the new level live (open panel, set slider, back).
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) {}
            override fun onStopTrackingTouch(s: Slider) {
                setVol(s.value.toInt())
                if (GoAction.effectiveState(p) == GoAction.ON)
                    startActivity(Intent(this@MainActivity, GoActivity::class.java).putExtra("volumeOnly", true))
            }
        })
        findViewById<MaterialButton>(btnId).apply {
            setTextColor(Accent.color(this@MainActivity))
            setOnClickListener {
                val on = !getOn(); setOn(on)
                slider.visibility = if (on) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderDevice(iconId: Int, valueId: Int, default: String, mac: String,
                             list: List<Triple<String, String, Int>>) {
        val icon = findViewById<ImageView>(iconId)
        val value = findViewById<TextView>(valueId)
        if (mac.isEmpty()) {
            icon.setImageResource(BtDevices.ICON); value.text = default
        } else {
            icon.setImageResource(list.firstOrNull { it.first == mac }?.third ?: BtDevices.ICON)
            value.text = list.firstOrNull { it.first == mac }?.second ?: mac
        }
    }

    private fun save() {
        p.playerPkg = selPlayer
        p.mac1 = selMac1; p.name1 = selName1
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
