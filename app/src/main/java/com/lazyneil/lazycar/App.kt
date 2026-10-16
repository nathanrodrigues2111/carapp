package com.lazyneil.lazycar

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Process start and real opens, one place for both:
 * - A process start while GO is ON (install, Android killed the app) brings the phone link back, so the tablet's
 *   Shut down still reaches the phone. A Shut down session stays down.
 * - CarApp coming to the front from nothing visible (launcher, recents, whatever screen restores) is a real open:
 *   it ends a Shut down and starts the link. The no-UI trampolines (GO, consent) are not opens.
 */
class App : Application() {
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        val p = Prefs(this)
        if (!Role.isTablet(this) && !p.shutDown && GoAction.effectiveState(p) == GoAction.ON) com.lazyneil.lazycar.media.MediaListener.wake(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { if (started++ == 0 && !trampoline(a)) opened(a) }
            override fun onActivityStopped(a: Activity) { started = maxOf(0, started - 1) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun trampoline(a: Activity) = a is GoActivity || a is com.lazyneil.lazycar.audio.StreamSender.Consent

    private fun opened(a: Activity) {
        Prefs(this).shutDown = false
        if (!Role.isTablet(this)) { com.lazyneil.lazycar.media.MediaListener.wake(this); return }
        com.lazyneil.lazycar.gps.GpsService.startIfIdle(a)
        Drive.join(a)
        com.lazyneil.lazycar.media.PhoneMedia.start(a)
        com.lazyneil.lazycar.audio.StreamReceiver.sync(a)
    }
}
