package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class GoogleImagesWindowTest {
    private val now = WatchdogClock(1_800_000_000_000, 50_000, 7)

    @Test fun durationCapExpiryAndRunningSessionCannotBeExtended() {
        for (minutes in listOf(-1, 0, 121, Int.MAX_VALUE))
            assertNull(GoogleImagesWindow().start(minutes, 120, now))
        val active = GoogleImagesWindow().start(120, 120, now)!!
        assertEquals(7_200_000L, active.remaining(now))
        assertNull(active.start(30, 120, now))
        assertEquals(1L, active.remaining(now.copy(elapsed = active.until - 1)))
        assertEquals(0L, active.remaining(now.copy(elapsed = active.until)))
        assertNotNull(active.start(30, 120, now.copy(elapsed = active.until)))
    }

    @Test fun wallClockChangesDoNotExtendAccessAndRebootEndsIt() {
        val active = GoogleImagesWindow().start(30, 120, now)!!
        assertEquals(1_800_000L, active.remaining(now.copy(wall = 0)))
        assertEquals(1_800_000L, active.remaining(now.copy(wall = Long.MAX_VALUE)))
        assertEquals(0L, active.remaining(now.copy(boot = 8)))
    }

    @Test fun imageExceptionLeavesVideosCustomDnsAndOrderedBlocksIntact() {
        val blocked = setOf("encrypted-tbn0.gstatic.com", "googleusercontent.com", "googlevideo.com", "example.com")
        val rules = DomainRules(emptyList())
        assertTrue(GoogleImagesWindow.isBlocked("encrypted-tbn0.gstatic.com", blocked, false, rules))
        assertFalse(GoogleImagesWindow.isBlocked("encrypted-tbn0.gstatic.com", blocked, true, rules))
        assertFalse(GoogleImagesWindow.isBlocked("lh3.googleusercontent.com", blocked, true, rules))
        assertTrue(GoogleImagesWindow.isBlocked("r1.googlevideo.com", blocked, true, rules))
        assertTrue(GoogleImagesWindow.isBlocked("images.example.com", blocked, true, rules))
        assertTrue(GoogleImagesWindow.isBlocked("encrypted-tbn0.gstatic.com", blocked + "gstatic.com", true, rules))
        assertTrue(GoogleImagesWindow.isBlocked("lh3.googleusercontent.com", blocked + "lh3.googleusercontent.com", true, rules))
        val explicit = DomainRules(listOf(DomainRule("block", DomainAction.BLOCK, DomainMatchType.SUFFIX, "googleusercontent.com")))
        assertTrue(GoogleImagesWindow.isBlocked("lh3.googleusercontent.com", blocked, true, explicit))
        assertFalse(GoogleImagesWindow.isImageHost("googleusercontent.com.example.org"))
    }
}
