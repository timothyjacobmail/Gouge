package com.example.installlock

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import android.os.SystemClock
import com.example.delayedsecrect.InlineSecretChoice
import com.example.delayedsecrect.RevealState
import com.example.delayedsecrect.RevealTimer
import com.example.delayedsecrect.Secret
import com.example.delayedsecrect.SecretStore
import com.example.delayedsecrect.SettingsStore
import com.example.delayedsecrect.TimerStore
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class ScheduledTask(
    val id: String, val task: DelayedTask, val secretId: String,
    val countdown: TaskCountdown, val result: String = ""
) {
    fun encode(): String = JSONObject().put("id", id).put("task", task.encode())
        .put("secret", secretId).put("due", countdown.dueElapsed).put("boot", countdown.boot)
        .put("duration", countdown.duration).put("result", result).toString()

    companion object {
        fun decode(raw: String): ScheduledTask? = try {
            val obj = JSONObject(raw)
            val duration = obj.getLong("duration")
            if (duration <= 0) null else DelayedTask.decode(obj.getString("task"))?.let {
                ScheduledTask(obj.getString("id"), it, obj.getString("secret"),
                    TaskCountdown(obj.getLong("due"), obj.getInt("boot"), duration), obj.optString("result"))
            }
        } catch (_: Exception) { null }
    }
}

/** Persists exact operations and secret IDs, never passwords or reusable task authorization. */
internal object ScheduledTasks {
    private fun prefs(c: Context) = c.getSharedPreferences("scheduled_tasks", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean()

    private fun read(c: Context) = prefs(c).all.values.filterIsInstance<String>().mapNotNull(ScheduledTask::decode)
    private fun save(c: Context, entry: ScheduledTask) {
        check(prefs(c).edit().putString(entry.id, entry.encode()).commit()) { "Could not save the scheduled task." }
    }

    @Synchronized fun all(c: Context, now: WatchdogClock = WatchdogStore.clock(c)): List<ScheduledTask> =
        read(c).map { entry ->
            val next = if (entry.result.isEmpty()) entry.copy(countdown = entry.countdown.reconcile(now)) else entry
            if (next != entry) save(c, next)
            next
        }.sortedBy { it.countdown.dueElapsed }

    /** Native credentials are ready immediately after setup. Legacy secrets are imported once. */
    fun choices(c: Context, scope: TaskScope): List<InlineSecretChoice> {
        if (!TaskPasswords.has(c, scope)) return emptyList()
        // Listing a task needs only credential metadata. Decrypt and authenticate on confirmation.
        if (!NativeTaskPasswords.hasSavedPassword(c, scope)) {
            val matching = SecretStore(c).getAll().filter { TaskPasswords.vault(c).matchesStoredSecret(scope, it.value) }
            if (matching.isEmpty() || matching.any { hardLocked(c, it) }) return emptyList()
            val delay = matching.maxOf { it.delayMinutes ?: SettingsStore(c).getDelayMinutes() }
            if (!NativeTaskPasswords.attach(c, scope, matching.first().value, delay)) return emptyList()
        }
        return listOf(InlineSecretChoice(NativeTaskPasswords.reference(c, scope), scope.title, NativeTaskPasswords.delay(c, scope)))
    }

    fun attachExisting(c: Context, scope: TaskScope, password: String): Boolean {
        val vault = TaskPasswords.vault(c)
        val matching = SecretStore(c).getAll().filter { vault.matchesStoredSecret(scope, it.value) }
        if (matching.any { hardLocked(c, it) }) return false
        val delay = matching.maxOfOrNull { it.delayMinutes ?: SettingsStore(c).getDelayMinutes() } ?: 1
        return NativeTaskPasswords.attach(c, scope, password, delay)
    }

    private fun hardLocked(c: Context, secret: Secret): Boolean =
        RevealTimer(secret.id, 0, 0, TimerStore(c)).currentState(claim = false).state == RevealState.HARD_LOCKED

    /** Recovery is deliberately limited to adding a subreddit with an old hash-only credential. */
    fun canRecoverSubredditPassword(c: Context, scope: TaskScope) = scope.kind == TaskKind.REDDIT_ALLOW &&
        TaskPasswords.ready(c) && TaskPasswords.has(c, scope) && !NativeTaskPasswords.hasSavedPassword(c, scope)

    @Synchronized fun confirmSubredditRecovery(c: Context, task: DelayedTask): ScheduledTask {
        val scope = requireNotNull(TaskScope.decode(task.scope))
        check(canRecoverSubredditPassword(c, scope)) { "This task is not eligible for the old-password migration." }
        check(TaskPasswords.remaining(c, scope) == 0L) { "Wait for this task's password lockout to end." }
        require(RedditRules.normalize(JSONObject(task.details).getString("subreddit")) != null)
        val matching = SecretStore(c).getAll().filter { TaskPasswords.vault(c).matchesStoredSecret(scope, it.value) }
        check(matching.none { hardLocked(c, it) }) { "This saved password is hard locked. Wait for that lock to end." }
        val minimum = matching.maxOfOrNull { it.delayMinutes ?: SettingsStore(c).getDelayMinutes() } ?: 1
        val reference = "recovery:" + NativeTaskPasswords.reference(c, scope)
        val now = WatchdogStore.clock(c)
        all(c, now).firstOrNull { it.result.isEmpty() && it.task == task && it.secretId == reference }?.let { return it }
        val delay = maxOf(15, minimum, NativeTaskPasswords.delay(c, scope))
        val entry = ScheduledTask(UUID.randomUUID().toString(), task, reference, TaskCountdown.start(delay, now))
        save(c, entry); reschedule(c); return entry
    }

    @Synchronized fun confirm(c: Context, task: DelayedTask, secretId: String): ScheduledTask {
        val scope = requireNotNull(TaskScope.decode(task.scope))
        require(scope.kind != TaskKind.ENROLL_APP) { "Use the password setup form for this task." }
        if (secretId.startsWith("native:")) {
            check(secretId == NativeTaskPasswords.reference(c, scope)) { "The task password changed. Reopen this task." }
            val password = NativeTaskPasswords.password(c, scope) ?: error("Enter this task's current password once to finish upgrading.")
            check(TaskPasswords.check(c, scope, password)) { "This task is locked out. Wait before confirming again." }
            val now = WatchdogStore.clock(c)
            all(c, now).firstOrNull { it.result.isEmpty() && it.task == task && it.secretId == secretId }?.let { return it }
            val entry = ScheduledTask(UUID.randomUUID().toString(), task, secretId,
                TaskCountdown.start(NativeTaskPasswords.delay(c, scope), now))
            save(c, entry); reschedule(c); return entry
        }
        val secret = SecretStore(c).getAll().firstOrNull { it.id == secretId }
            ?: error("The saved password was removed. Reopen this task.")
        check(!hardLocked(c, secret)) { "This password is hard locked. Wait for that lock to end." }
        check(TaskPasswords.check(c, scope, secret.value)) { "The saved password changed or this task is locked out." }
        val now = WatchdogStore.clock(c)
        all(c, now).firstOrNull { it.result.isEmpty() && it.task == task && it.secretId == secretId }?.let { return it }
        val delay = secret.delayMinutes ?: SettingsStore(c).getDelayMinutes()
        val entry = ScheduledTask(UUID.randomUUID().toString(), task, secretId, TaskCountdown.start(delay, now))
        save(c, entry)
        reschedule(c)
        return entry
    }

    @Synchronized fun cancel(c: Context, id: String) {
        check(prefs(c).edit().remove(id).commit())
        reschedule(c)
    }

    fun nextDelay(c: Context): Long? {
        val now = WatchdogStore.clock(c)
        return listOfNotNull(all(c, now).filter { it.result.isEmpty() }.minOfOrNull { it.countdown.remaining(now) },
            TemporaryLinks.nextDelay(c)).minOrNull()
    }

    fun reschedule(c: Context) {
        val alarm = PendingIntent.getBroadcast(c, 0, Intent(c, ScheduledTaskReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = c.getSystemService(AlarmManager::class.java)
        val delay = nextDelay(c)
        if (delay == null) { manager.cancel(alarm); return }
        val at = SystemClock.elapsedRealtime() + delay.coerceAtLeast(1000L)
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarm)
            else manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarm)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarm)
        }
    }

    /** Commit consumption before execution, so a service restart cannot replay an app unlock. */
    @Synchronized internal fun applyDue(c: Context, now: WatchdogClock = WatchdogStore.clock(c),
        execute: (DelayedTask, String) -> Boolean = { task, password -> Policy.isOwner(c) && task.execute(c, password) }) {
        val due = all(c, now).filter { it.result.isEmpty() && it.countdown.remaining(now) == 0L }
        if (due.isEmpty()) return
        val secrets = if (due.any { !it.secretId.startsWith("native:") && !it.secretId.startsWith("recovery:") })
            SecretStore(c).getAll().associateBy { it.id } else emptyMap()
        for (entry in due) {
            save(c, entry.copy(result = "Applying; if interrupted, confirm this task again."))
            val success = try {
                if (entry.secretId.startsWith("recovery:")) {
                    synchronized(TaskPasswords) {
                        val scope = TaskScope.decode(entry.task.scope)
                        if (scope == null || !canRecoverSubredditPassword(c, scope) ||
                            entry.secretId != "recovery:" + NativeTaskPasswords.reference(c, scope)) false
                        else {
                            val password = TaskVault.generatePassword(scope)
                            val hash = c.getSharedPreferences("install_lock", Context.MODE_PRIVATE).getString("task.${scope.id}.hash", "").orEmpty()
                            val hardLock = SecretStore(c).getAll().any {
                                TaskPasswords.vault(c).matchesStoredSecret(scope, it.value) && hardLocked(c, it)
                            }
                            !hardLock && TaskPasswords.vault(c).recoverLegacySubredditPassword(hash, password) && execute(entry.task, password)
                        }
                    }
                } else if (entry.secretId.startsWith("native:")) {
                    val scope = TaskScope.decode(entry.task.scope)
                    val password = scope?.let { NativeTaskPasswords.password(c, it) }
                    scope != null && password != null && entry.secretId == NativeTaskPasswords.reference(c, scope) && execute(entry.task, password)
                } else {
                    val secret = secrets[entry.secretId]
                    secret != null && !hardLocked(c, secret) && execute(entry.task, secret.value)
                }
            } catch (_: Exception) { false }
            save(c, entry.copy(result = if (success) "Applied" else
                "Not applied. The password, lock state or task changed. Confirm again when ready."))
        }
        TemporaryLinks.reconcile(c)
        Policy.sweep(c)
        reschedule(c)
    }

    fun reconcileAsync(c: Context) {
        if (nextDelay(c) != 0L || !running.compareAndSet(false, true)) return
        val context = c.applicationContext
        executor.execute {
            try { TemporaryLinks.reconcile(context); applyDue(context) }
            catch (_: Exception) { /* Retry unconsumed tasks on the next service tick. */ }
            finally { running.set(false) }
        }
    }
}

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                TemporaryLinks.reconcile(context)
                ScheduledTasks.applyDue(context)
                Policy.syncChrome(context)
                ScheduledTasks.reschedule(context)
            }
            catch (_: Exception) { /* The foreground service retries tasks that were not consumed. */ }
            finally { pending.finish() }
        }.start()
    }
}
