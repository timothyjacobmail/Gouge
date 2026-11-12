package com.example.installlock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** One-tap start using the last session settings; never replaces a running session. */
class FocusLockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val start = Intent(context, FocusLockWidget::class.java).setAction(ACTION_START)
        val pending = PendingIntent.getBroadcast(context, 8102, start,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ids.forEach { id ->
            manager.updateAppWidget(id, LockWidgetStyle.views(context, context.getString(R.string.focus_lock),
                context.getString(R.string.focus_lock_widget_hint), context.getString(R.string.focus_lock_widget_description)).apply {
                setOnClickPendingIntent(R.id.focus_lock_widget, pending)
            })
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START) { super.onReceive(context, intent); return }
        val result = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val minutes = FocusLockStore.quickStartMinutes(app)
                val message = when {
                    FocusLockStore.remaining(app) > 0 -> "Focus lock is already active."
                    FocusLockStore.start(app, minutes, FocusLockStore.quickStartTargets(app)) -> {
                        LockService.start(app)
                        Policy.sweep(app)
                        "Focus lock started for $minutes minutes."
                    }
                    else -> "Could not start Focus lock. Check setup and apps in Gouge."
                }
                Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
            } catch (error: RuntimeException) {
                android.util.Log.e("Gouge", "Widget focus lock failed", error)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, "Check Focus lock status in Gouge.", Toast.LENGTH_LONG).show()
                }
            } finally { result.finish() }
        }.start()
    }

    companion object {
        private const val ACTION_START = "com.example.installlock.widget.START_FOCUS_LOCK"
    }
}
