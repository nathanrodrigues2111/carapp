package com.lazyneil.lazycar

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** No-UI launcher: decides the action from state, hands it to GoService, and finishes at once. */
class GoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(applicationContext)
        val svc = Intent(this, GoService::class.java)
        when {
            intent?.getBooleanExtra("volumeOnly", false) == true -> svc.putExtra("action", "volume")
            GoAction.effectiveState(p) == GoAction.IDLE -> svc.putExtra("action", "go")
            GoAction.effectiveState(p) == GoAction.ON -> svc.putExtra("action", "stop")
            else -> { android.util.Log.e("LazyCar", "GoActivity ignored tap (transitioning)"); finish(); return }
        }
        android.util.Log.e("LazyCar", "GoActivity -> service ${svc.getStringExtra("action")}")
        startForegroundService(svc)
        finish()
    }
}
