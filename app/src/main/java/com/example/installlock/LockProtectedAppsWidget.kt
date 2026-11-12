package com.example.installlock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/** Explicit, immutable widget action; only strengthens existing protection. */
class LockProtectedAppsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = PendingIntent.getBroadcast(context, 8103,
            Intent(context, LockProtectedAppsWidget::class.java).setAction(ACTION_LOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ids.forEach { id -> manager.updateAppWidget(id, LockWidgetStyle.views(context,
            context.getString(R.string.lock_protected_widget_title), context.getString(R.string.lock_protected_widget_hint),
            context.getString(R.string.lock_protected_widget_name)).apply {
                setOnClickPendingIntent(R.id.focus_lock_widget, pending)
            }) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOCK) { super.onReceive(context, intent); return }
        val result = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                Store.lockProtectedApps(app)
                Policy.sweep(app)
                LockService.start(app)
            } catch (error: RuntimeException) {
                android.util.Log.e("Gouge", "Widget lock enforcement will retry through the protection service", error)
            } finally { result.finish() }
        }.start()
    }

    companion object {
        private const val ACTION_LOCK = "com.example.installlock.widget.LOCK_PROTECTED_APPS"
    }
}
