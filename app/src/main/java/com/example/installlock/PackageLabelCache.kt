package com.example.installlock

/** Labels are metadata, not authorization. Keep one current label per installed package. */
internal data class PackageLabelKey(
    val packageName: String, val installedAt: Long, val updatedAt: Long,
    val labelResource: Int, val literalLabel: String?, val locales: String
)

internal class PackageLabelCache {
    private val entries = mutableMapOf<String, Pair<PackageLabelKey, String>>()
    @Synchronized fun get(key: PackageLabelKey, load: () -> String): String {
        entries[key.packageName]?.let { if (it.first == key) return it.second }
        return load().also { entries[key.packageName] = key to it }
    }
    @Synchronized fun retain(installed: Set<String>) { entries.keys.retainAll(installed) }
    @Synchronized fun clear() { entries.clear() }
}
