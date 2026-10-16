package com.lazyneil.lazycar

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle

/** No-UI trampoline for the app button, the widget and the notification's Shut down: decides the action from state,
 *  hands it to GoService, and opens the system dialogs GO needs while CarApp is still in front (Android blocks them
 *  from the background): "Turn on Bluetooth?" in Phone mode, the screen share consent for the tablet speaker copy. */
class GoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(applicationContext)
        val svc = Intent(this, GoService::class.java)
        val st = GoAction.effectiveState(p)
        if (intent?.getStringExtra("action") == "btoff") { GoAction.disableBt(applicationContext); finish(); return }   // Shut down notice tap
        when {
            intent?.getBooleanExtra("volumeOnly", false) == true -> svc.putExtra("action", "volume")
            intent?.getStringExtra("action") == "stop" -> svc.putExtra("action", "stop")   // Shut down always restores, whatever the state
            st == GoAction.IDLE -> svc.putExtra("action", "go")
            st == GoAction.ON -> svc.putExtra("action", "stop")
            else -> { android.util.Log.e("LazyCar", "GoActivity ignored tap (transitioning)"); finish(); return }
        }
        android.util.Log.e("LazyCar", "GoActivity -> service ${svc.getStringExtra("action")}")
        startForegroundService(svc)
        if (svc.getStringExtra("action") == "go" && !com.lazyneil.lazycar.audio.TabletSpeaker.fromTablet(this)) {
            val bt = getSystemService(BluetoothManager::class.java)?.adapter
            val btOff = bt != null && !bt.isEnabled
            SplitService.press("Start now", "Share screen", "Start", "Allow", "Turn on")
            // One system dialog at a time: a second one opened on top closed the Bluetooth one. Consent first, it opens Bluetooth after.
            if (com.lazyneil.lazycar.audio.TabletSpeaker.needsCapture(this)) com.lazyneil.lazycar.audio.StreamSender.askConsent(this, thenBt = btOff)
            else if (btOff) Ui.openSettings(this, Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), fallback = false)
        }
        finish()
    }
}
