package com.example.installlock

/** Edit only our own services; never switch off another app's accessibility service. */
internal object BankingAccessibilityRules {
    fun entries(value: String): List<String> = value.split(':').filter { it.isNotBlank() }
    fun owned(entry: String, packageName: String): Boolean =
        entry.substringBefore('/', "") == packageName && entry.substringAfter('/', "").isNotBlank()

    fun pause(current: String, packageName: String): String =
        entries(current).filterNot { owned(it, packageName) }.joinToString(":")

    fun restore(current: String, saved: Set<String>, packageName: String): String =
        (entries(current) + saved.filter { owned(it, packageName) }).distinct().joinToString(":")

    fun monitoringReady(banking: Boolean, connected: Boolean, enabled: Boolean): Boolean =
        !banking && connected && enabled
}
