package com.example.installlock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class BetaStatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = PendingIntent.getActivity(context, 8111, Intent(context, BetaActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val enabled = BetaStore.enabled(context)
        val profiles = if (enabled) BetaStore.config(context).profiles else emptyList()
        val profile = profiles.firstOrNull { it.enabled }
        val summary = profile?.let { p -> val u = BetaStore.usage(context,p,"*");
            "${u.used/60000}${if (p.groupMinutes>0) "/${p.groupMinutes}" else ""} min · ${u.count}${if (p.shortCount>0) "/${p.shortCount}" else ""} shorts"
        }.orEmpty()
        ids.forEach { manager.updateAppWidget(it, LockWidgetStyle.views(context, profile?.let { "${it.name} · beta" } ?: "Beta usage", if (!enabled) "Beta features are off" else summary.ifEmpty { "Enable a profile to track progress" }, "Open beta usage and profiles").apply {
            setOnClickPendingIntent(R.id.focus_lock_widget, pending)
        }) }
    }
    companion object {
        fun refresh(c: Context) { val manager = AppWidgetManager.getInstance(c); BetaStatsWidget().onUpdate(c, manager, manager.getAppWidgetIds(ComponentName(c, BetaStatsWidget::class.java))) }
    }
}
