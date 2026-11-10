package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class JudgePreferencesTest {
    private val page = "<title>Workshop engineering documentation</title><main>Workshop provides technical documentation, " +
        "software installation and engineering support. Read reference material and troubleshoot your configuration.</main>"

    @Test fun completedWholeSiteApprovalsTeachOnlyExactHosts() {
        val policy = JudgePreferences(approvedPatterns = listOf("workshop.example", "reddit.com/r/engineering/", "gov.in", "localhost/callback"))
        assertEquals(setOf("workshop.example", "www.workshop.example"), policy.learnedHosts)
        assertEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://www.workshop.example/docs", page, policy).decision)
        assertEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://workshop.example/docs", page, policy).decision)
        assertNotEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://sub.workshop.example/docs", page, policy).decision)
        assertNotEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://workshop.example.attacker.example", page, policy).decision)
    }
    @Test fun allowSuffixesNeverBecomeLearnedTrustAndLeisureApprovalsStayIneligible() {
        val policy = JudgePreferences(approvedPatterns = listOf("youtube.com", "reddit.com", "chess.com", "linkedin.com"),
            domainRules = listOf(DomainRule("allow", DomainAction.ALLOW, DomainMatchType.SUFFIX, "org")))
        assertTrue(policy.learnedHosts.isEmpty())
        assertNotEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://unknown.org", page, policy).decision)
        assertEquals(JudgeDecision.DELAY, JudgeRules.inspect("https://youtube.com", page, policy).decision)
    }
    @Test fun changingBlockedWordsChangesJudgementWithoutChangingCode() {
        val original = JudgePreferences(approvedPatterns = listOf("workshop.example"))
        val changed = original.copy(blockedWords = setOf("graphic novel"))
        assertEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://workshop.example", page, original).decision)
        assertEquals(JudgeDecision.DELAY, JudgeRules.inspect("https://workshop.example", page + " Graphic novel", changed).decision)
        assertNotEquals(original.revision, changed.revision)
    }
    @Test fun exceptionsApplyToUserWordsWithoutOverridingLeisureDenials() {
        val p = JudgePreferences(approvedPatterns = listOf("workshop.example"), blockedWords = setOf("restricted phrase"),
            wordExceptions = mapOf("restricted phrase" to setOf("technical context")))
        assertEquals(JudgeDecision.DELAY, JudgeRules.inspect("https://workshop.example", page + " restricted phrase", p).decision)
        assertEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://workshop.example", page + " restricted phrase technical context", p).decision)
        val generic = p.copy(pageExceptions = setOf("permitted context"))
        assertEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://workshop.example", page + " restricted phrase permitted context", generic).decision)
        assertEquals(JudgeDecision.DELAY, JudgeRules.inspect("https://youtube.com", page + " permitted context", generic).decision)
    }
    @Test fun extraLocksAndBlockRulesVetoBeforeFetchingEvenForBundledServices() {
        val locks = JudgePreferences(extraLocks = setOf("ilovepdf.com"))
        var fetched = false
        val result = WebsiteJudge({ fetched = true; error("Not expected") }, preferences = locks).judge("https://www.ilovepdf.com")
        assertEquals(JudgeDecision.DELAY, result.decision)
        assertFalse(fetched)
        val rules = locks.copy(extraLocks = emptySet(), domainRules = listOf(DomainRule("b", DomainAction.BLOCK, DomainMatchType.CONTAINS, "pdf")))
        assertEquals(JudgeDecision.DELAY, WebsiteJudge({ error("Not expected") }, preferences = rules).judge("https://ilovepdf.com").decision)
    }
    @Test fun positiveResultIsBoundToTheExactSettingsRevision() {
        val p = JudgePreferences(approvedPatterns = listOf("workshop.example"))
        val result = JudgeRules.inspect("https://workshop.example", page, p)
        assertEquals(p.revision, result.policyRevision)
        assertNotEquals(result.policyRevision, p.copy(extraLocks = setOf("workshop.example")).revision)
        assertNotEquals(result.policyRevision, p.copy(approvedPatterns = emptyList()).revision)
        assertNotEquals(result.policyRevision, p.copy(pageExceptions = setOf("context")).revision)
        assertNotEquals(result.policyRevision, p.copy(engineRevision = "new-engine").revision)
    }
    @Test fun canonicalSettingsOrderingDoesNotCauseSpuriousInvalidationButRuleOrderDoes() {
        val a = DomainRule("a", DomainAction.ALLOW, DomainMatchType.SUFFIX, "example")
        val b = DomainRule("b", DomainAction.BLOCK, DomainMatchType.EXACT, "workshop.example")
        val p = JudgePreferences(approvedPatterns = listOf("a.example", "b.example"), blockedWords = linkedSetOf("alpha", "beta"), domainRules = listOf(a,b))
        assertEquals(p.revision, p.copy(approvedPatterns = p.approvedPatterns.reversed(), blockedWords = linkedSetOf("beta", "alpha")).revision)
        assertNotEquals(p.revision, p.copy(domainRules = listOf(b,a)).revision)
    }
    @Test fun oldContentCannotUseQueryTextToSatisfyAContextException() {
        val p = JudgePreferences(approvedPatterns = listOf("workshop.example"), blockedWords = setOf("restricted phrase"), pageExceptions = setOf("allowed context"))
        assertEquals(JudgeDecision.DELAY, JudgeRules.inspect("https://workshop.example/?q=allowed%20context", page + " restricted phrase", p).decision)
    }
    @Test fun contentMustStillIdentifyAPracticalServiceAndUnknownSitesDoNotLearnThemselves() {
        val p = JudgePreferences(approvedPatterns = listOf("workshop.example"))
        assertNotEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://unfamiliar.example", page, p).decision)
        assertNotEquals(JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://workshop.example", "Completely unrelated text. ".repeat(10), p).decision)
    }
    @Test fun learnedHostsFlowThroughExactHostChromeNavigation() {
        val p = JudgePreferences(approvedPatterns = listOf("workshop.example"))
        val f = ChromeNavigationFilter(ChromeNavigationConfig(true, emptyList(), emptySet(), judgeHosts = setOf("workshop.example"), judgeEligibleHosts = p.eligibleHosts))
        assertNull(f.block("https://workshop.example/docs"))
        assertEquals(ChromeNavigationBlock.WHITELIST, f.block("https://sub.workshop.example/docs"))
    }
    @Test fun dnsExclusionsAlsoPreventNewGrantsAndInvalidateOldDecisions() {
        val original = JudgePreferences(approvedPatterns = listOf("workshop.example"))
        val changed = original.copy(dnsBlocks = setOf("workshop.example"))
        assertTrue(changed.learnedHosts.isEmpty())
        assertEquals(JudgeDecision.DELAY, JudgeRules.inspect("https://workshop.example", page, changed).decision)
        assertNotEquals(original.revision, changed.revision)
    }
    @Test fun automaticRefreshCanOnlyConsiderPreviouslyGrantedEligibleUnblockedHosts() {
        val p = JudgePreferences(approvedPatterns = listOf("workshop.example", "reference.example"), extraLocks = setOf("reference.example"))
        assertEquals(setOf("workshop.example", "www.ilovepdf.com"), p.refreshCandidates(setOf(
            "workshop.example", "reference.example", "www.ilovepdf.com", "youtube.com", "unknown.example")))
        assertTrue(p.refreshCandidates(emptySet()).isEmpty())
    }
}
