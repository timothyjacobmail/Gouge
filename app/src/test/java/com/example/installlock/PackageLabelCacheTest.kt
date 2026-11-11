package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class PackageLabelCacheTest {
    private val key = PackageLabelKey("example.reader", 1, 2, 3, null, "en-US")
    @Test fun unchangedMetadataLoadsResourcesOnce() {
        val cache = PackageLabelCache()
        var calls = 0
        repeat(1000) { assertEquals("Reader", cache.get(key.copy()) { calls++; "Reader" }) }
        assertEquals(1, calls)
    }
    @Test fun updateReinstallLocaleAndLabelChangesReloadImmediately() {
        val cache = PackageLabelCache()
        val changed = listOf(key.copy(installedAt = 10), key.copy(updatedAt = 20),
            key.copy(labelResource = 30), key.copy(literalLabel = "Reddit"), key.copy(locales = "ta-IN"))
        for (next in changed) {
            assertEquals("Old", cache.get(key) { "Old" })
            assertEquals("New", cache.get(next) { "New" })
        }
    }
    @Test fun removedPackagesAndInvalidationDoNotRetainStaleLabels() {
        val cache = PackageLabelCache()
        assertEquals("Old", cache.get(key) { "Old" })
        cache.retain(emptySet())
        assertEquals("Reinstalled", cache.get(key) { "Reinstalled" })
        cache.clear()
        assertEquals("Reloaded", cache.get(key) { "Reloaded" })
    }
    @Test fun anotherPackageOrFailedResourceLoadCannotReuseALabel() {
        val cache = PackageLabelCache()
        cache.get(key) { "Reader" }
        val other = key.copy(packageName = "example.other")
        try { cache.get(other) { error("resource unavailable") }; fail() } catch (_: IllegalStateException) { }
        assertEquals("Other", cache.get(other) { "Other" })
        assertEquals("Reader", cache.get(key) { error("cached value lost") })
    }
}
