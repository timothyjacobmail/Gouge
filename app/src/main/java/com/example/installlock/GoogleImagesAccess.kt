package com.example.installlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal object GoogleImagesAccess {
    private const val CHANNEL = "temporary_google_images"
    private const val NOTIFICATION = 8104
    private fun prefs(c: Context) = c.getSharedPreferences("temporary_google_images", Context.MODE_PRIVATE)
    private fun read(c: Context): GoogleImagesWindow {
        val p = prefs(c)
        return GoogleImagesWindow(p.getLong("until", 0), p.getInt("boot", -1), p.getLong("duration", 0))
    }

    fun remaining(c: Context, now: WatchdogClock = WatchdogStore.clock(c)) = read(c).remaining(now)
    fun bypassesImageBlock(c: Context, host: String) = GoogleImagesWindow.isImageHost(host) && remaining(c) > 0
    fun nextDelay(c: Context): Long? = remaining(c).takeIf { it > 0 }

    /** Only called by the authenticated delayed DNS-unblock task. Never disables the VPN. */
    @Synchronized fun start(c: Context, minutes: Int): Boolean {
        val next = read(c).start(minutes, Store.timingConfig(c)[TimingOption.MAX_UNLOCK], WatchdogStore.clock(c)) ?: return false
        if (!prefs(c).edit().putLong("until", next.until).putInt("boot", next.boot)
                .putLong("duration", next.duration).commit()) return false
        try { notifyStarted(c, minutes) } catch (_: RuntimeException) { /* Access also appears in Gouge. */ }
        return true
    }

    @Synchronized fun end(c: Context) {
        check(prefs(c).edit().clear().commit())
        c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }

    @Synchronized fun reconcile(c: Context) {
        if (read(c).duration > 0 && remaining(c) == 0L) end(c)
    }

    private fun notifyStarted(c: Context, minutes: Int) {
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Temporary Google Images", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, NOTIFICATION,
            Intent(c, MainActivity::class.java).setAction(MainActivity.ACTION_GOOGLE_IMAGES)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(NOTIFICATION, Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Google Images is available now")
                .setContentText("Your delay is over. Image access lasts $minutes minutes. Other locks still apply.")
                .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(minutes * 60_000L)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build())
        } catch (_: SecurityException) { /* The access window is visible in Gouge if notifications are disabled. */ }
    }
}
