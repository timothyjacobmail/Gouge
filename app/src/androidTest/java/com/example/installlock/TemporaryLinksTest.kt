package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TemporaryLinksTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()
    private val prefix = "temporary_link_test_${System.nanoTime()}_"
    private val c = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(name)
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    private val scope = TaskScope(TaskKind.WHITELIST_ADD)
    private val password = "TemporaryLink123"
    private val url = "https://instagram.com/reel/Example/"
    private fun task() = DelayedTask(scope, "Temporary reel", mapOf("temporary" to true, "url" to url))

    @After fun clean() {
        TemporaryLinks.all(c).forEach { TemporaryLinks.remove(c, it.id) }
        ScheduledTasks.all(c).forEach { ScheduledTasks.cancel(c, it.id) }
        names.forEach { base.getSharedPreferences(prefix + it, 0).edit().clear().commit() }
    }

    @Test fun approvalUsesWhitelistCredentialAndNeverAddsAPermanentEntry() {
        assertNull(TaskPasswords.vault(c).install(listOf(NewTaskPassword(scope, password)), false))
        assertFalse(task().execute(c, "IncorrectPassword1"))
        assertTrue(TemporaryLinks.all(c).isEmpty())
        assertTrue(task().execute(c, password))
        assertTrue(Store.sites(c).isEmpty())
        val ready = TemporaryLinks.all(c).single()
        assertTrue(ready.ready)
        var notifications = 0
        TemporaryLinks.reconcile(c) { notifications++; true }
        TemporaryLinks.reconcile(c) { notifications++; true }
        assertEquals(1, notifications)
        val now = WatchdogStore.clock(c)
        val opened = TemporaryLinks.open(c, ready.id, now)!!
        assertEquals(TemporaryLinkRules.ACCESS_MS, opened.remaining(now))
        assertEquals(opened, TemporaryLinks.open(c, ready.id, now.copy(elapsed = now.elapsed + 1000, wall = now.wall + 1000)))
        assertTrue(task().execute(c, password))
        assertEquals(opened, TemporaryLinks.all(c).single())
        val expiry = now.copy(wall = now.wall + TemporaryLinkRules.ACCESS_MS,
            elapsed = now.elapsed + TemporaryLinkRules.ACCESS_MS)
        TemporaryLinks.reconcile(c, expiry) { fail("An active or expired link must not send a ready notification"); false }
        assertTrue(TemporaryLinks.all(c).isEmpty())
        assertNull(TemporaryLinks.open(c, opened.id, expiry))
        assertTrue(Store.sites(c).isEmpty())
    }

    @Test fun taskCannotGrantAccessBeforeItsNormalDelayAndCancellationStopsIt() {
        assertNull(TaskPasswords.vault(c).install(listOf(NewTaskPassword(scope, password)), false))
        val entry = ScheduledTasks.confirm(c, task(), NativeTaskPasswords.reference(c, scope))
        val due = WatchdogClock(0, entry.countdown.dueElapsed, entry.countdown.boot)
        ScheduledTasks.applyDue(c, due.copy(elapsed = due.elapsed - 1)) { _, _ -> fail("Applied early"); false }
        assertTrue(TemporaryLinks.all(c).isEmpty())
        ScheduledTasks.cancel(c, entry.id)
        ScheduledTasks.applyDue(c, due) { _, _ -> fail("Applied cancelled link"); false }
        assertTrue(TemporaryLinks.all(c).isEmpty())
    }

    @Test fun blockedNotificationsAreRetriedAndPendingLinksDoNotGrantPolicyAccess() {
        assertTrue(TemporaryLinks.approve(c, url))
        var attempts = 0
        TemporaryLinks.reconcile(c) { attempts++; false }
        TemporaryLinks.reconcile(c) { attempts++; true }
        TemporaryLinks.reconcile(c) { attempts++; true }
        assertEquals(2, attempts)
        assertTrue(TemporaryLinks.activeUrls(c).isEmpty())
        assertNull(TemporaryLinks.open(c, "unapproved-id"))
    }

    @Test fun temporaryChromePolicyIsNarrowAndExtraLocksOverrideIt() {
        val policy = ChromeRestrictions.update(Bundle(), true, emptyList(), temporaryUrls = listOf(url))!!
        val patterns = JSONArray(policy.getString("URLAllowlist"))
        val values = (0 until patterns.length()).map { patterns.getString(it) }
        assertTrue(values.contains("https://.instagram.com:443/reel/Example"))
        assertFalse(values.contains("instagram.com"))
        val revoked = ChromeRestrictions.update(policy, true, emptyList())!!
        assertEquals("[]", revoked.getString("URLAllowlist"))
        val blocked = ChromeRestrictions.update(Bundle(), true, emptyList(), listOf("instagram.com"), temporaryUrls = listOf(url))!!
        assertEquals("[]", blocked.getString("URLAllowlist"))
        val blockWww = ChromeRestrictions.update(Bundle(), true, emptyList(), listOf("www.instagram.com"), temporaryUrls = listOf(url))!!
        assertFalse(blockWww.getString("URLAllowlist")!!.contains(".www.instagram.com"))
    }

    @Test fun existingInstagramApprovalLosesShareTokenWithoutChangingExpiryOrStartingAnotherWait() {
        val now = WatchdogStore.clock(c)
        val original = TemporaryLink("existing-id", "https://instagram.com/p/AbC123?xtok=old-share-token",
            now.wall + 60_000, now.elapsed + 60_000, now.boot, true)
        c.getSharedPreferences("temporary_links", 0).edit().putString(original.id,
            JSONObject().put("id", original.id).put("url", original.url).put("wall", original.expiresWall)
                .put("elapsed", original.expiresElapsed).put("boot", original.boot).put("notified", true).toString()).commit()
        val restored = TemporaryLinks.all(c, now).single()
        assertEquals(original.copy(url = "https://instagram.com/p/AbC123"), restored)
        assertTrue(TemporaryLinks.approve(c, "https://www.instagram.com/reel/AbC123/?xtok=new-token"))
        assertEquals(restored, TemporaryLinks.all(c, now).single())
        val policy = ChromeRestrictions.update(Bundle(), true, emptyList(), temporaryUrls = listOf(restored.url))!!
        val values = JSONArray(policy.getString("URLAllowlist")).let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue(values.contains("https://.www.instagram.com:443/p/AbC123"))
        assertTrue(values.contains("https://.www.instagram.com:443/reel/AbC123"))
        assertTrue(values.none { "xtok" in it })
    }
}
