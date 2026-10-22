package com.lazyneil.lazycar

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/** No-UI trampoline for the app button, the widget and the notification's Shut down: decides the action from state and
 *  hands it to GoService. Phone GO first asks, the standard way, for what is off: location with the system "Turn on
 *  location" dialog, mobile data and hotspot with a CarApp dialog that opens their settings screen. Each step re-checks
 *  when the user comes back. Shut down leaves them as they are and only offers to turn off a hotspot GO turned on. */
class GoActivity : Activity() {
    private val steps = mutableListOf<String>()
    private var waiting = false
    private var after: () -> Unit = {}
    private var go = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(applicationContext)
        val st = GoAction.effectiveState(p)
        val action = when {
            intent?.getStringExtra("action") == "stop" -> "stop"   // Shut down always restores, whatever the state
            st == GoAction.IDLE -> "go"
            st == GoAction.ON -> "stop"
            else -> { log("GoActivity ignored tap (transitioning)"); finish(); return }
        }
        log("GoActivity -> $action"); go = action == "go"
        val svc = { startForegroundService(Intent(this, GoService::class.java).putExtra("action", action)); Unit }
        if (Role.isTablet(this) || savedInstanceState != null) { if (savedInstanceState == null) svc(); finish(); return }
        if (action == "go") {
            val ap = GoAction.hotspotOn(this); val data = GoAction.dataOn(this); val loc = GoAction.locationOn(this)
            p.snapApWasOn = ap == true
            log("check: hotspot=$ap data=$data location=$loc")
            steps += listOfNotNull(GoAction.LOCATION.takeIf { !loc }, GoAction.DATA.takeIf { !data }, GoAction.HOTSPOT.takeIf { ap != true })
            after = svc   // tablet wake and GPS once the phone side is up
        } else {
            if (GoAction.hotspotByGo(this, p)) steps += GoAction.HOTSPOT
            svc()   // the service tells the tablet first, over the hotspot
        }
        waiting = true   // the first step runs from the first onResume
    }

    override fun onResume() { super.onResume(); if (waiting) { waiting = false; next() } }

    private fun next() {
        val s = steps.removeFirstOrNull() ?: run { after(); finish(); return }
        val ok = when (s) { GoAction.HOTSPOT -> GoAction.hotspotOn(this) == true; GoAction.DATA -> GoAction.dataOn(this); else -> GoAction.locationOn(this) } == go
        log("step: $s ${if (ok) "already right" else "ask"}")
        if (ok) { next(); return }
        if (s == GoAction.LOCATION) {
            val req = LocationSettingsRequest.Builder().addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()).build()
            LocationServices.getSettingsClient(this).checkLocationSettings(req)
                .addOnSuccessListener { next() }
                .addOnFailureListener { e -> try { waiting = true; (e as ResolvableApiException).startResolutionForResult(this, 1) }
                    catch (x: Exception) { log("location dialog: $x"); open(GoAction.screen(s)) } }
            return
        }
        val msg = when {
            !go -> "Start turned on the hotspot. Turn it off now?"
            s == GoAction.HOTSPOT -> "Hotspot is off. CarApp needs it for the tablet."
            else -> "Mobile data is off. CarApp needs it for the tablet."
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage(msg).setCancelable(false)
            .setPositiveButton("Open settings") { _, _ -> open(GoAction.screen(s)) }
            .setNegativeButton(if (go) "Skip" else "Keep it on") { _, _ -> next() }.show()
    }

    private fun open(i: Intent) = try { waiting = true; startActivity(i) } catch (e: Exception) { log("open ${i.action}: $e"); waiting = false; next() }
    private fun log(m: String) = android.util.Log.e("LazyCar", m)
}
