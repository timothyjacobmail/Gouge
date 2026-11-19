package com.example.installlock

/** Metadata belongs to an installed version. Missing listings are not a reason to poll every minute. */
internal object PlayLookupPolicy {
    const val MIN_RETRY_MS = 15 * 60_000L
    private val retryDelays = longArrayOf(MIN_RETRY_MS, 60 * 60_000L, 6 * 60 * 60_000L, 24 * 60 * 60_000L)
    fun verified(record: PlayCategoryRecord) = PlayInstallCategory.entries.any { it.storeId == record.category }
    fun migrate(record: PlayCategoryRecord, now: Long): PlayCategoryRecord = if (!record.legacy) record else
        record.copy(retryAt = if (verified(record)) Long.MAX_VALUE else maxOf(record.retryAt, now + MIN_RETRY_MS),
            failures = if (verified(record)) 0 else 1, legacy = false)
    fun due(record: PlayCategoryRecord?, now: Long) = record == null || !verified(record) && now >= record.retryAt
    fun completed(installedAt: Long, updatedAt: Long, previous: PlayCategoryRecord?, result: String?, now: Long,
                  unavailable: Boolean = false): PlayCategoryRecord {
        val category = result?.takeIf { value -> PlayInstallCategory.entries.any { it.storeId == value } }
        if (category != null) return PlayCategoryRecord(installedAt, updatedAt, category, Long.MAX_VALUE)
        val failures = ((previous?.failures ?: 0) + 1).coerceIn(1,100)
        val delay = if (unavailable) 7 * 24 * 60 * 60_000L else retryDelays[(failures-1).coerceAtMost(retryDelays.lastIndex)]
        return PlayCategoryRecord(installedAt,updatedAt,previous?.category.orEmpty(),now + delay,failures)
    }
}

internal data class PlayLookupAllowance(val window: Long = 0, val requests: Int = 0, val bytes: Long = 0) {
    companion object {
        const val WINDOW_MS = 60 * 60_000L
        const val MAX_BACKGROUND_REQUESTS = 16
        const val MAX_REQUESTS = 24
        const val MAX_BYTES = 2 * 1024 * 1024L
    }
    fun current(now: Long) = if (now < window || now - window >= WINDOW_MS) PlayLookupAllowance(now) else this
    fun permits(now: Long, background: Boolean) = current(now).let { it.requests <
        (if (background) MAX_BACKGROUND_REQUESTS else MAX_REQUESTS) && it.bytes < MAX_BYTES }
}
