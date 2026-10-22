package com.lazyneil.lazycar

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** No-UI trampoline for the app button, the widget and the notification's Shut down: decides the action from state,
 *  hands it to GoService. With the helper off it opens the system screens for the toggles GO or Shut down needs, while
 *  CarApp is still in front (Android blocks them from the background). */
class GoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(applicationContext)
        val svc = Intent(this, GoService::class.java)
        val st = GoAction.effectiveState(p)
        when {
            intent?.getStringExtra("action") == "stop" -> svc.putExtra("action", "stop")   // Shut down always restores, whatever the state
            st == GoAction.IDLE -> svc.putExtra("action", "go")
            st == GoAction.ON -> svc.putExtra("action", "stop")
            else -> { android.util.Log.e("LazyCar", "GoActivity ignored tap (transitioning)"); finish(); return }
        }
        android.util.Log.e("LazyCar", "GoActivity -> service ${svc.getStringExtra("action")}")
        startForegroundService(svc)
        if (SplitService.self == null && !Role.isTablet(this)) GoAction.openScreens(this,
            if (svc.getStringExtra("action") == "go") listOfNotNull(GoAction.HOTSPOT.takeIf { GoAction.hotspotOn(this) != true },
                GoAction.DATA.takeIf { !GoAction.dataOn(this) }, GoAction.LOCATION.takeIf { !GoAction.locationOn(this) })
            else GoAction.restoreList(p))
        finish()
    }
}
