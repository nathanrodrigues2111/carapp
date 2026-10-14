package com.lazyneil.lazycar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Short-lived foreground service that owns the GO/STOP sequence. Running as a foreground service
 * keeps the process (and GoAction's postDelayed chain + BT-enable receiver) alive independently of
 * any activity - GoActivity finishes immediately, so the system-enable dialog can take focus.
 */
class GoService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        TapService.ensureEnabled(this)   // widget path: GO needs the taps
        com.lazyneil.lazycar.gps.GpsService.startIfIdle(this)
        // Widget tap: derive GO vs STOP here so no LazyCar activity ever has to open.
        val action = if (intent?.action == ACTION_TOGGLE) {
            when (GoAction.effectiveState(Prefs(applicationContext))) {
                GoAction.IDLE -> "go"
                GoAction.ON -> "stop"
                else -> { android.util.Log.e("LazyCar", "widget tap ignored (transitioning)"); null }
            }
        } else intent?.getStringExtra("action")
        val dur = when (action) {
            "stop" -> GoAction.stop(applicationContext)
            "volume" -> { GoAction.applyVolumesNow(applicationContext); 4000L }
            "go" -> GoAction.run(applicationContext)
            else -> 0L    // null / transitioning toggle: nothing to do, just fall through and stop
        }
        // Live until the real end of the sequence: runSequence pushes endAt out once it knows its steps.
        GoAction.endAt = maxOf(GoAction.endAt, SystemClock.uptimeMillis() + dur)
        val h = Handler(Looper.getMainLooper())
        h.postDelayed(object : Runnable { override fun run() {
            val left = GoAction.endAt - SystemClock.uptimeMillis()
            if (left > 0) h.postDelayed(this, left + 500L) else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        } }, dur + 500L)
        return START_NOT_STICKY
    }

    companion object { const val ACTION_TOGGLE = "com.lazyneil.lazycar.TOGGLE" }

    private fun notification(): Notification {
        val chId = "lazycar_go"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(chId) == null)
                nm.createNotificationChannel(NotificationChannel(chId, "Car setup", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, chId)
            .setContentTitle("CarApp")
            .setContentText("Setting up for the car…")
            .setSmallIcon(R.drawable.ic_car_glyph)
            .build()
    }
}
