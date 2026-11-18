package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class JudgePracticalContentTest {
    private val html = """<html><title>Forgekit technical documentation</title><body><main>
        <h1>Forgekit software reference and installation</h1>
        Forgekit provides software documentation and API reference for engineers. Follow the installation
        instructions to configure your environment, examine the API parameters and troubleshoot configuration
        problems. The reference explains technical specifications and includes detailed configuration examples.
        </main></body></html>"""

    @Test fun genuinelyNewPracticalHostCanPassWithoutAnExistingWhitelistEntry() {
        val policy = JudgePreferences()
        val result = WebsiteJudge({ WebsiteJudge.Page(200, html) }, preferences = policy).judge("https://forgekit.example/docs")
        assertEquals(JudgeDecision.IMMEDIATE, result.decision)
        assertEquals(setOf("forgekit.example"), result.hosts)
        assertTrue(result.contentReviewed)
        assertTrue(policy.accepts(result))
        val filter = ChromeNavigationFilter(ChromeNavigationConfig(true, emptyList(), emptySet(),
            judgeHosts = result.hosts, judgeEligibleHosts = policy.eligibleHosts + result.hosts))
        assertNull(filter.block(result.address))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter.block("https://sub.forgekit.example/docs"))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter.block("http://forgekit.example/docs"))
    }

    @Test fun borrowedIdentityThinContentHiddenClaimsAndLeisureRemainRejected() {
        for ((host, page) in listOf(
            "unfamiliar.example" to html,
            "forgekit.example.attacker.example" to html,
            "forgekit.example" to "<title>Forgekit technical documentation</title><main>Software API installation.</main>",
            "forgekit.example" to "<title>Welcome</title><script>$html</script><body>Ordinary content. ${"More words. ".repeat(30)}</body>",
            "forgekit.example" to html.replace("</main>", " Watch movies and anime online.</main>"),
            "youtube.com" to html,
            "chess.com" to html)) {
            assertNotEquals(host, JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://$host/", page).decision)
        }
    }

    @Test fun everyPersonalVetoStillAppliesToNewContentBasedApprovals() {
        for (policy in listOf(JudgePreferences(blockedWords = setOf("configuration")),
            JudgePreferences(extraLocks = setOf("forgekit.example")), JudgePreferences(dnsBlocks = setOf("forgekit.example")),
            JudgePreferences(leisureCatalog = setOf("forgekit.example")), JudgePreferences(domainRules = listOf(
                DomainRule("block", DomainAction.BLOCK, DomainMatchType.EXACT, "forgekit.example"))))) {
            assertEquals(JudgeDecision.DELAY, WebsiteJudge({ WebsiteJudge.Page(200, html) }, preferences = policy)
                .judge("https://forgekit.example/").decision)
        }
    }

    @Test fun staleOrMismatchedContentDecisionsCannotBecomePersistedGrants() {
        val policy = JudgePreferences()
        val result = JudgeRules.inspect("https://forgekit.example/", html, policy)
        assertTrue(policy.accepts(result))
        assertFalse(policy.accepts(result.copy(contentReviewed = false)))
        assertFalse(policy.accepts(result.copy(hosts = setOf("other.example"))))
        assertFalse(policy.accepts(result.copy(address = "https://youtube.com/", hosts = setOf("youtube.com"))))
        assertFalse(policy.copy(blockedWords = setOf("new-word")).accepts(result))
        assertEquals(setOf("forgekit.example"), policy.refreshCandidates(result.hosts, result.hosts))
        assertTrue(policy.copy(extraLocks = result.hosts).refreshCandidates(result.hosts, result.hosts).isEmpty())
    }

    @Test fun ordinaryTechnicalPhrasesAndLongJavascriptHelpDoNotLookLikeEntertainmentOrABotChallenge() {
        val technical = html.replace("</main>", " Enable JavaScript to use the interactive API explorer. Our data streaming API supports event streaming.</main>")
        assertEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://forgekit.example/", technical).decision)
        assertEquals(JudgeDecision.UNVERIFIED, JudgeRules.inspect("https://forgekit.example/",
            "<title>Verify you are human</title>$technical").decision)
    }

    @Test fun bodyMustHaveMultipleSignalsFromOnePracticalPurpose() {
        val vague = "<title>Forgekit software</title><body><main>Forgekit is safe, productive and educational. " +
            "We offer account services and helpful information. ".repeat(8) + "</main></body>"
        assertEquals(JudgeDecision.UNVERIFIED, JudgeRules.inspect("https://forgekit.example/", vague).decision)
    }
}
