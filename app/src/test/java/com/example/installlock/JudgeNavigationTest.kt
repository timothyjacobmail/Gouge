package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class JudgeNavigationTest {
    private fun filter(hosts: Set<String> = setOf("www.edcad.ae"), extras: Set<String> = emptySet(),
                       rules: List<DomainRule> = emptyList()) =
        ChromeNavigationFilter(ChromeNavigationConfig(true, emptyList(), extras, rules, hosts))

    @Test fun reviewedExactHttpsHostOpensButNeverRelatedSubdomainsOrInsecurePorts() {
        assertNull(filter().block("https://www.edcad.ae/en"))
        for (url in listOf("https://edcad.ae", "https://pay.edcad.ae", "https://other.www.edcad.ae", "http://www.edcad.ae",
            "https://www.edcad.ae:8443", "https://www.edcad.ae.attacker.com"))
            assertEquals(url, ChromeNavigationBlock.WHITELIST, filter().block(url))
    }

    @Test fun injectedLeisureAndUnknownHostsCannotBecomeJudgeApprovals() {
        for (host in listOf("youtube.com", "reddit.com", "unknown.example", "sub.edcad.ae"))
            assertEquals(ChromeNavigationBlock.WHITELIST, filter(setOf(host)).block("https://$host/"))
    }

    @Test fun otherLocksTakePriorityAndRevocationClosesTheHost() {
        assertEquals(ChromeNavigationBlock.EXTRA_SITE, filter(extras = setOf("edcad.ae")).block("https://www.edcad.ae"))
        assertEquals(ChromeNavigationBlock.DOMAIN_RULE, filter(rules = listOf(DomainRule("b", DomainAction.BLOCK,
            DomainMatchType.SUFFIX, "edcad.ae"))).block("https://www.edcad.ae"))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter(emptySet()).block("https://www.edcad.ae"))
    }

    @Test fun leisureAndRedirectRoutesRemainOutsideJudgeEvenOnAnApprovedHost() {
        assertEquals(ChromeNavigationBlock.WHITELIST, filter().block("https://www.edcad.ae/games"))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter().block("https://www.edcad.ae/redirect?url=https://youtube.com"))
    }
}
