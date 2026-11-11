package com.example.installlock

import android.content.Context

/** Opening the countdown again preserves its deadline; cancelling starts the next request afresh. */
internal object PlayStoreDelayStore {
    const val PACKAGE = "com.android.vending"
    const val DELAY_MINUTES = 2
    fun minutes(c: Context) = prefs(c).getInt("minutes", DELAY_MINUTES)
    fun enabled(c: Context) = minutes(c) > 0
    fun save(c: Context, minutes: Int, password: String): Boolean {
        if (minutes !in 0..10080) return false
        return TaskPasswords.run(c, TaskScope(TaskKind.PLAY_STORE_DELAY), password) {
            val saved = prefs(c).edit().putInt("minutes", minutes).remove("due").remove("boot").commit()
            if (saved) endSession()
            saved
        }
    }
    @Volatile var sessionOpen = false
        private set
    fun beginSession(c: Context) { sessionOpen = true; cancel(c) }
    fun endSession() { sessionOpen = false }
    private fun prefs(c: Context) = c.getSharedPreferences("play_store_open_delay", Context.MODE_PRIVATE)

    @Synchronized fun start(c: Context, now: WatchdogClock = WatchdogStore.clock(c)): TaskCountdown {
        val p = prefs(c)
        val timer = if (p.contains("due"))
            TaskCountdown(p.getLong("due", 0), p.getInt("boot", -1), minutes(c) * 60_000L).reconcile(now)
        else TaskCountdown.start(minutes(c), now)
        if (!p.contains("due") || p.getLong("due", 0) != timer.dueElapsed || p.getInt("boot", -1) != timer.boot)
            check(p.edit().putLong("due", timer.dueElapsed).putInt("boot", timer.boot).commit())
        return timer
    }

    @Synchronized fun cancel(c: Context) { check(prefs(c).edit().remove("due").remove("boot").commit()) }
}
