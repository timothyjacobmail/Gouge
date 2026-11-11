package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlayStoreDelayStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("play_delay_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val c = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    private val now = WatchdogClock(100_000, 20_000, 1)
    @After fun clean() { PlayStoreDelayStore.endSession(); prefs.edit().clear().commit() }

    @Test fun changingOrDisablingOpeningDelayRequiresItsOwnPasswordAndCancelKeepsTheSetting() {
        val scope = TaskScope(TaskKind.PLAY_STORE_DELAY)
        assertNull(TaskPasswords.vault(c).install(listOf(NewTaskPassword(scope, "PlayStoreDelay123")), false))
        assertFalse(PlayStoreDelayStore.save(c, 0, "WrongPassword1"))
        assertEquals(2, PlayStoreDelayStore.minutes(c))
        assertTrue(PlayStoreDelayStore.save(c, 0, "PlayStoreDelay123"))
        assertFalse(PlayStoreDelayStore.enabled(c))
        assertFalse(PlayStoreDelayStore.save(c, -1, "PlayStoreDelay123"))
        assertTrue(PlayStoreDelayStore.save(c, 5, "PlayStoreDelay123"))
        assertEquals(300_000L, PlayStoreDelayStore.start(c, now).remaining(now))
        PlayStoreDelayStore.cancel(c)
        assertEquals(5, PlayStoreDelayStore.minutes(c))
    }

    @Test fun repeatedOpenAndProcessReloadKeepTheOriginalTwoMinuteDeadline() {
        val start = PlayStoreDelayStore.start(c, now)
        assertEquals(120_000L, start.remaining(now))
        assertEquals(start, PlayStoreDelayStore.start(c, now.copy(elapsed = 80_000)))
        assertEquals(1L, PlayStoreDelayStore.start(c, now.copy(elapsed = 139_999)).remaining(now.copy(elapsed = 139_999)))
        assertEquals(0L, PlayStoreDelayStore.start(c, now.copy(elapsed = 140_000)).remaining(now.copy(elapsed = 140_000)))
    }

    @Test fun cancelAndRebootRequireAFreshFullWait() {
        PlayStoreDelayStore.start(c, now)
        val reboot = now.copy(elapsed = 500, boot = 2)
        assertEquals(120_000L, PlayStoreDelayStore.start(c, reboot).remaining(reboot))
        PlayStoreDelayStore.cancel(c)
        val later = reboot.copy(elapsed = 5000)
        assertEquals(120_000L, PlayStoreDelayStore.start(c, later).remaining(later))
    }

    @Test fun openingASessionConsumesTheWaitAndLeavingRequiresANewOne() {
        PlayStoreDelayStore.start(c, now)
        PlayStoreDelayStore.beginSession(c)
        assertTrue(PlayStoreDelayStore.sessionOpen)
        PlayStoreDelayStore.endSession()
        assertFalse(PlayStoreDelayStore.sessionOpen)
        val next = now.copy(elapsed = 200_000)
        assertEquals(120_000L, PlayStoreDelayStore.start(c, next).remaining(next))
    }
}
