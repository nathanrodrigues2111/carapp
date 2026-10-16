package com.lazyneil.lazycar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lazyneil.lazycar.gps.GpsService

/** Tablet home screen "Drive" button: phone GPS on, join the phone hotspot, open the chosen apps. */
class DriveWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getBroadcast(ctx, 0,
            Intent(ctx, DriveWidget::class.java).setAction(ACTION_DRIVE), PendingIntent.FLAG_IMMUTABLE)
        val accent = Accent.color(ctx)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_drive)
            v.setColorStateList(R.id.widget_button, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(accent))
            v.setInt(R.id.widget_glyph, "setColorFilter", Accent.onColor(accent))
            v.setTextColor(R.id.widget_label, Accent.onColor(accent))
            v.setOnClickPendingIntent(R.id.widget_button, pi)
            mgr.updateAppWidget(id, v)
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_DRIVE) return
        Prefs(ctx).shutDown = false
        GpsService.startIfIdle(ctx)
        TapService.joinHotspot(wifiOn = true)   // the tablet's GO: may switch Wi-Fi on, Shut down switches it back off
        TapService.launchApps(ctx)
    }

    companion object {
        const val ACTION_DRIVE = "com.lazyneil.lazycar.DRIVE"
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, DriveWidget::class.java))
            if (ids.isNotEmpty()) DriveWidget().onUpdate(ctx, mgr, ids)
        }
    }
}
