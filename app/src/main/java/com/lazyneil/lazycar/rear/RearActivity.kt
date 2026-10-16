package com.lazyneil.lazycar.rear

import com.lazyneil.lazycar.R

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import java.io.File

/**
 * Launched automatically by the system when the webcam gets USB power
 * (i.e. when reverse gear energizes the relay). Shows the feed full screen,
 * over the swipe lockscreen, and closes itself when the camera loses power.
 */
class RearActivity : AppCompatActivity() {

    companion object {
        const val VERSION = "v2.1"
        private const val ACTION_USB_PERMISSION = "com.lazyneil.lazycar.USB_PERMISSION"
        private const val REQ_CAMERA_PERMISSION = 1
        private const val REQ_ALL_PERMISSIONS = 2
        private val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        /** Never replace a camera fragment younger than this: the webcam
         *  re-enumerates when its stream opens, and restarting on that ATTACH
         *  killed the feed right as the first frames were arriving. */
        private const val START_GRACE_MS = 6000L
        private const val MAX_START_RETRIES = 3

        /** USB attach/detach broadcasts can be missed (or the device can appear
         *  before we registered); re-read the real state on this cadence while
         *  there is no video, so the empty state never lies. */
        private const val EMPTY_REFRESH_MS = 1500L

        /** How long the revealed controls stay up in clean mode. */
        private const val CONTROLS_LINGER_MS = 6000L

        /** AUSBC's muxer only starts once it has both a video and an audio
         *  track, so onBegin arriving is the proof that recording really
         *  started. If it does not, the clip would be a 0-byte file. */
        private const val RECORD_BEGIN_TIMEOUT_MS = 5000L

        /** captureVideoStop() finalizes the mp4 on the encoder thread; give it
         *  a moment before judging the file on disk. */
        private const val RECORD_FLUSH_MS = 900L

        /** Grace before auto-record starts, so the encoder is not asked for a
         *  surface while the first frames are still coming up. */
        private const val AUTO_RECORD_DELAY_MS = 1200L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null
    private var overlayCard: View? = null
    private var copyButton: Button? = null
    private var dismissButton: Button? = null

    private var emptyState: View? = null
    private var emptyIcon: ImageView? = null
    private var emptyTitle: TextView? = null
    private var emptyBody: TextView? = null
    private var emptyStatus: View? = null
    private var emptySpinner: View? = null
    private var emptyHint: TextView? = null
    private var emptyActions: View? = null
    private var emptyPrimary: MaterialButton? = null
    private var emptySecondary: MaterialButton? = null

    private var emptyCard: LinearLayout? = null
    private var emptyTextColumn: LinearLayout? = null

    private var controls: View? = null
    private var linesButton: MaterialButton? = null
    private var recordButton: MaterialButton? = null
    private var gestureCatcher: View? = null

    private var recChip: View? = null
    private var recDot: View? = null
    private var recTime: TextView? = null

    /** Launched over the lockscreen (reverse gear): show nothing but video. */
    var minimalMode = false
        private set

    /**
     * Launched by the system because the camera got power, i.e. the car is in
     * reverse. That path must be silent: straight to the picture, no card
     * explaining what is being negotiated. The explanations are for someone who
     * opened RearView by hand.
     */
    private var autoLaunched = false

    /** True while the controls are hidden by default and have not been
     *  revealed by a gesture. */
    private val controlsHiddenByDefault get() = minimalMode || prefs.getBoolean("clean", false)
    private var controlsRevealed = false

    /** The camera lost power: we are on our way out. Suppresses the empty
     *  state, so leaving reverse never flashes "No camera connected". */
    private var closing = false

    /** Clip being written, and when it started. */
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L
    private var recordingBegan = false

    /** Auto-record started this clip, so it reports itself quietly. */
    private var recordingIsAuto = false

    private val isRecording get() = recordingFile != null

    private val camFragment
        get() = supportFragmentManager.findFragmentById(R.id.fragment_host) as? RearCamFragment

    private val prefs get() = getSharedPreferences("rearview", 0)
    private val debugEnabled get() = prefs.getBoolean("debug", false)
    private val usbManager get() = getSystemService(Context.USB_SERVICE) as UsbManager

    /** True while a crash log is being displayed, normal status updates
     *  must not overwrite it until the user dismisses it. */
    private var showingCrashLog = false

    /** Set once preview frames are actually arriving. */
    @Volatile private var videoFlowing = false

    private var lastCameraStartMs = 0L
    private var startRetries = 0

    /** Camera present and permitted, but no frames after every retry. */
    private var stalled = false

    /** The theme this activity was built with, so returning from Settings with
     *  a different one can rebuild instead of showing a stale palette. */
    private var appliedTheme = Theming.DEFAULT

    private val crashFile: File get() = File(filesDir, "crash.txt")

    // Webcams with built-in mics often re-enumerate right after the UVC stream
    // opens, firing a momentary DETACH. So never close immediately: wait, then
    // close only if no video-class device is actually present anymore.
    private val closeIfCameraGone = Runnable {
        val stillThere = usbManager.deviceList.values.any { UsbDevices.isVideoDevice(it) }
        if (!stillThere && !showingCrashLog) {
            finishAndRemoveTask()
        } else {
            // False alarm, the camera re-enumerated. Pick the picture back up
            // (and auto-record with it) instead of sitting on a dead screen.
            closing = false
            if (camFragment?.hasFrames == true) videoStarted() else updateEmptyState()
        }
    }

    /** Ticks the REC pill, blinks its dot, and enforces the clip length cap. */
    private val recTicker = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - recordingStartedAt
            recTime?.text = "REC ${Recordings.formatDuration(elapsed)}"
            recDot?.alpha = if ((elapsed / 500) % 2 == 0L) 1f else 0.2f
            if (elapsed >= Recordings.MAX_CLIP_MS) {
                stopRecording()
                setStatus("Recording stopped at the 15 minute clip limit.")
                return
            }
            handler.postDelayed(this, 250)
        }
    }

    /** No onBegin means the muxer never started, almost always a microphone
     *  the system would not hand over. Fail loudly rather than leave a
     *  0-byte file and a REC pill that lies. */
    private val recordWatchdog = Runnable {
        if (isRecording && !recordingBegan) {
            camFragment?.stopRecording()
            recordingFailed("no data. Is the microphone available?")
        }
    }

    private val hideControls = Runnable {
        controlsRevealed = false
        updateControls()
    }

    /** Auto-record, a beat after the first frame: the encoder draws from the
     *  GL render pipeline, which is still being wired up as frames start. */
    private val startAutoRecord = Runnable {
        if (videoFlowing && !isRecording) startRecording(auto = true)
    }

    /** While there is no video, keep the empty state honest about the
     *  currently plugged-in hardware. */
    private val refreshEmptyState = object : Runnable {
        override fun run() {
            updateEmptyState()
            if (!videoFlowing) handler.postDelayed(this, EMPTY_REFRESH_MS)
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (UsbDevices.isVideoDevice(device)) {
                videoFlowing = false
                // Leaving reverse is a normal exit, not a fault: no empty-state
                // card, just the picture going away.
                closing = true
                // Close the mp4 while the encoder is still alive, an unfinished
                // file has no moov atom and will not play.
                if (isRecording) stopRecording()
                updateEmptyState()
                updateControls()
                handler.removeCallbacks(closeIfCameraGone)
                handler.postDelayed(closeIfCameraGone, 2000)
            }
        }
    }

    /**
     * The camera library only opens a camera it had permission for when it
     * started, it never retries. So whenever a camera attaches (or USB
     * permission is granted), restart the camera engine ourselves.
     */
    private val usbEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, ACTION_USB_PERMISSION -> {
                    updateEmptyState()
                    handler.removeCallbacks(ensureCameraRunning)
                    handler.postDelayed(ensureCameraRunning, 300)
                }
            }
        }
    }

    private val ensureCameraRunning = Runnable {
        if (videoFlowing || showingCrashLog) return@Runnable
        // The AUSBC library silently refuses to open ANY camera (even UVC over
        // USB) without the runtime CAMERA permission when targetSdk >= 28,
        // it just logs and returns, which looked like a hang.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("CarApp $VERSION: asking for Camera permission…", essential = false)
            updateEmptyState()
            dismissKeyguardThen {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION
                )
            }
            return@Runnable
        }
        val cam = usbManager.deviceList.values.firstOrNull { UsbDevices.isVideoDevice(it) }
        if (cam == null) {
            updateEmptyState()
            return@Runnable
        }
        if (usbManager.hasPermission(cam)) {
            // The webcam re-enumerates when its stream opens, which lands us
            // back here via ATTACHED. If a camera fragment is already mid-open,
            // leave it alone, restarting it here is what made video flaky.
            val fragmentUp = supportFragmentManager.findFragmentById(R.id.fragment_host) != null
            val sinceStart = android.os.SystemClock.elapsedRealtime() - lastCameraStartMs
            if (fragmentUp && sinceStart < START_GRACE_MS) return@Runnable
            setStatus("CarApp $VERSION: camera found, starting…", essential = false)
            startCamera()
        } else {
            updateEmptyState()
            dismissKeyguardThen { requestUsbPermission(cam) }
        }
    }

    /**
     * Android suppresses permission dialogs (USB and runtime alike) while the
     * keyguard is active, and with showWhenLocked we are always sitting on
     * top of an active (swipe) keyguard, so popups never appeared. A swipe
     * lock is insecure, so it can be dismissed without user input first.
     */
    private fun dismissKeyguardThen(action: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = action()
                override fun onDismissError() = action()
                override fun onDismissCancelled() {
                    setStatus(
                        "CarApp $VERSION: swipe the lockscreen away,\n" +
                            "then unplug & replug the camera."
                    )
                }
            })
        } else {
            action()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateEmptyState()
        // Whatever was granted (or not), re-evaluate: the camera engine never
        // retries by itself.
        handler.removeCallbacks(ensureCameraRunning)
        handler.postDelayed(ensureCameraRunning, 300)
    }

    private fun requestUsbPermission(cam: UsbDevice) {
        setStatus(
            "CarApp $VERSION: asking for USB permission…",
            essential = false
        )
        val pi = android.app.PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            // Mutable so UsbManager can fill in EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED.
            android.app.PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(cam, pi)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashReporter()
        Theming.applyNightMode(this)
        super.onCreate(savedInstanceState)
        Theming.applyToActivity(this)
        appliedTheme = Theming.current(this)
        minimalMode = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        autoLaunched = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        // A remux that was interrupted last time (the tablet cut power mid-strip)
        // leaves a temp file behind; the clip itself is never lost this way.
        Recordings.sweepTemporaries(this)

        // Show over the (swipe-only) lockscreen and wake the display.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Full-screen immersive: hide status and navigation bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.rv_activity_main)
        bindViews()
        setStatus("CarApp $VERSION: waiting for camera…", essential = false)

        copyButton?.setOnClickListener {
            val text = if (crashFile.exists()) crashFile.readText() else statusView?.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CarApp crash log", text))
            Toast.makeText(this, "Log copied. Paste it anywhere", Toast.LENGTH_LONG).show()
        }
        dismissButton?.setOnClickListener {
            crashFile.delete()
            showingCrashLog = false
            statusView?.visibility = View.GONE
            copyButton?.visibility = View.GONE
            dismissButton?.visibility = View.GONE
            setStatus("CarApp $VERSION: waiting for camera…", essential = false)
            startCamera()
        }

        ContextCompat.registerReceiver(
            this,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            usbEventReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(ACTION_USB_PERMISSION)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        // Camera may already be plugged in (possibly without permission yet).
        handler.postDelayed(ensureCameraRunning, 300)

        // If the last run crashed, show the log on a STABLE screen: the camera
        // (the likely crasher) is not started until the user taps DISMISS.
        // Otherwise ensureCameraRunning (posted above) is the ONLY thing that
        // starts the camera, starting it here too made the two race.
        if (crashFile.exists()) {
            showCrashLogIfAny()
        }
        maybeRequestAllPermissions()
    }

    private fun bindViews() {
        statusView = findViewById(R.id.status_text)
        overlayCard = findViewById(R.id.overlay_card)
        copyButton = findViewById(R.id.btn_copy)
        dismissButton = findViewById(R.id.btn_dismiss)

        emptyState = findViewById(R.id.empty_state)
        emptyIcon = findViewById(R.id.empty_icon)
        emptyTitle = findViewById(R.id.empty_title)
        emptyBody = findViewById(R.id.empty_body)
        emptyStatus = findViewById(R.id.empty_status)
        emptySpinner = findViewById(R.id.empty_spinner)
        emptyHint = findViewById(R.id.empty_hint)
        emptyActions = findViewById(R.id.empty_actions)
        emptyPrimary = findViewById(R.id.btn_empty_primary)
        emptySecondary = findViewById(R.id.btn_empty_secondary)
        emptyCard = findViewById(R.id.empty_card)
        emptyTextColumn = findViewById(R.id.empty_text_column)
        applyEmptyStateOrientation()

        controls = findViewById(R.id.controls)
        linesButton = findViewById(R.id.btn_lines)
        recordButton = findViewById(R.id.btn_record)
        gestureCatcher = findViewById(R.id.gesture_catcher)
        recChip = findViewById(R.id.rec_chip)
        recDot = findViewById(R.id.rec_dot)
        recTime = findViewById(R.id.rec_time)

        // Over the lockscreen (reverse gear) show the picture and nothing else.
        if (minimalMode) emptyActions?.visibility = View.GONE

        linesButton?.setOnClickListener {
            prefs.edit().putBoolean("lines", !prefs.getBoolean("lines", false)).apply()
            refreshCamPrefs()
            updateControls()
            keepControlsAwake()
        }
        recordButton?.setOnClickListener {
            if (isRecording) stopRecording() else startRecording(auto = false)
            updateControls()
            keepControlsAwake()
        }
        findViewById<View>(R.id.btn_recordings).setOnClickListener { openRecordings() }
        findViewById<View>(R.id.btn_settings).setOnClickListener { openSettings() }
        emptySecondary?.setOnClickListener { openSettings() }
        bindRevealGesture()
        updateControls()
    }

    /**
     * Clean mode has no buttons at all. Double-tap or swipe up on the picture
     * to bring them back for a few seconds, the same two gestures work over
     * the lockscreen, where the controls are hidden regardless.
     */
    private fun bindRevealGesture() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (controlsRevealed) hideControls.run() else revealControls()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val up = (e1?.y ?: return false) - e2.y
                if (up > 90 && velocityY < -400) {
                    revealControls()
                    return true
                }
                return false
            }
        })
        gestureCatcher?.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun revealControls() {
        controlsRevealed = true
        updateControls()
        keepControlsAwake()
    }

    /** Restart the linger timer, so using the controls does not fight with
     *  them disappearing mid-tap. */
    private fun keepControlsAwake() {
        handler.removeCallbacks(hideControls)
        if (controlsHiddenByDefault) handler.postDelayed(hideControls, CONTROLS_LINGER_MS)
    }

    /**
     * Sideways (icon beside the text) when there is width for it, stacked and
     * centred when there is not. This activity declares its own configChanges
     * so that rotating never restarts the camera, which also means the layout
     * is never re-inflated, so the switch has to happen here.
     */
    private fun applyEmptyStateOrientation(
        config: android.content.res.Configuration = resources.configuration
    ) {
        val card = emptyCard ?: return
        val column = emptyTextColumn ?: return
        // Keyed off the window's actual shape, not an absolute dp width: a
        // portrait tablet can still be 800dp wide, and laying the card out
        // sideways there looks like the landscape UI leaked into portrait.
        val wide = config.screenWidthDp > config.screenHeightDp &&
            config.screenWidthDp >= 600

        card.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        card.gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        column.gravity = if (wide) Gravity.START else Gravity.CENTER_HORIZONTAL

        val gap = (24 * resources.displayMetrics.density).toInt()
        (column.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = if (wide) gap else 0
            topMargin = if (wide) 0 else gap
        }
        column.requestLayout()

        // gravity, not textAlignment: these TextViews are wrap_content with a
        // maxWidth, so the view ends up as wide as its longest line and
        // textAlignment had nothing to centre inside, the body stayed
        // left-aligned under a centred title, which just looked broken.
        val gravity = if (wide) Gravity.START else Gravity.CENTER_HORIZONTAL
        emptyTitle?.gravity = gravity
        emptyBody?.gravity = gravity
        // Centred text reads badly when the lines are wildly uneven, so the
        // stacked card gets a narrower measure than the sideways one.
        val measure = ((if (wide) 420 else 300) * resources.displayMetrics.density).toInt()
        emptyTitle?.maxWidth = measure
        emptyBody?.maxWidth = measure
        // The chip and the buttons are laid out by the column, so they follow
        // the column's own gravity, set above.
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyEmptyStateOrientation(newConfig)
    }

    private fun openSettings(showSetup: Boolean = false) {
        dismissKeyguardThen {
            Nav.open(
                this,
                Intent(this, RearSettingsActivity::class.java)
                    .putExtra(RearSettingsActivity.EXTRA_SHOW_SETUP, showSetup)
            )
        }
    }

    private fun updateControls() {
        val show = !controlsHiddenByDefault || controlsRevealed
        controls?.visibility = if (show) View.VISIBLE else View.GONE

        val lines = prefs.getBoolean("lines", false)
        linesButton?.text = if (lines) "Lines: on" else "Lines: off"
        // Lines and recording only mean anything over a live picture.
        linesButton?.visibility = if (videoFlowing) View.VISIBLE else View.GONE
        recordButton?.visibility = if (videoFlowing) View.VISIBLE else View.GONE
        updateRecordButton()
    }

    /** Idle: a red dot on the normal tonal button. Recording: a stop square on
     *  an error-coloured one, so the state is obvious at a glance. */
    private fun updateRecordButton() {
        val button = recordButton ?: return
        button.setIconResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_record)
        button.contentDescription = if (isRecording) "Stop recording" else "Start recording"
        val background = themeColor(
            if (isRecording) com.google.android.material.R.attr.colorErrorContainer
            else com.google.android.material.R.attr.colorSecondaryContainer
        )
        val icon = themeColor(
            if (isRecording) com.google.android.material.R.attr.colorOnErrorContainer
            else androidx.appcompat.R.attr.colorError
        )
        button.backgroundTintList = ColorStateList.valueOf(background)
        button.iconTint = ColorStateList.valueOf(icon)
    }

    private fun themeColor(attr: Int): Int =
        MaterialColors.getColor(recordButton ?: window.decorView, attr)

    /** The REC pill is shown in every mode, including over the lockscreen.
     *  A recording you cannot tell is running is worse than a bit of clutter. */
    private fun updateRecChip() {
        recChip?.visibility = if (isRecording) View.VISIBLE else View.GONE
    }

    // ── Recording ──────────────────────────────────────────────────────────

    /**
     * Start a clip. [auto] marks the ones auto-record started, which report
     * themselves quietly, nobody wants a toast on every reverse.
     */
    private fun startRecording(auto: Boolean) {
        if (isRecording || !videoFlowing) return

        // The library's muxer waits for an audio track before it writes
        // anything, so without the mic there is no clip at all, not even a
        // silent one. Say so instead of producing an empty file.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (!auto) {
                toast("Recording needs the Microphone permission")
                requestMissingPermissions()
            }
            setStatus("Cannot record: Microphone permission not granted.", essential = !auto)
            return
        }
        if (Recordings.freeBytes(this) < Recordings.MIN_FREE_BYTES) {
            if (!auto) toast("Not enough free space to record")
            setStatus("Cannot record: less than 200 MB free.", essential = !auto)
            return
        }

        val file = camFragment?.startRecording()
        if (file == null) {
            if (!auto) toast("The camera is not ready yet")
            return
        }
        recordingFile = file
        recordingStartedAt = SystemClock.elapsedRealtime()
        recordingBegan = false
        recordingIsAuto = auto
        updateRecChip()
        updateControls()
        handler.post(recTicker)
        handler.postDelayed(recordWatchdog, RECORD_BEGIN_TIMEOUT_MS)
    }

    private fun stopRecording() {
        val file = camFragment?.stopRecording() ?: recordingFile
        val elapsed = SystemClock.elapsedRealtime() - recordingStartedAt
        val quiet = recordingIsAuto
        finishRecording(elapsed)
        if (file != null) verifyClip(file, elapsed, quiet)
    }

    /** Clears recording state and the UI that goes with it. */
    private fun finishRecording(elapsedMs: Long) {
        handler.removeCallbacks(recTicker)
        handler.removeCallbacks(recordWatchdog)
        recordingFile = null
        recordingBegan = false
        recordingIsAuto = false
        recDot?.alpha = 1f
        recTime?.text = "REC ${Recordings.formatDuration(elapsedMs)}"
        updateRecChip()
        updateControls()
    }

    /**
     * A finished clip is only real once the encoder has closed it and there are
     * bytes on disk. Then, if sound is switched off, drop the audio track the
     * library forced us to record.
     *
     * All of it on a plain thread with the application context, deliberately:
     * the most important case is the camera losing power, where this activity
     * is already being torn down and anything on its handler would be dropped.
     */
    private fun verifyClip(file: File, elapsedMs: Long, quiet: Boolean) {
        val app = applicationContext
        val stripAudio = !prefs.getBoolean("audio", true)
        Thread {
            // captureVideoStop() finalizes the mp4 on the encoder thread.
            runCatching { Thread.sleep(RECORD_FLUSH_MS) }
            if (!file.exists() || file.length() == 0L) {
                file.delete()
                if (!quiet) toastOn(app, "Recording failed: nothing was written")
                return@Thread
            }
            if (stripAudio && !Recordings.stripAudio(file) && !quiet) {
                toastOn(app, "Saved, but the audio track could not be removed")
                return@Thread
            }
            if (!quiet) {
                toastOn(
                    app,
                    "Saved ${Recordings.formatDuration(elapsedMs)} · " +
                        Recordings.formatSize(file.length())
                )
            }
        }.start()
    }

    /** Toast from a background thread, via the application context, this can
     *  land after the activity is gone. */
    private fun toastOn(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    /** Called by the fragment when the muxer actually started writing. */
    fun recordingStarted() {
        runOnUiThread {
            recordingBegan = true
            handler.removeCallbacks(recordWatchdog)
        }
    }

    /** Called by the fragment when the capture pipeline reports an error. */
    fun recordingFailed(reason: String) {
        runOnUiThread {
            val wasAuto = recordingIsAuto
            val file = recordingFile
            finishRecording(0L)
            file?.takeIf { it.length() == 0L }?.delete()
            if (!wasAuto) toast("Recording failed: $reason")
            setStatus("Recording failed: $reason")
        }
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun openRecordings() {
        dismissKeyguardThen { Nav.open(this, Intent(this, RecordingsActivity::class.java)) }
    }

    /**
     * The whole point of this screen: with no video there must never be a bare
     * black rectangle. Work out WHY there is no picture and say so, with the
     * one action that actually moves things forward.
     */
    private fun updateEmptyState() {
        val empty = emptyState ?: return
        // Closing: the camera lost power, we are seconds from finishing. Never
        // explain the absence of a camera we are deliberately leaving.
        if (videoFlowing || closing) {
            empty.visibility = View.GONE
            return
        }

        val devices = usbManager.deviceList.values
        val cam = devices.firstOrNull { UsbDevices.isVideoDevice(it) }
        val missing = missingPermissions().filter { it == Manifest.permission.CAMERA }   // the mic is asked by recording

        val icon: Int
        var title: String
        var body: String
        var hint: String
        var spinner = true
        var primaryLabel: String? = "Setup guide"
        var primaryAction: () -> Unit = { openSettings(showSetup = true) }

        /** Whether this state needs the user to DO something. On the reverse-gear
         *  path the transient ones (looking for a camera, starting it up) are
         *  suppressed, that path has to be seamless. */
        var needsUser = false

        when {
            missing.isNotEmpty() -> {
                icon = R.drawable.ic_lock
                title = "Permissions needed"
                body = "Camera is required. The USB camera will not open without it."
                hint = missing.joinToString("  ") { "✗ ${it.substringAfterLast('.')}" }
                spinner = false
                primaryLabel = "Grant permissions"
                primaryAction = { requestMissingPermissions() }
                needsUser = true
            }

            stalled -> {
                icon = R.drawable.ic_videocam_off
                title = "Camera found, but no video"
                body = "The camera is connected and allowed, but it never sent a frame. " +
                    "Unplug it and plug it back in. That fixes it most of the time."
                hint = cam?.productName ?: "USB camera"
                spinner = false
                primaryLabel = "Try again"
                primaryAction = {
                    stalled = false
                    startRetries = 0
                    updateEmptyState()
                    startCamera()
                }
                // Not transient: this is after every retry has failed, so even
                // in the car it is better said than left as a black screen.
                needsUser = true
            }

            cam != null && !usbManager.hasPermission(cam) -> {
                icon = R.drawable.ic_lock
                title = "Allow access to the camera"
                body = "Android needs a one-time approval for this USB device. " +
                    "Tick “always open CarApp” on the prompt and it will never ask again."
                hint = cam.productName ?: "USB camera"
                spinner = false
                primaryLabel = "Allow"
                primaryAction = { dismissKeyguardThen { requestUsbPermission(cam) } }
                needsUser = true
            }

            cam != null -> {
                icon = R.drawable.ic_car
                title = "Starting the camera…"
                body = "Camera connected. Negotiating the video stream. This takes a second or two."
                hint = cam.productName ?: "USB camera"
                primaryLabel = null
            }

            devices.isEmpty() -> {
                icon = R.drawable.ic_usb
                title = "No camera connected"
                body = "Plug your USB camera into the tablet's OTG port. Wired up in the car, " +
                    "it powers on by itself the moment you shift into reverse, and this screen " +
                    "turns into the picture."
                hint = "Watching the USB port…"
            }

            else -> {
                icon = R.drawable.ic_videocam_off
                title = "That's not a camera"
                body = "Something is plugged in, but Android does not see a UVC video device. " +
                    "Some OTG adapters pass power but not data. Try another adapter or cable."
                hint = devices.joinToString("  ") { it.productName ?: "unknown device" }
            }
        }

        // Seamless when the car put us here: no card for the states that fix
        // themselves within a second or two.
        if (autoLaunched && !needsUser) {
            empty.visibility = View.GONE
            return
        }
        empty.visibility = View.VISIBLE

        emptyIcon?.setImageResource(icon)
        emptyTitle?.text = title
        emptyBody?.text = body
        emptyHint?.text = hint
        emptySpinner?.visibility = if (spinner) View.VISIBLE else View.GONE
        emptyStatus?.visibility = if (hint.isBlank()) View.GONE else View.VISIBLE
        emptyPrimary?.apply {
            visibility = if (primaryLabel == null) View.GONE else View.VISIBLE
            primaryLabel?.let { text = it }
            setOnClickListener { primaryAction() }
        }
    }

    private fun requestMissingPermissions() {
        val missing = missingPermissions().toTypedArray()
        if (missing.isEmpty()) return
        // shouldShowRequestPermissionRationale is false both before the first
        // ask and after "don't ask again", the prefs flag tells the two apart.
        // If blocked, the system settings page is the only place left to grant.
        val blocked = prefs.getBoolean("perms_asked", false) &&
            missing.none { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        if (blocked) {
            Toast.makeText(
                this, "Enable Camera and Microphone here, then reopen CarApp",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                )
            )
        } else {
            prefs.edit().putBoolean("perms_asked", true).apply()
            dismissKeyguardThen {
                ActivityCompat.requestPermissions(this, missing, REQ_ALL_PERMISSIONS)
            }
        }
    }

    private fun startCamera() {
        lastCameraStartMs = android.os.SystemClock.elapsedRealtime()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_host, RearCamFragment())
            .commitAllowingStateLoss()
    }

    /** Called by the fragment when no frame arrived within its timeout.
     *  Restart the whole camera engine a few times before giving up,
     *  the first open attempt losing the re-enumeration race is common. */
    fun cameraStalled() {
        if (videoFlowing || showingCrashLog) return
        if (startRetries < MAX_START_RETRIES) {
            startRetries++
            setStatus(
                "CarApp $VERSION: no video yet, restarting camera (try $startRetries/$MAX_START_RETRIES)…",
                essential = false
            )
            startCamera()
        } else {
            stalled = true
            setStatus(
                "No video after $MAX_START_RETRIES restarts.\n" + usbDeviceSummary(),
                essential = false
            )
        }
        updateEmptyState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Attached by the car (be seamless) or opened by hand (explain things).
        autoLaunched = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        if (autoLaunched) closing = false
        // Relaunched by the system USB-attach dialog while already running.
        handler.removeCallbacks(ensureCameraRunning)
        handler.postDelayed(ensureCameraRunning, 300)
    }

    /** Called by the fragment when preview frames actually arrive. */
    fun videoStarted() {
        videoFlowing = true
        startRetries = 0
        stalled = false
        closing = false
        setStatus(null)
        runOnUiThread {
            updateEmptyState()
            updateControls()
            // Reverse gear IS the trigger, so "record every time the camera
            // comes up" is the dashcam behaviour people actually want.
            if (prefs.getBoolean("autorec", false)) {
                handler.removeCallbacks(startAutoRecord)
                handler.postDelayed(startAutoRecord, AUTO_RECORD_DELAY_MS)
            }
        }
    }

    /** Status/diagnostic overlay. Pass null to hide (video is flowing).
     *  Never overwrites a crash log that is on screen. Non-essential progress
     *  chatter is hidden unless the "debug" option is on. */
    fun setStatus(text: String?, essential: Boolean = true) {
        runOnUiThread {
            if (showingCrashLog) return@runOnUiThread
            val show = text != null && (essential || debugEnabled)
            statusView?.apply {
                visibility = if (show) View.VISIBLE else View.GONE
                if (show) this.text = text
            }
            updateOverlay()
        }
    }

    /** The floating card exists only while it has something to show. */
    private fun updateOverlay() {
        val any = listOf(statusView, copyButton, dismissButton)
            .any { it?.visibility == View.VISIBLE }
        overlayCard?.visibility = if (any) View.VISIBLE else View.GONE
    }

    fun usbDeviceSummary(): String {
        val usb = usbManager
        if (usb.deviceList.isEmpty()) return "No USB devices detected (check OTG adapter/cable)"
        return usb.deviceList.values.joinToString("\n") { d ->
            val name = d.productName ?: "unknown"
            "• $name  vid=${d.vendorId} pid=${d.productId} class=${d.deviceClass}" +
                (if (UsbDevices.isVideoDevice(d)) " [camera]" else "") +
                (if (usb.hasPermission(d)) " perm=YES" else " perm=NO")
        }
    }

    private fun missingPermissions(): List<String> = RUNTIME_PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * CAMERA: the camera library refuses to open the UVC camera without it.
     * RECORD_AUDIO: the webcam has a mic, and without this Android shows an
     * audio-capture warning on every attach AND refuses to remember "always
     * open RearView". Only CAMERA is asked up front when the user opened the app by
     * hand (not over the lockscreen).
     */
    private fun maybeRequestAllPermissions() {
        updateEmptyState()
        val locked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        // Opening rearview asks the camera only; the mic is asked when a recording starts.
        val missing = missingPermissions().filter { it == Manifest.permission.CAMERA }.toTypedArray()
        if (missing.isNotEmpty() && !locked) {
            prefs.edit().putBoolean("perms_asked", true).apply()
            ActivityCompat.requestPermissions(this, missing, REQ_ALL_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // A theme picked in Settings only takes effect on a fresh activity.
        if (appliedTheme != Theming.current(this)) {
            recreate()
            return
        }
        // Options may have changed in Settings while we were stopped.
        refreshCamPrefs()
        // Clean mode may have just been switched on or off; either way the
        // controls were on screen while the user was in Settings.
        controlsRevealed = false
        updateControls()
        keepControlsAwake()
        updateEmptyState()
        updateRecChip()
        handler.removeCallbacks(refreshEmptyState)
        if (!videoFlowing) handler.postDelayed(refreshEmptyState, EMPTY_REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshEmptyState)
        super.onPause()
    }

    private fun refreshCamPrefs() {
        (supportFragmentManager.findFragmentById(R.id.fragment_host) as? RearCamFragment)?.applyPrefs()
    }

    /** Any crash gets written to a file and shown on the next launch, with a
     *  COPY LOG button, so it can be diagnosed without adb. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                crashFile.writeText(
                    "CarApp $VERSION crash on Android ${Build.VERSION.RELEASE} " +
                        "(${Build.MANUFACTURER} ${Build.MODEL}), thread ${thread.name}:\n" +
                        android.util.Log.getStackTraceString(throwable)
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun showCrashLogIfAny() {
        if (!crashFile.exists()) return
        showingCrashLog = false // allow setStatus to write the log text
        setStatus("PREVIOUS CRASH. Tap COPY LOG and paste it to Claude:\n\n" + crashFile.readText())
        showingCrashLog = true
        copyButton?.visibility = View.VISIBLE
        dismissButton?.visibility = View.VISIBLE
        updateOverlay()
    }

    override fun onDestroy() {
        // Close the mp4 before the fragment goes: an unfinalized file will not
        // play. The full stop, not just the fragment's, so the clip still gets
        // checked and (if sound is off) stripped, that work is on its own
        // thread with the application context for exactly this case.
        if (isRecording) stopRecording()
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(detachReceiver)
        unregisterReceiver(usbEventReceiver)
        super.onDestroy()
    }
}
