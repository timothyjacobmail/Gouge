package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class JudgeUtilitySitesTest {
    private val pdf = "<title>iLovePDF document tools</title><main>" +
        "Merge and split PDF files, compress documents, convert office documents, edit pages and sign PDF forms. " +
        "Work with digital documents using iLovePDF tools for document organization.</main>"
    private val safety = "<title>Adhoc Safety</title><main>" +
        "Adhoc provides fire safety and security systems. Our engineers install fire alarm and suppression systems, " +
        "perform planned maintenance and prepare emergency evacuation plans for buildings.</main>"

    @Test fun reviewedPdfAndSafetyHostsPassWithTheirOwnPurposeEvidence() {
        for ((hosts, html) in listOf(listOf("ilovepdf.com", "www.ilovepdf.com") to pdf,
            listOf("adhocsafety.com", "www.adhocsafety.com") to safety)) {
            for (host in hosts) {
                val result = JudgeRules.inspect("https://$host/", html)
                assertEquals(host, JudgeDecision.IMMEDIATE, result.decision)
                assertEquals(setOf(host), result.hosts)
            }
        }
    }
    @Test fun doesNotTrustArbitrarySubdomainsOrSimilarNames() {
        for (host in listOf("anything.ilovepdf.com", "ilovepdf.com.attacker.example", "myadhocsafety.com",
            "anything.adhocsafety.com", "adhocsafety.com.attacker.example")) {
            assertNotEquals(host, JudgeDecision.IMMEDIATE, JudgeRules.inspect("https://$host", pdf + safety).decision)
        }
    }
    @Test fun cannotReuseAnotherReviewedServicesContentAsEvidence() {
        assertEquals(JudgeDecision.UNVERIFIED, JudgeRules.inspect("https://ilovepdf.com", safety).decision)
        assertEquals(JudgeDecision.UNVERIFIED, JudgeRules.inspect("https://adhocsafety.com", pdf).decision)
    }
    @Test fun newServicesCannotOverrideLeisureOrUnreadableContent() {
        assertEquals(JudgeDecision.DELAY, JudgeRules.inspect("https://ilovepdf.com", pdf + " Watch anime movies").decision)
        assertEquals(JudgeDecision.DELAY, JudgeRules.inspect("https://adhocsafety.com/games", safety).decision)
        assertEquals(JudgeDecision.UNVERIFIED, JudgeRules.inspect("https://adhocsafety.com", "Login").decision)
        assertEquals(JudgeDecision.DELAY, JudgeRules.inspect("https://youtube.com", pdf).decision)
    }
    @Test fun newGrantsStillHonorExtraLocksAndOrderedBlockRules() {
        val hosts = setOf("www.ilovepdf.com", "www.adhocsafety.com")
        assertEquals(setOf("www.adhocsafety.com"), JudgeRules.activeHosts(hosts, setOf("ilovepdf.com"), emptyList()))
        assertTrue(JudgeRules.activeHosts(hosts, emptySet(), listOf(
            DomainRule("pdf", DomainAction.BLOCK, DomainMatchType.SUFFIX, "ilovepdf.com"),
            DomainRule("safety", DomainAction.BLOCK, DomainMatchType.SUFFIX, "adhocsafety.com"))).isEmpty())
    }
    @Test fun nativeNavigationUsesExactHostsForNewServices() {
        val filter = ChromeNavigationFilter(ChromeNavigationConfig(true, emptyList(), emptySet(),
            judgeHosts = setOf("www.ilovepdf.com", "www.adhocsafety.com")))
        assertNull(filter.block("https://www.ilovepdf.com/merge_pdf"))
        assertNull(filter.block("https://www.adhocsafety.com/services"))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter.block("https://other.ilovepdf.com/"))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter.block("https://youtube.com/"))
    }
}
