package com.lazyneil.lazycar.gps


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ctx.getSharedPreferences("lazycar", Context.MODE_PRIVATE).edit().putLong("bootAt", System.currentTimeMillis()).apply()
        com.lazyneil.lazycar.Role.applyComponents(ctx)
        val m = GpsService.autoMode(ctx)
        if (m != "stop") GpsService.launch(ctx, m)
    }
}
