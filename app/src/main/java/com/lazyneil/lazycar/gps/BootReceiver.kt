package com.lazyneil.lazycar.gps


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (com.lazyneil.lazycar.Prefs(ctx).shutDown) return   // shut down: nothing starts until the app is opened or GO
        ctx.getSharedPreferences("lazycar", Context.MODE_PRIVATE).edit().putLong("bootAt", System.currentTimeMillis()).apply()
        com.lazyneil.lazycar.Role.applyComponents(ctx)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {   // nothing survives a reboot; an update keeps the state
            com.lazyneil.lazycar.Prefs(ctx).state = com.lazyneil.lazycar.GoAction.IDLE; com.lazyneil.lazycar.GoWidget.refresh(ctx)
        }
        if (com.lazyneil.lazycar.Role.isTablet(ctx)) {
            com.lazyneil.lazycar.PhoneLink.start(ctx)
        }
        val m = GpsService.autoMode(ctx)
        if (m != "stop") GpsService.launch(ctx, m)
    }
}
