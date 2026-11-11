package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RedditStoreTest {
    @Test fun approvedSubredditsAreLinkedToChromeWithoutASecondWaitAndRemovalRevokesTheLink() {
        installTaskTestCredentials(context, "RedditRules1", TaskKind.REDDIT_ALLOW, TaskKind.REDDIT_OFF)
        Store.saveTimingConfig(context, TimingConfig(mapOf(TimingOption.SITE_WAIT to 60)))
        assertFalse(Store.activePatterns(context).contains("reddit.com/r/android/"))
        assertTrue(RedditStore.addAllowed(context, "Android", "RedditRules1"))
        assertTrue(Store.activePatterns(context).contains("reddit.com/r/android/"))
        // Linking applies even with the native-app feature off and does not create a manual website entry.
        assertFalse(RedditStore.enabled(context))
        assertTrue(Store.sites(context).isEmpty())
        val config = ChromeNavigationConfig(true, Store.activePatterns(context), emptySet())
        assertNull(ChromeNavigationFilter(config).block("https://www.reddit.com/r/android/comments/abc/post/"))
        assertNull(ChromeNavigationFilter(config).block("https://old.reddit.com/r/android/"))
        assertEquals(ChromeNavigationBlock.WHITELIST, ChromeNavigationFilter(config).block("https://reddit.com/r/androidapps/"))
        assertEquals(ChromeNavigationBlock.WHITELIST, ChromeNavigationFilter(config).block("https://reddit.com/r/kotlin/"))
        assertEquals(ChromeNavigationBlock.WHITELIST, ChromeNavigationFilter(config).block("https://reddit.com/"))
        assertNotNull(ChromeRestrictions.update(android.os.Bundle(), true, Store.activePatterns(context)))
        RedditStore.removeAllowed(context, "android")
        assertFalse(Store.activePatterns(context).contains("reddit.com/r/android/"))
    }
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("reddit_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private fun context() = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    private val context = context()
    @After fun cleanUp() { prefs.edit().clear().commit(); RedditStore.invalidate() }

    @Test fun accessAndDisablingHaveSeparateCredentialsAndRemovingAccessNeedsNoPassword() {
        assertFalse(RedditStore.enable(context))
        assertNull(TaskPasswords.vault(context).install(listOf(
            NewTaskPassword(TaskScope(TaskKind.REDDIT_ALLOW), "AllowReddit1"),
            NewTaskPassword(TaskScope(TaskKind.REDDIT_OFF), "DisableReddit2")), false))
        assertTrue(RedditStore.enable(context))
        assertFalse(RedditStore.addAllowed(context, "android", "DisableReddit2"))
        assertTrue(RedditStore.addAllowed(context, "r/Android", "AllowReddit1"))
        assertFalse(RedditStore.disable(context, "AllowReddit1"))
        assertTrue(RedditStore.enabled(context))
        assertTrue(RedditStore.removeAllowed(context, "android"))
        assertTrue(RedditStore.allowed(context).isEmpty())
        assertTrue(RedditStore.enabled(context))
        assertTrue(RedditStore.disable(context, "DisableReddit2"))
    }

    @Test fun emptyInstallationSnapshotAndFreshContextPreserveRulesAndRenamedClients() {
        installTaskTestCredentials(context, "RedditRules1", TaskKind.REDDIT_ALLOW, TaskKind.REDDIT_OFF)
        assertTrue(RedditStore.addAllowed(context, "kotlin", "RedditRules1"))
        prefs.edit().putStringSet(RedditStore.CLIENTS, setOf("custom.renamed.reader"))
            .putStringSet(RedditStore.DETECTED, setOf("com.reddit.frontpage.revanced")).commit()
        assertTrue(RedditStore.enable(context))
        val before = RedditStore.targets(context)
        RedditStore.invalidate()
        RedditStore.discover(context, emptyList()) // All Reddit clients have been uninstalled.
        val reopened = context()
        assertEquals(before, RedditStore.targets(reopened))
        assertEquals(setOf("kotlin"), RedditStore.allowed(reopened))
        assertTrue(RedditStore.enabled(reopened))
        assertTrue("custom.renamed.reader" in RedditStore.targets(reopened))
    }

    @Test fun scopedDelayedTasksCannotDisableProtectionWithAnAddCommunityPassword() {
        installTaskTestCredentials(context, "RedditRules1", TaskKind.REDDIT_ALLOW, TaskKind.REDDIT_OFF)
        assertTrue(RedditStore.enable(context))
        val add = DelayedTask(TaskScope(TaskKind.REDDIT_ALLOW), "Allow kotlin", mapOf("subreddit" to "kotlin"))
        assertTrue(add.execute(context, "RedditRules1"))
        assertFalse(DelayedTask(TaskScope(TaskKind.REDDIT_OFF), "Disable Reddit").execute(context, "WrongPass1"))
        assertTrue(RedditStore.enabled(context))
        assertEquals(setOf("kotlin"), RedditStore.allowed(context))
    }
}
