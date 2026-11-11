package com.example.installlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.content.Intent
import org.json.JSONObject
import java.util.UUID

internal object TemporaryLinks {
    const val CHANNEL = "temporary_links"
    private fun prefs(c: Context) = c.getSharedPreferences("temporary_links", Context.MODE_PRIVATE)
    private fun read(c: Context): List<TemporaryLink> = prefs(c).all.values.filterIsInstance<String>().mapNotNull { raw ->
        try {
            val o = JSONObject(raw)
            val url = TemporaryLinkRules.normalize(o.getString("url")) ?: return@mapNotNull null
            TemporaryLink(o.getString("id"), url, o.getLong("wall"), o.getLong("elapsed"), o.getInt("boot"), o.getBoolean("notified"))
        } catch (_: Exception) { null }
    }
    private fun save(c: Context, link: TemporaryLink): Boolean = prefs(c).edit().putString(link.id,
        JSONObject().put("id", link.id).put("url", link.url).put("wall", link.expiresWall)
            .put("elapsed", link.expiresElapsed).put("boot", link.boot).put("notified", link.notified).toString()).commit()

    @Synchronized fun all(c: Context, now: WatchdogClock = WatchdogStore.clock(c)) =
        read(c).filter { it.ready || it.active(now) }

    /** Called only by the authenticated whitelist task after its saved delay. */
    @Synchronized fun approve(c: Context, input: String): Boolean {
        val url = TemporaryLinkRules.normalize(input) ?: return false
        // A second completed request must not restart an existing access window.
        if (all(c).any { it.url == url }) return true
        return save(c, TemporaryLink(UUID.randomUUID().toString(), url))
    }

    @Synchronized fun open(c: Context, id: String, now: WatchdogClock = WatchdogStore.clock(c)): TemporaryLink? {
        val link = read(c).firstOrNull { it.id == id }?.open(now) ?: return null
        if (!save(c, link)) return null
        c.getSystemService(NotificationManager::class.java).cancel(id, 0)
        return link
    }

    @Synchronized fun remove(c: Context, id: String) {
        check(prefs(c).edit().remove(id).commit())
        c.getSystemService(NotificationManager::class.java).cancel(id, 0)
    }

    fun activeUrls(c: Context): List<String> = if (!BrowserKeywordService.isReady(c)) emptyList() else {
        val now = WatchdogStore.clock(c)
        all(c, now).filter { it.active(now) }.map { it.url }
    }

    fun nextDelay(c: Context): Long? {
        val now = WatchdogStore.clock(c)
        return read(c).filterNot { it.ready }.minOfOrNull { it.remaining(now) }
    }

    @Synchronized fun reconcile(c: Context, now: WatchdogClock = WatchdogStore.clock(c),
        notify: (TemporaryLink) -> Boolean = { notifyReady(c, it) }) {
        for (link in read(c)) {
            if (!link.ready && !link.active(now)) remove(c, link.id)
            else if (link.ready && !link.notified && notify(link)) save(c, link.copy(notified = true))
        }
    }

    internal fun openIntent(c: Context, id: String) = Intent(c, TemporaryLinkActivity::class.java)
        .setData(Uri.parse("installlock://temporary-link/$id")).putExtra("link_id", id)

    fun notificationsEnabled(c: Context): Boolean {
        val manager = c.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun notifyReady(c: Context, link: TemporaryLink): Boolean {
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Temporary links", NotificationManager.IMPORTANCE_DEFAULT))
        if (!notificationsEnabled(c)) return false
        return try {
            val open = PendingIntent.getActivity(c, 0, openIntent(c, link.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(link.id, 0, Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Link available to view now")
                .setContentText("Your whitelist delay is over. Open for 1 day of access.")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build())
            true
        } catch (_: SecurityException) { false }
    }
}
