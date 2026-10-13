package com.lazyneil.lazycar.rear

import com.lazyneil.lazycar.R

import android.app.Activity
import android.content.Intent

/**
 * Page transitions, applied per navigation rather than left to the theme.
 *
 * The theme does declare windowAnimationStyle, but relying on it alone gave no
 * visible movement at all when opening an inner page (Settings → Recordings):
 * whichever window the transition controller reads its animation from, it was
 * not ours. overridePendingTransition is applied to the transition we just
 * asked for, so it always lands.
 *
 * Deprecated in API 34 in favour of overrideActivityTransition, which needs
 * targetSdk 34. Kept as is from RearView (it was targetSdk 28); still works on 35, so this is
 * still the correct call here.
 */
object Nav {

    /** Forward: the new page slides in from the right, the old one drifts left. */
    @Suppress("DEPRECATION")
    fun open(activity: Activity, intent: Intent) {
        activity.startActivity(intent)
        activity.overridePendingTransition(R.anim.nav_enter, R.anim.nav_exit)
    }

    /** Back: the reverse. Call right after finish(), from every route out of a
     *  page, toolbar arrow, system back and gesture all end up in finish(). */
    @Suppress("DEPRECATION")
    fun close(activity: Activity) {
        activity.overridePendingTransition(R.anim.nav_pop_enter, R.anim.nav_pop_exit)
    }
}
