package com.lazyneil.lazycar.rear

import com.lazyneil.lazycar.R

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Settings as a real page rather than a bottom sheet, it holds the in-car
 * setup guide and the diagnostics, which are the two things you need most when
 * nothing is working yet, and a sheet you have to know to swipe up for is a bad
 * place to hide them.
 */
class RearSettingsActivity : AppCompatActivity() {

    companion object {
        /** Scroll straight to the setup guide (from the empty state's button). */
        const val EXTRA_SHOW_SETUP = "show_setup"

        private val SETUP_STEPS = listOf(
            R.string.setup_1_title to R.string.setup_1_body,
            R.string.setup_2_title to R.string.setup_2_body,
            R.string.setup_3_title to R.string.setup_3_body,
            R.string.setup_4_title to R.string.setup_4_body,
            R.string.setup_5_title to R.string.setup_5_body,
        )

        private val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private val prefs get() = getSharedPreferences("rearview", 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyNightMode(this)
        super.onCreate(savedInstanceState)
        Theming.applyToActivity(this)
        setContentView(R.layout.rv_activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<TextView>(R.id.txt_version).text = "CarApp ${RearActivity.VERSION}"

        buildSetupSteps()
        bindSwitches()
        bindThemePicker()
        bindButtons()
        refreshDiagnostics()

        if (intent.getBooleanExtra(EXTRA_SHOW_SETUP, false)) {
            val scroll = findViewById<NestedScrollView>(R.id.scroll)
            val card = findViewById<View>(R.id.card_setup)
            // card's parent is the scroll view's direct child, so its top is
            // already the scroll offset we want.
            scroll.post { scroll.smoothScrollTo(0, card.top) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been granted on the system page we linked to.
        refreshDiagnostics()
    }

    /** Every way out of this page (toolbar arrow, system back, gesture) ends up
     *  here, so this is the one place the exit animation has to be set. */
    override fun finish() {
        super.finish()
        Nav.close(this)
    }

    private fun buildSetupSteps() {
        val host = findViewById<LinearLayout>(R.id.setup_steps)
        SETUP_STEPS.forEachIndexed { index, (title, body) ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_setup_step, host, false)
            row.findViewById<TextView>(R.id.step_num).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.step_title).setText(title)
            // getText, not getString: the bodies carry <b> markup.
            row.findViewById<TextView>(R.id.step_body).text = getText(body)
            host.addView(row)
        }
    }

    private fun bindSwitches() {
        fun bind(id: Int, key: String, def: Boolean) {
            findViewById<MaterialSwitch>(id).apply {
                isChecked = prefs.getBoolean(key, def)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                }
            }
        }
        bind(R.id.sw_lines, "lines", false)
        bind(R.id.sw_mirror, "mirror", true)
        bind(R.id.sw_clean, "clean", false)
        bind(R.id.sw_autorec, "autorec", false)
        bind(R.id.sw_audio, "audio", true)
        bind(R.id.sw_debug, "debug", false)
    }

    private fun bindThemePicker() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.theme_group)
        group.check(
            when (Theming.current(this)) {
                "light" -> R.id.theme_light
                "dark" -> R.id.theme_dark
                "amoled" -> R.id.theme_amoled
                else -> R.id.theme_system
            }
        )
        showThemeCaption(Theming.current(this))
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val value = when (checkedId) {
                R.id.theme_light -> "light"
                R.id.theme_dark -> "dark"
                R.id.theme_amoled -> "amoled"
                else -> "system"
            }
            showThemeCaption(value)
            if (value == Theming.current(this)) return@addOnButtonCheckedListener
            prefs.edit().putString("theme", value).apply()
            Theming.applyNightMode(this)
            // dark → amoled is the same night mode, so setDefaultNightMode
            // alone would not redraw anything.
            recreate()
        }
    }

    /** The picker is icons only, so this line carries the name. */
    private fun showThemeCaption(theme: String) {
        findViewById<TextView>(R.id.txt_theme_caption).text = when (theme) {
            "light" -> "Light. Follows nothing, always the light palette."
            "dark" -> "Dark. The standard dark palette, grey surfaces."
            "amoled" -> "AMOLED. Pure black behind the video, so nothing of the app reflects in the windscreen at night."
            else -> "System. Follows the tablet's own light or dark setting."
        }
    }

    private fun bindButtons() {
        findViewById<MaterialButton>(R.id.btn_permissions).setOnClickListener {
            val missing = missingPermissions()
            if (missing.isEmpty()) {
                Toast.makeText(this, "Camera and Microphone are already granted", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // shouldShowRequestPermissionRationale is false both before the
            // first ask and after "don't ask again", the prefs flag tells the
            // two apart. Once blocked, only the system page can grant.
            val blocked = prefs.getBoolean("perms_asked", false) &&
                missing.none { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
            if (blocked) {
                Toast.makeText(this, "Enable Camera and Microphone here", Toast.LENGTH_LONG).show()
                openAppInfo()
            } else {
                prefs.edit().putBoolean("perms_asked", true).apply()
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            }
        }

        findViewById<MaterialButton>(R.id.btn_recordings).setOnClickListener {
            Nav.open(this, Intent(this, RecordingsActivity::class.java))
        }

        // The author, then the people this app stands on.
        openLinkOnClick(R.id.btn_credit_author, R.string.about_url_lazyneil)
        openLinkOnClick(R.id.btn_credit_ausbc, R.string.about_url_ausbc)
        openLinkOnClick(R.id.btn_credit_uvc, R.string.about_url_uvccamera)

        findViewById<MaterialButton>(R.id.btn_battery).setOnClickListener { openAppInfo() }
        findViewById<MaterialButton>(R.id.btn_refresh).setOnClickListener { refreshDiagnostics() }
        findViewById<MaterialButton>(R.id.btn_copy_diag).setOnClickListener {
            val text = "CarApp ${RearActivity.VERSION}\n\n" +
                findViewById<TextView>(R.id.txt_camera).text + "\n\n" +
                findViewById<TextView>(R.id.txt_sensors).text
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("CarApp diagnostics", text))
            Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshDiagnostics()
    }

    /** A tablet wired into a car may genuinely have no browser, so falling back
     *  to the clipboard beats an ActivityNotFoundException. */
    private fun openLinkOnClick(buttonId: Int, urlRes: Int) {
        findViewById<MaterialButton>(buttonId).setOnClickListener {
            val url = getString(urlRes)
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (view.resolveActivity(packageManager) != null) {
                startActivity(view)
            } else {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("CarApp link", url))
                Toast.makeText(this, "No browser here, so the link is copied", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun openAppInfo() {
        com.lazyneil.lazycar.Ui.openSettings(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun missingPermissions(): List<String> = RUNTIME_PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun refreshDiagnostics() {
        findViewById<TextView>(R.id.txt_camera).text = usbSummary() + "\n\n" + permissionSummary()
        findViewById<TextView>(R.id.txt_sensors).text = sensorSummary()
        refreshStorage()
    }

    private fun refreshStorage() {
        val clips = Recordings.list(this)
        findViewById<TextView>(R.id.txt_storage).text = if (clips.isEmpty()) {
            "No clips yet · ${Recordings.formatSize(Recordings.freeBytes(this))} free"
        } else {
            "${clips.size} clip${if (clips.size == 1) "" else "s"} · " +
                "${Recordings.formatSize(Recordings.totalBytes(this))} used · " +
                "${Recordings.formatSize(Recordings.freeBytes(this))} free"
        }
        findViewById<TextView>(R.id.txt_rec_location).text =
            Recordings.dir(this).absolutePath
    }

    private fun permissionSummary(): String = RUNTIME_PERMISSIONS.joinToString("\n") {
        val ok = ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        "${if (ok) "[OK]" else "[ X]"} ${it.substringAfterLast('.')}"
    }

    private fun usbSummary(): String {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usb.deviceList.isEmpty()) return "No USB devices detected (check OTG adapter/cable)"
        return usb.deviceList.values.joinToString("\n") { d ->
            val name = d.productName ?: "unknown"
            "• $name  vid=${d.vendorId} pid=${d.productId} class=${d.deviceClass}" +
                (if (UsbDevices.isVideoDevice(d)) " [camera]" else "") +
                (if (usb.hasPermission(d)) " perm=YES" else " perm=NO")
        }
    }

    private fun sensorSummary(): String {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val axis = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) "X" else "Y"
        return buildString {
            if (gyro != null) {
                append("• Gyroscope: ${gyro.name}\n  bends the guide lines through the turn")
            } else {
                append("• No gyroscope, guide lines stay straight")
            }
            append("\n• Display rotation: $degrees° (gyro $axis axis)")
        }
    }
}
