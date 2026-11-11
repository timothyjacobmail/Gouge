package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class BankingListRulesTest {
    private val clock = BankingListClock(1_800_000_000_000, 10_000, 5)

    @Test fun newAdditionWaitsExactlyFifteenMinutes() {
        val entry = BankingListRules.enroll(null, 100, clock)
        assertEquals(900_000L, BankingListRules.remaining(entry, clock))
        assertEquals(1L, BankingListRules.remaining(entry, clock.copy(elapsed = clock.elapsed + 899_999)))
        assertEquals(0L, BankingListRules.remaining(entry, clock.copy(elapsed = clock.elapsed + 900_000)))
    }

    @Test fun separateAdditionsHaveSeparateDeadlines() {
        val first = BankingListRules.enroll(null, 100, clock)
        val later = clock.copy(wall = clock.wall + 60_000, elapsed = clock.elapsed + 60_000)
        val second = BankingListRules.enroll(null, 200, later)
        assertEquals(840_000L, BankingListRules.remaining(first, later))
        assertEquals(900_000L, BankingListRules.remaining(second, later))
    }

    @Test fun refreshOrRemovalAndReadditionCannotSkipOrRestartRunningWait() {
        val entry = BankingListRules.enroll(null, 100, clock)
        val later = clock.copy(elapsed = clock.elapsed + 300_000)
        assertEquals(entry, BankingListRules.enroll(entry, 100, later))
        assertEquals(600_000L, BankingListRules.remaining(BankingListRules.enroll(entry, 100, later), later))
        val expired = BankingListRules.enroll(entry, 100, clock.copy(elapsed = clock.elapsed + 900_000))
        assertEquals(BankingListEntry(100), expired)
    }

    @Test fun rebootCountsOfflineTimeThenUsesMonotonicTimeAgain() {
        val entry = BankingListRules.enroll(null, 100, clock)
        val reboot = BankingListClock(clock.wall + 300_000, 2000, clock.boot + 1)
        val restored = BankingListRules.reconcile(entry, reboot)
        assertEquals(600_000L, BankingListRules.remaining(restored, reboot))
        assertEquals(540_000L, BankingListRules.remaining(restored,
            reboot.copy(wall = clock.wall + 86_400_000, elapsed = reboot.elapsed + 60_000)))
        assertEquals(BankingListEntry(100), BankingListRules.reconcile(entry, reboot.copy(wall = entry.untilWall)))
    }

    @Test fun clockChangesWithinBootCannotEndOrExtendWait() {
        val entry = BankingListRules.enroll(null, 100, clock)
        val later = clock.copy(elapsed = clock.elapsed + 60_000)
        assertEquals(840_000L, BankingListRules.remaining(entry, later.copy(wall = clock.wall + 86_400_000)))
        assertEquals(840_000L, BankingListRules.remaining(entry, later.copy(wall = clock.wall - 86_400_000)))
    }

    @Test fun migrationKeepsExistingEntriesReadyAndReinstallReceivesNewWait() {
        val migrated = BankingListRules.enroll(null, 100, clock, legacy = true)
        assertEquals(0L, BankingListRules.remaining(migrated, clock))
        assertEquals(900_000L, BankingListRules.remaining(BankingListRules.enroll(migrated, 200, clock), clock))
        assertEquals(migrated, BankingListRules.enroll(migrated, 100, clock))
    }

    @Test fun savedWaitRoundTripsAndMalformedRecordsAreRejected() {
        val entry = BankingListRules.enroll(null, 100, clock)
        assertEquals(entry, BankingListEntry.decode(entry.encode()))
        for (raw in listOf(null, "", "100|200|300", "bad|200|300|1", "100|bad|300|1", "100|200|bad|1", "100|200|300|bad"))
            assertNull(BankingListEntry.decode(raw))
    }

    @Test fun accessibilityModeAndTemporaryAccessCannotClearListWait() {
        val reason = AppLockReasons(bankingListUntil = clock.wall + 900_000)
        assertTrue(reason.shouldLock)
        assertTrue(reason.copy(bankingMode = true).shouldLock)
        assertTrue(reason.descriptions().single().contains("15-minute wait"))
        assertTrue(reason.descriptions().single().contains("cannot skip"))
        assertFalse(reason.copy(bankingListUntil = null).shouldLock)
        assertTrue(reason.copy(bankingListUntil = null, appLock = true).shouldLock)
    }
}
