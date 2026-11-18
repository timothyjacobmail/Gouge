package com.example.installlock

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class JudgeLinkAndDeadlineTest {
    @Test fun googleWrappedClaudeMagicLinkIsDecodedWithoutFetchingOrConsumingIt() {
        val link = "https://www.google.com/url?q=https://claude.ai/magic-link%23synthetic-token%3D&source=gmail"
        var fetched = false
        val result = WebsiteJudge({ fetched = true; error("Sign-in link must not be fetched") }).judge(link)
        assertEquals(JudgeDecision.IMMEDIATE, result.decision)
        assertEquals(setOf("claude.ai"), result.hosts)
        assertEquals("https://claude.ai/magic-link#synthetic-token=", result.address)
        assertFalse(fetched)
        assertFalse(result.reason.contains("synthetic-token"))
        assertEquals(ChromeNavigationBlock.WHITELIST, ChromeNavigationFilter(ChromeNavigationConfig(true,
            emptyList(), emptySet(), judgeHosts = result.hosts)).block("https://www.google.com/url?q=https://claude.ai"))
    }

    @Test fun signInRecognitionNeverGrantsLookalikesOrOverridesOtherRules() {
        for (host in listOf("claude.ai.attacker.example", "other.claude.ai", "claude-ai.example")) {
            assertNull(JudgeRules.knownSignIn("https://$host/magic-link#synthetic", JudgePreferences()))
        }
        for (policy in listOf(JudgePreferences(extraLocks = setOf("claude.ai")), JudgePreferences(blockedWords = setOf("claude")),
            JudgePreferences(leisureCatalog = setOf("claude.ai")))) {
            assertEquals(JudgeDecision.DELAY, WebsiteJudge({ error("No fetch") }, preferences = policy)
                .judge("https://claude.ai/magic-link#synthetic").decision)
        }
        assertNull(JudgeRules.knownSignIn("https://claude.ai/magic-link?next=https://youtube.com", JudgePreferences()))
        assertEquals(JudgeDecision.DELAY, WebsiteJudge({ error("No fetch") }, leisureCatalog = setOf("claude.ai"))
            .judge("https://claude.ai/magic-link#synthetic").decision)
    }

    @Test fun otherTokenBearingLinksCheckOnlyThePublicRootAndNeverTheToken() {
        val requested = mutableListOf<String>()
        val result = WebsiteJudge({ requested.add(it.toString()); WebsiteJudge.Page(403) })
            .judge("https://other.example/verify?code=synthetic-secret#private")
        assertEquals(listOf("https://other.example/"), requested)
        assertEquals(JudgeDecision.UNVERIFIED, result.decision)
        assertFalse(result.reason.contains("synthetic-secret"))
        assertTrue(result.address.isEmpty())
    }

    @Test fun nestedGoogleTargetsAndEncodedPathsKeepTheirOriginalOctets() {
        assertEquals("https://example.com/A%2Fb?q=a%26b", JudgeRules.webUri("https://example.com/A%2Fb?q=a%26b")?.toASCIIString())
        val inner = "https://www.google.com/url?q=https%3A%2F%2Fclaude.ai%2Fmagic-link%23synthetic"
        val outer = "https://www.google.com/url?url=" + java.net.URLEncoder.encode(inner, "UTF-8")
        assertEquals("https://claude.ai/magic-link#synthetic", GoogleShareResolver.resolveLocally(outer))
        assertNull(GoogleShareResolver.resolveLocally("https://google.com.attacker.example/url?q=https://claude.ai")?.takeIf { it == "https://claude.ai/" })
        assertNull(GoogleShareResolver.resolveLocally("https://google.com/url?q=https://claude.ai&url=https://youtube.com"))
    }

    @Test fun wallClockDeadlineCancelsABlockedFetchRatherThanWaitingForSocketTimeouts() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val started = System.nanoTime()
        val result = WebsiteJudge({
            entered.countDown()
            try { CountDownLatch(1).await(30, TimeUnit.SECONDS) } finally { interrupted.countDown() }
            WebsiteJudge.Page(200)
        }, timeoutMillis = 250).judge("https://edcad.ae/")
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        assertEquals(JudgeDecision.UNVERIFIED, result.decision)
        assertTrue(result.hosts.isEmpty())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
    }

    @Test fun opaqueGoogleWrapperAlsoHasOneTotalBudgetAndDoesNotHoldTheNextEmbeddedLink() {
        val interrupted = CountDownLatch(1)
        val resolver = GoogleShareResolver(timeoutMillis = 250) {
            try { CountDownLatch(1).await(30, TimeUnit.SECONDS) } finally { interrupted.countDown() }
            GoogleShareResolver.Response(200)
        }
        assertNull(resolver.resolve("https://share.google/synthetic"))
        assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        assertEquals("https://claude.ai/magic-link#synthetic", resolver.resolve(
            "https://google.com/url?q=https://claude.ai/magic-link%23synthetic"))
    }
}
