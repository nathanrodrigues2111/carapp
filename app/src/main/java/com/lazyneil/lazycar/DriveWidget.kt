package com.lazyneil.lazycar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Tablet home screen "Drive" button: opens CarApp, which starts the GPS link, suggests the phone hotspot and
 *  reminds of the Maps + CarApp pair icon. An activity PendingIntent: Android allows it from a widget tap. */
class DriveWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, DashboardActivity::class.java).putExtra("launcher", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
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

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, DriveWidget::class.java))
            if (ids.isNotEmpty()) DriveWidget().onUpdate(ctx, mgr, ids)
        }
    }
}
