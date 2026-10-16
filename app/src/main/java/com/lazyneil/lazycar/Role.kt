package com.lazyneil.lazycar

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.lazyneil.lazycar.gps.GpsService

/** Phone runs car audio + GPS send; the head unit tablet runs the reverse camera + GPS receive. */
object Role {
    const val AUTO = "auto"
    const val PHONE = "phone"
    const val TABLET = "tablet"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("lazycar", Context.MODE_PRIVATE)

    fun detected() = if (Build.MODEL == "TB351FU") TABLET else PHONE
    fun override(ctx: Context) = sp(ctx).getString("role", AUTO) ?: AUTO
    fun current(ctx: Context) = override(ctx).takeIf { it != AUTO } ?: detected()
    fun isTablet(ctx: Context) = current(ctx) == TABLET

    fun set(ctx: Context, role: String) {
        sp(ctx).edit().putString("role", role).apply()
        // The saved GPS mode belonged to the old role; fall back to the new role's default.
        ctx.getSharedPreferences("lazygps", Context.MODE_PRIVATE).edit().remove("mode").apply()
        applyComponents(ctx)
        GpsService.launch(ctx, GpsService.autoMode(ctx), save = false)
    }

    /** Camera auto launch and the Drive widget only on the tablet, the GO widget only on the phone. */
    fun applyComponents(ctx: Context) {
        val tablet = isTablet(ctx)
        toggle(ctx, "com.lazyneil.lazycar.rear.UsbLaunch", tablet)
        toggle(ctx, "com.lazyneil.lazycar.GoWidget", !tablet)
        toggle(ctx, "com.lazyneil.lazycar.DriveWidget", tablet)
        toggle(ctx, "com.lazyneil.lazycar.SplitService", true)   // both roles now; an older build disabled it on the phone
    }

    private fun toggle(ctx: Context, cls: String, on: Boolean) {
        val pm = ctx.packageManager
        val cn = ComponentName(ctx, cls)
        val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(cn) != want) pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
    }
}
