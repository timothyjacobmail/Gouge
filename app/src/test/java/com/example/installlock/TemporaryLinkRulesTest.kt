package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class TemporaryLinkRulesTest {
    private val url = "https://www.instagram.com/reel/AbC123/"
    private val now = WatchdogClock(1_000_000, 10_000, 4)

    @Test fun readyApprovalWaitsForOpeningThenLastsOneDayWithoutExtension() {
        val ready = TemporaryLink("id", url)
        assertTrue(ready.ready)
        assertFalse(ready.active(now))
        val later = now.copy(wall = now.wall + 10 * TemporaryLinkRules.ACCESS_MS)
        val opened = ready.open(later)!!
        assertEquals(86_400_000L, opened.remaining(later))
        assertEquals(opened, opened.open(later.copy(elapsed = later.elapsed + 5000, wall = later.wall + 5000)))
        val expiry = later.copy(elapsed = later.elapsed + TemporaryLinkRules.ACCESS_MS,
            wall = later.wall + TemporaryLinkRules.ACCESS_MS)
        assertFalse(opened.active(expiry))
        assertNull(opened.open(expiry))
    }

    @Test fun clockChangesAndRebootsCannotExtendActiveAccess() {
        val opened = TemporaryLink("id", url).open(now)!!
        assertEquals(0L, opened.remaining(now.copy(wall = 0, elapsed = now.elapsed + 1000)))
        assertFalse(opened.active(now.copy(wall = now.wall + TemporaryLinkRules.ACCESS_MS)))
        assertEquals(TemporaryLinkRules.ACCESS_MS - 1000,
            opened.remaining(now.copy(boot = now.boot + 1, elapsed = 0, wall = now.wall + 1000)))
        assertTrue(TemporaryLink("id", url).ready)
    }

    @Test fun reelApprovalIsCaseSensitiveAndCannotApproveAdjacentPagesOrSubdomains() {
        assertTrue(TemporaryLinkRules.matches(url, "https://instagram.com/reel/AbC123?"))
        assertTrue(TemporaryLinkRules.matches(url, "$url#comments"))
        assertTrue(TemporaryLinkRules.matches(url, "$url?igsh=tracking&utm_source=share"))
        for (address in listOf("https://instagram.com/", "https://instagram.com/reel/Other/",
            "https://instagram.com/reel/abc123/", "https://instagram.com/reel/AbC123evil/",
            "https://instagram.com/reel/AbC123/comments", "https://evil.instagram.com/reel/AbC123/",
            "http://instagram.com/reel/AbC123/", "https://instagram.com:8443/reel/AbC123/",
            "https://instagram.com/reel/AbC123/../Other/")) assertFalse(address, TemporaryLinkRules.matches(url, address))
    }

    @Test fun queriesIdentifyTheContentAndChromeReceivesNarrowPatterns() {
        val video = "https://example.com/watch?v=one&list=two"
        assertTrue(TemporaryLinkRules.matches(video, "https://www.example.com/watch?list=two&v=one"))
        assertFalse(TemporaryLinkRules.matches(video, "https://example.com/watch?v=other&list=two"))
        assertFalse(TemporaryLinkRules.matches(video, "https://example.com/watch?v=one&v=other&list=two"))
        assertEquals(listOf("https://.example.com:443/watch@list=two&v=one",
            "https://.www.example.com:443/watch@list=two&v=one"), TemporaryLinkRules.policyPatterns(video))
        assertTrue(TemporaryLinkRules.policyPatterns("https://example.com/path*").all { '*' !in it })
    }

    @Test fun unsafeLinksAndLocalCallbacksAreRejected() {
        for (input in listOf("javascript:alert(1)", "https://user:secret@example.com/page", "https://localhost/callback?code=secret",
            "https://co.in/", "https://example.com:99999/", "https://example.com/%2e%2e/page", "https://example.com/a\\b"))
            assertNull(input, TemporaryLinkRules.normalize(input))
        assertEquals("https://example.com/Page?token=1", SharedSite.temporaryLink("A page\nhttps://www.example.com/Page?token=1#part"))
    }

    @Test fun nativeNavigationEnforcesExactLinkAndExistingBlockRules() {
        val config = ChromeNavigationConfig(true, emptyList(), emptySet(), temporaryUrls = listOf(url))
        assertNull(ChromeNavigationFilter(config).block(url))
        assertEquals(ChromeNavigationBlock.WHITELIST, ChromeNavigationFilter(config).block("https://instagram.com/reel/Other/"))
        assertEquals(ChromeNavigationBlock.EXTRA_SITE,
            ChromeNavigationFilter(config.copy(extraLockedDomains = setOf("instagram.com"))).block(url))
        val rule = DomainRule("block", DomainAction.BLOCK, DomainMatchType.SUFFIX, "instagram.com")
        assertEquals(ChromeNavigationBlock.DOMAIN_RULE, ChromeNavigationFilter(config.copy(domainRules = listOf(rule))).block(url))
        assertEquals(ChromeNavigationBlock.WHITELIST, ChromeNavigationFilter(config.copy(temporaryUrls = emptyList())).block(url))
    }
}
