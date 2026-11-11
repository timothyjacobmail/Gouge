package com.example.installlock

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Upgrade migration for features removed before Gouge. Never clears current task/vault data. */
internal object RetiredFeatureCleanup {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean()
    @Volatile private var complete = false
    internal val channels = setOf("verse_alarm", "verse_alarm_warning", "voice_reminders",
        "voice_listener", "voice_quiet", "voice_playback", "lock")

    fun schedule(c: Context) {
        if (complete || !running.compareAndSet(false, true)) return
        val context = c.applicationContext
        executor.execute {
            try { clean(context); complete = true }
            catch (e: Exception) { Log.w("Gouge", "Retired feature cleanup will retry", e) }
            finally { running.set(false) }
        }
    }

    @Synchronized internal fun clean(c: Context) {
        val migration = c.getSharedPreferences("retired_features", Context.MODE_PRIVATE)
        if (migration.getBoolean("alarm_voice_v1", false)) return
        val notifications = c.getSystemService(NotificationManager::class.java)
        notifications.activeNotifications.filter { it.notification.channelId in channels }.forEach {
            notifications.cancel(it.tag, it.id)
        }
        channels.forEach(notifications::deleteNotificationChannel)

        val alarms = c.getSystemService(AlarmManager::class.java)
        fun cancel(receiver: String, action: String, code: Int, data: Uri? = null) {
            val intent = Intent().setClassName(c.packageName, receiver).setAction(action).setData(data)
            PendingIntent.getBroadcast(c, code, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
        }
        listOf("VERSE_ALARM", "VERSE_ALARM_CHANGE", "VERSE_ALARM_WARN").forEach {
            cancel("${c.packageName}.VerseAlarmReceiver", "${c.packageName}.$it", 4701)
        }
        val raw = c.getSharedPreferences("voice_reminders", Context.MODE_PRIVATE).getString("tasks", "[]")
        // A damaged obsolete task list must not prevent its files/channels being removed.
        val tasks = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        for (i in 0 until tasks.length()) {
            val id = tasks.optJSONObject(i)?.optString("id").orEmpty()
            if (!Regex("[a-fA-F0-9-]{36}").matches(id)) continue
            for (action in listOf("voice.FIRE", "voice.DONE", "voice.SNOOZE"))
                cancel("${c.packageName}.voice.VoiceReminderReceiver", action, 0, Uri.parse("installlock://voice/$id/$action"))
        }
        listOf("verse_alarm", "voice_reminders", "voice_time_practice").forEach {
            check(c.deleteSharedPreferences(it)) { "Could not delete retired preferences: $it" }
        }
        for (name in listOf("voice-model-v1", "voice-recordings"))
            check(deleteWithin(c.filesDir, File(c.filesDir, name))) { "Could not delete retired files: $name" }
        c.cacheDir.listFiles()?.filter { it.name.matches(Regex("jna-\\d+")) }?.forEach {
            check(deleteWithin(c.cacheDir, it)) { "Could not delete retired native cache" }
        }
        check(migration.edit().putBoolean("alarm_voice_v1", true).commit())
    }

    /** Refuse symlinks or any recursive target that resolves outside the named storage root. */
    private fun deleteWithin(root: File, target: File): Boolean {
        if (!target.exists()) return true
        val base = root.canonicalFile.toPath()
        val subtree = File(root.canonicalFile, target.name).toPath()
        fun remove(file: File): Boolean {
            val path = file.canonicalFile.toPath()
            if (path == base || !path.startsWith(subtree)) return false
            if (file.isDirectory) {
                val children = file.listFiles() ?: return false
                if (!children.all(::remove)) return false
            }
            return file.delete() || !file.exists()
        }
        return remove(target)
    }
}
