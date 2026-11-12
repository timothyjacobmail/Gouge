package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Isolated preferences; never switches VPN, changes app locks or applies device policy. */
class GoogleImagesAccessTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()
    private val prefix = "google_images_test_${System.nanoTime()}_"
    private val c = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(name)
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    private val scope = TaskScope(TaskKind.DNS_UNBLOCK)
    private val password = "ImagesTestPassword123"
    private fun task(minutes: Int = 30) = DelayedTask(scope, "Google Images for $minutes minutes", mapOf("imagesMinutes" to minutes))

    @After fun clean() {
        GoogleImagesAccess.end(c)
        ScheduledTasks.all(c).forEach { ScheduledTasks.cancel(c, it.id) }
        names.forEach { base.deleteSharedPreferences(prefix + it) }
        ScheduledTasks.reschedule(base)
    }

    @Test fun usesDnsCredentialAndTemporaryWindowNeverChangesBlocklistOrVpnReadiness() {
        assertNull(TaskPasswords.vault(c).install(listOf(NewTaskPassword(scope, password)), false))
        val blocked = Store.blockedDomains(c)
        val vpn = DnsFilterService.isReady(base)
        assertFalse(task().execute(c, "WrongPassword1"))
        assertEquals(0L, GoogleImagesAccess.remaining(c))
        assertFalse(task(121).execute(c, password))
        assertTrue(task().execute(c, password))
        assertTrue(GoogleImagesAccess.remaining(c) in 1..1_800_000L)
        assertTrue(GoogleImagesAccess.bypassesImageBlock(c, "encrypted-tbn0.gstatic.com"))
        assertFalse(GoogleImagesAccess.bypassesImageBlock(c, "googlevideo.com"))
        assertFalse(task(120).execute(c, password))
        assertEquals(blocked, Store.blockedDomains(c))
        assertEquals(vpn, DnsFilterService.isReady(base))
        GoogleImagesAccess.end(c)
        assertEquals(0L, GoogleImagesAccess.remaining(c))
        assertFalse(GoogleImagesAccess.bypassesImageBlock(c, "encrypted-tbn0.gstatic.com"))
    }

    @Test fun confirmationUsesSavedDelayAndCannotApplyEarly() {
        assertNull(TaskPasswords.vault(c).install(listOf(NewTaskPassword(scope, password)), false))
        val entry = ScheduledTasks.confirm(c, task(), NativeTaskPasswords.reference(c, scope))
        assertEquals(NativeTaskPasswords.delay(c, scope) * 60_000L, entry.countdown.duration)
        val before = WatchdogClock(0, entry.countdown.dueElapsed - 1, entry.countdown.boot)
        ScheduledTasks.applyDue(c, before) { _, _ -> fail("Image access granted before delay"); false }
        assertEquals(0L, GoogleImagesAccess.remaining(c))
        assertFalse(entry.encode().contains(password))
        ScheduledTasks.cancel(c, entry.id)
        assertTrue(ScheduledTasks.all(c).isEmpty())
    }
}
