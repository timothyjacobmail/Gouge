package com.example.installlock

import android.content.Context
import java.time.Instant
import java.time.ZoneId

internal object BetaRuntime {
    fun nextBoundary(c: Context, now: Long): Long {
        if (!BetaStore.enabled(c)) return Long.MAX_VALUE
        var next = Long.MAX_VALUE
        fun consider(t: Long) { if (t > now) next = minOf(next, t) }
        BetaStore.config(c).profiles.filter { it.enabled }.forEach { p ->
            val today = Instant.ofEpochMilli(now).atZone(ZoneId.of(p.zone)).toLocalDate()
            consider(today.plusDays(1).atStartOfDay(ZoneId.of(p.zone)).toInstant().toEpochMilli())
            for (offset in -1L..7L) {
                val date = today.plusDays(offset)
                p.windows.filter { it.days and (1 shl (date.dayOfWeek.value - 1)) != 0 }.forEach { w ->
                    val start = date.atStartOfDay().plusMinutes(w.start.toLong()).atZone(ZoneId.of(p.zone)).toInstant().toEpochMilli()
                    val end = date.plusDays(if (w.end < w.start) 1 else 0).atStartOfDay().plusMinutes(w.end.toLong()).atZone(ZoneId.of(p.zone)).toInstant().toEpochMilli()
                    consider(start); consider(end)
                }
            }
            if (p.focusEnd > now) {
                consider(p.focusEnd)
                val cycle = (p.pomodoroMinutes + p.pomodoroBreak) * 60_000L
                val current = p.focusStart + ((now - p.focusStart).coerceAtLeast(0) / cycle) * cycle
                consider(current + p.pomodoroMinutes * 60_000L); consider(current + cycle)
            }
            consider(p.overrideUntil)
            consider(BetaStore.usage(c, p, "*", now).pauseUntil)
        }
        return next
    }
}
