package com.lazyneil.lazycar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

class GoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val p = Prefs(ctx)
        val st = GoAction.effectiveState(p)
        // Tap goes straight to the foreground service (user-initiated, so an FGS start is allowed).
        // It must never open a LazyCar screen; GoService decides GO vs STOP itself.
        val pi = PendingIntent.getForegroundService(
            ctx, 0, Intent(ctx, GoService::class.java).setAction(GoService.ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Always the car glyph; state shows in the pill colour only. IDLE: accent pill + car.
        // ON: red pill + white car. BUSY: grey pill + spinner (glyph hidden).
        val busy = st == GoAction.STARTING || st == GoAction.STOPPING
        val accent = Accent.color(ctx)
        val on = st == GoAction.ON
        val pillColor = when {
            busy -> 0xFF22252B.toInt()
            on -> 0xFFFF5252.toInt()
            else -> accent
        }
        val glyphTint = if (on) 0xFF000000.toInt() else Accent.onColor(accent)
        val pill = android.content.res.ColorStateList.valueOf(pillColor)
        val desc = when (st) {
            GoAction.ON -> "CarApp STOP"; GoAction.STARTING -> "CarApp starting"
            GoAction.STOPPING -> "CarApp stopping"; else -> "CarApp GO"
        }
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_go)
            v.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_bg)
            v.setColorStateList(R.id.widget_button, "setBackgroundTintList", pill)
            v.setImageViewResource(R.id.widget_glyph, R.drawable.ic_car_glyph)
            v.setInt(R.id.widget_glyph, "setColorFilter", glyphTint)
            v.setViewVisibility(R.id.widget_glyph, if (busy) View.INVISIBLE else View.VISIBLE)
            v.setViewVisibility(R.id.widget_progress, if (busy) View.VISIBLE else View.GONE)
            v.setContentDescription(R.id.widget_button, desc)
            v.setOnClickPendingIntent(R.id.widget_button, pi)
            mgr.updateAppWidget(id, v)
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, GoWidget::class.java))
            if (ids.isNotEmpty()) GoWidget().onUpdate(ctx, mgr, ids)
        }
    }
}
