package com.lazyneil.lazycar.rear

import com.lazyneil.lazycar.R

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * The theme choice lives in prefs and has to be applied the same way by every
 * activity, before its content view is set, otherwise the settings page and
 * the camera screen drift apart after a change.
 */
object Theming {

    /** Pure black by default: this thing lives on a dash, and at night a grey
     *  surface behind the video is a reflection in the windscreen. */
    const val DEFAULT = "amoled"

    fun prefs(context: Context) = context.getSharedPreferences("rearview", 0)!!

    fun current(context: Context): String =
        prefs(context).getString("theme", DEFAULT) ?: DEFAULT

    /** Call before super.onCreate(). */
    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (current(context)) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark", "amoled" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Call after super.onCreate(), before setContentView(). Material You on
     *  Android 12+, then pure-black surfaces on top if AMOLED is chosen. */
    fun applyToActivity(activity: Activity) {
        DynamicColors.applyToActivityIfAvailable(activity)
        if (current(activity) == "amoled") {
            activity.theme.applyStyle(R.style.ThemeOverlay_RearView_Amoled, true)
        }
    }
}
