package com.example.installlock

internal data class BankingListClock(val wall: Long, val elapsed: Long, val boot: Int)
internal data class BankingListEntry(
    val installedAt: Long, val untilWall: Long = 0, val untilElapsed: Long = 0, val boot: Int = -1
) {
    fun encode() = "$installedAt|$untilWall|$untilElapsed|$boot"
    companion object {
        fun decode(raw: String?): BankingListEntry? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 4) return null
            return BankingListEntry(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: return null,
                parts[2].toLongOrNull() ?: return null, parts[3].toIntOrNull() ?: return null)
        }
    }
}

internal object BankingListRules {
    const val WAIT_MS = 15 * 60_000L
    fun remaining(entry: BankingListEntry, now: BankingListClock): Long =
        (if (entry.boot == now.boot) entry.untilElapsed - now.elapsed else entry.untilWall - now.wall)
            .coerceIn(0, WAIT_MS)

    fun reconcile(entry: BankingListEntry, now: BankingListClock): BankingListEntry {
        val left = remaining(entry, now)
        return when {
            left == 0L -> BankingListEntry(entry.installedAt)
            entry.boot == now.boot -> entry
            else -> entry.copy(untilWall = now.wall + left, untilElapsed = now.elapsed + left, boot = now.boot)
        }
    }

    fun enroll(old: BankingListEntry?, installedAt: Long, now: BankingListClock,
               legacy: Boolean = false): BankingListEntry = when {
        old != null && old.installedAt == installedAt -> reconcile(old, now)
        legacy -> BankingListEntry(installedAt)
        else -> BankingListEntry(installedAt, now.wall + WAIT_MS, now.elapsed + WAIT_MS, now.boot)
    }
}
