package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SubredditPasswordRecoveryTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()
    private val prefix = "subreddit_recovery_${System.nanoTime()}_"
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(name)
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    private val scope = TaskScope(TaskKind.REDDIT_ALLOW)
    private val task = DelayedTask(scope, "Allow kotlin", mapOf("subreddit" to "kotlin"))
    private fun legacy() {
        val prefs = context.getSharedPreferences("install_lock", 0)
        val data = object : TaskData {
            override fun read() = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            override fun write(changes: Map<String, String>): Boolean = prefs.edit().also { edit ->
                changes.forEach { (k, v) -> edit.putString(k, v) }
            }.commit()
        }
        assertNull(TaskVault(data).install(listOf(NewTaskPassword(scope, "LostPassword1")), false))
    }
    @After fun clean() {
        ScheduledTasks.all(context).forEach { ScheduledTasks.cancel(context, it.id) }
        names.forEach { base.getSharedPreferences(prefix + it, 0).edit().clear().commit() }
    }
    @Test fun recoveryWaitsThenCreatesNativeCredentialAndAppliesOnlyTheRequestedSubredditOnce() {
        legacy()
        val entry = ScheduledTasks.confirmSubredditRecovery(context, task)
        assertEquals(15 * 60_000L, entry.countdown.duration)
        assertEquals(entry.id, ScheduledTasks.confirmSubredditRecovery(context, task).id)
        val due = WatchdogClock(0, entry.countdown.dueElapsed, entry.countdown.boot)
        var calls = 0
        val execute: (DelayedTask, String) -> Boolean = { saved, password ->
            calls++; assertEquals(task, saved); saved.execute(context, password)
        }
        ScheduledTasks.applyDue(context, due.copy(elapsed = due.elapsed - 1), execute)
        assertEquals(0, calls)
        assertFalse(NativeTaskPasswords.hasSavedPassword(context, scope))
        assertTrue(RedditStore.allowed(context).isEmpty())
        ScheduledTasks.applyDue(context, due, execute)
        assertEquals(1, calls)
        assertNotNull(NativeTaskPasswords.password(context, scope))
        assertEquals(setOf("kotlin"), RedditStore.allowed(context))
        assertTrue("reddit.com/r/kotlin/" in Store.activePatterns(context))
        assertFalse(ScheduledTasks.canRecoverSubredditPassword(context, scope))
        ScheduledTasks.applyDue(context, due, execute)
        assertEquals(1, calls)
    }
    @Test fun cancellationAndNativeCredentialsCannotBeBypassedByRecovery() {
        legacy()
        val entry = ScheduledTasks.confirmSubredditRecovery(context, task)
        ScheduledTasks.cancel(context, entry.id)
        ScheduledTasks.applyDue(context, WatchdogClock(0, entry.countdown.dueElapsed, entry.countdown.boot)) { _, _ -> fail("Cancelled recovery ran"); false }
        assertFalse(NativeTaskPasswords.hasSavedPassword(context, scope))
        assertTrue(NativeTaskPasswords.attach(context, scope, "LostPassword1"))
        assertFalse(ScheduledTasks.canRecoverSubredditPassword(context, scope))
        try { ScheduledTasks.confirmSubredditRecovery(context, task); fail("Native credential replaced") }
        catch (_: IllegalStateException) { }
        assertFalse(ScheduledTasks.canRecoverSubredditPassword(context, TaskScope(TaskKind.REDDIT_OFF)))
    }
}
