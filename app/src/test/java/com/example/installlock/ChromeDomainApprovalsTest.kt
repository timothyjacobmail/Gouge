package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class ChromeDomainApprovalsTest {
    private val government = DomainRule("gov", DomainAction.ALLOW, DomainMatchType.SUFFIX, "gov.in")
    private val exact = DomainRule("exact", DomainAction.ALLOW, DomainMatchType.EXACT, "account.example.com")
    private val contains = DomainRule("contains", DomainAction.ALLOW, DomainMatchType.CONTAINS, "trusted")
    private val block = DomainRule("block", DomainAction.BLOCK, DomainMatchType.SUFFIX, "trusted.example")

    @Test fun suffixAndExactRulesRetainTheirDifferentChromeScopes() {
        assertEquals(listOf("gov.in", ".account.example.com"),
            ChromeDomainApprovals(listOf(government, exact)).policyPatterns())
    }

    @Test fun containsRulesNeverCreateAUniversalPolicyException() {
        val approvals = ChromeDomainApprovals(listOf(contains))
        assertTrue(approvals.policyPatterns().isEmpty())
        assertTrue(approvals.needsObservedHost("trusted.example"))
        assertEquals(listOf(".trusted.example"),
            approvals.policyPatterns(setOf("trusted.example", "unrelated.example", "https://trusted.example/")))
    }

    @Test fun earlierBlocksPreventObservedApprovalsAndReorderingChangesTheDecision() {
        val blocked = ChromeDomainApprovals(listOf(block, contains))
        assertFalse(blocked.needsObservedHost("trusted.example"))
        assertTrue(blocked.policyPatterns(setOf("trusted.example")).isEmpty())
        val allowed = ChromeDomainApprovals(listOf(contains, block))
        assertTrue(allowed.needsObservedHost("trusted.example"))
        assertEquals(listOf(".trusted.example"), allowed.policyPatterns(setOf("trusted.example")))
    }

    @Test fun removedOrChangedRulesRevokePreviouslyObservedHosts() {
        val observed = setOf("trusted.example")
        assertTrue(ChromeDomainApprovals(emptyList()).policyPatterns(observed).isEmpty())
        assertTrue(ChromeDomainApprovals(listOf(contains.copy(value = "different"))).policyPatterns(observed).isEmpty())
        assertTrue(ChromeDomainApprovals(listOf(contains.copy(action = DomainAction.BLOCK))).policyPatterns(observed).isEmpty())
    }

    @Test fun existingStaticApprovalsAvoidUnnecessaryContainsHostExceptions() {
        val approvals = ChromeDomainApprovals(listOf(contains, government, exact))
        assertFalse(approvals.needsObservedHost("trusted.gov.in"))
        assertFalse(ChromeDomainApprovals(listOf(contains, exact.copy(value = "trusted.example")))
            .needsObservedHost("trusted.example"))
        assertTrue(approvals.needsObservedHost("trustedgov.in"))
        assertEquals(listOf("gov.in", ".account.example.com"), approvals.policyPatterns(setOf("trusted.gov.in")))
    }
}
