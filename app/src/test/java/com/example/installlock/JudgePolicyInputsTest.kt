package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class JudgePolicyInputsTest {
    private val base = JudgePolicyInputs(approvedPatterns = listOf("workshop.example"))

    @Test fun unchangedInputsCompileOnceAndReuseTheDerivedPolicy() {
        var builds = 0
        val cache = ParsedValueCache<JudgePolicyInputs, JudgePreferences> { builds++; it.compile() }
        val first = cache.get(base)
        repeat(1000) {
            assertSame(first, cache.get(base.copy(approvedPatterns = listOf("workshop.example"))))
        }
        assertEquals(1, builds)
        assertTrue("workshop.example" in first.learnedHosts)
    }

    @Test fun everySecurityRelevantInputInvalidatesTheCacheImmediately() {
        val cache = ParsedValueCache<JudgePolicyInputs, JudgePreferences> { it.compile() }
        val initial = cache.get(base)
        val changes = listOf(
            base.copy(approvedPatterns = emptyList()), base.copy(blockedWords = setOf("workshop")),
            base.copy(wordExceptions = mapOf("workshop" to setOf("context"))),
            base.copy(pageExceptions = setOf("context")), base.copy(extraLocks = setOf("workshop.example")),
            base.copy(dnsBlocks = setOf("workshop.example")),
            base.copy(domainRules = listOf(DomainRule("b", DomainAction.BLOCK, DomainMatchType.EXACT, "workshop.example"))),
            base.copy(keywordFilterEnabled = false), base.copy(leisureCatalog = setOf("workshop.example")),
            base.copy(engineRevision = "new-engine"))
        for (changed in changes) {
            assertNotSame(initial, cache.get(changed))
            assertEquals(changed.compile(), cache.get(changed))
            cache.get(base)
        }
        assertTrue(cache.get(base.copy(extraLocks = setOf("workshop.example"))).learnedHosts.isEmpty())
    }

    @Test fun pendingApprovalActivationAndDomainRuleOrderArePartOfTheInput() {
        val allow = DomainRule("a", DomainAction.ALLOW, DomainMatchType.SUFFIX, "example")
        val block = DomainRule("b", DomainAction.BLOCK, DomainMatchType.EXACT, "workshop.example")
        val cache = ParsedValueCache<JudgePolicyInputs, JudgePreferences> { it.compile() }
        assertTrue(cache.get(base.copy(approvedPatterns = emptyList())).learnedHosts.isEmpty())
        assertTrue("workshop.example" in cache.get(base).learnedHosts)
        assertTrue("workshop.example" in cache.get(base.copy(domainRules = listOf(allow, block))).learnedHosts)
        assertTrue(cache.get(base.copy(domainRules = listOf(block, allow))).learnedHosts.isEmpty())
    }

    @Test fun leisureSuffixLookupRetainsExactAndSubdomainMatchingWithoutLookalikeTrust() {
        val catalog = (0..2500).map { "catalog$it.example" }.toSet() + "leisure.example"
        for (host in listOf("leisure.example", "deep.sub.leisure.example", "youtube.com", "www.youtube.com"))
            assertTrue(host, JudgeRules.leisureHost(host, catalog))
        for (host in listOf("notleisure.example", "leisure.example.safe.test", "workshop.example"))
            assertFalse(host, JudgeRules.leisureHost(host, catalog))
        assertTrue(JudgeRules.leisureHost("bad host", catalog))
    }
}
