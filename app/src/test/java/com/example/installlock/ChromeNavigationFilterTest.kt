package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class ChromeNavigationFilterTest {
    private fun filter(vararg patterns: String, on: Boolean = true, extras: Set<String> = emptySet(),
                       rules: List<DomainRule> = emptyList()) =
        ChromeNavigationFilter(ChromeNavigationConfig(on, patterns.toList(), extras, rules))

    @Test fun savedSuffixAllowsGrantChromeAccessWithoutAnotherWebsiteEntry() {
        val rules = listOf("gov.in", "ae", "org").map { DomainRule(it, DomainAction.ALLOW, DomainMatchType.SUFFIX, it) }
        val guard = filter(rules = rules)
        for (address in listOf("https://traces.tdscpc.gov.in/auth/TracesRegistration/PAORegistration",
            "https://portal.example.ae/", "https://example.org/")) assertNull(address, guard.block(address))
        for (address in listOf("https://gov.in.example.com/", "https://notgov.in/", "https://evilorg.com/"))
            assertEquals(address, ChromeNavigationBlock.WHITELIST, guard.block(address))
    }

    @Test fun firstDomainMatchStillWinsAndExtraLocksOverrideAnAllow() {
        val block = DomainRule("block", DomainAction.BLOCK, DomainMatchType.CONTAINS, "manga")
        val allow = DomainRule("allow", DomainAction.ALLOW, DomainMatchType.SUFFIX, "org")
        assertEquals(ChromeNavigationBlock.DOMAIN_RULE,
            filter("manga.org", rules = listOf(block, allow)).block("https://manga.org/"))
        assertNull(filter(rules = listOf(allow, block)).block("https://manga.org/"))
        assertEquals(ChromeNavigationBlock.EXTRA_SITE,
            filter(rules = listOf(allow), extras = setOf("manga.org")).block("https://sub.manga.org/"))
        assertEquals(ChromeNavigationBlock.DOMAIN_RULE,
            filter(on = false, rules = listOf(block, allow)).block("https://manga.org/"))
    }

    @Test fun exactAndContainsApprovalsUseOnlyTheActualHostname() {
        val exact = DomainRule("exact", DomainAction.ALLOW, DomainMatchType.EXACT, "trusted.example")
        assertNull(filter(rules = listOf(exact)).block("https://TRUSTED.example:8443/anything"))
        assertEquals(ChromeNavigationBlock.WHITELIST,
            filter(rules = listOf(exact)).block("https://sub.trusted.example/"))
        val contains = exact.copy(type = DomainMatchType.CONTAINS, value = "trusted")
        assertNull(filter(rules = listOf(contains)).block("https://sub.trusted.example/"))
        for (address in listOf("https://evil.example/trusted", "https://trusted.example@evil.example/",
            "https://evil.example/?next=https://trusted.example/"))
            assertEquals(address, ChromeNavigationBlock.WHITELIST, filter(rules = listOf(contains)).block(address))
    }

    @Test fun removingDomainApprovalRestoresWhitelistChecksWhileManualEntriesRemain() {
        val rule = DomainRule("allow", DomainAction.ALLOW, DomainMatchType.SUFFIX, "gov.in")
        val traces = "https://traces.tdscpc.gov.in/auth/TracesRegistration/PAORegistration"
        assertNull(filter(rules = listOf(rule)).block(traces))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter().block(traces))
        assertNull(filter("tdscpc.gov.in").block(traces))
    }

    @Test fun subscriptionsToHomeOrShortsIsRecheckedWithoutReloading() {
        val guard = filter("m.youtube.com/feed/subscriptions")
        assertNull(guard.block("https://m.youtube.com/feed/subscriptions"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("https://m.youtube.com/"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("m.youtube.com/shorts/video123"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("m.youtube.com/feed/you"))
        assertNull(guard.block("m.youtube.com/feed/subscriptions?app=m#latest"))
    }

    @Test fun broaderEntriesKeepTheirExistingScope() {
        val guard = filter("youtube.com")
        assertNull(guard.block("m.youtube.com/shorts/video"))
        assertNull(guard.block("www.youtube.com/"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("notyoutube.com/feed/subscriptions"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("youtube.com.evil.example/feed/subscriptions"))
    }

    @Test fun governmentNamespaceIncludesTracesButNotLookalikesOrExtraLockedAgencies() {
        val traces = "https://traces.tdscpc.gov.in/auth/TracesRegistration/PAORegistration"
        val guard = filter("gov.in")
        assertNull(guard.block(traces))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("https://notgov.in/auth/"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("https://traces.tdscpc.gov.in.evil.example/auth/"))
        assertEquals(ChromeNavigationBlock.EXTRA_SITE,
            filter("gov.in", extras = setOf("tdscpc.gov.in")).block(traces))
    }

    @Test fun embeddedCaptchaExceptionsDoNotApproveStandaloneGoogleDestinations() {
        val guard = filter("onlineservices.proteantech.in")
        assertNull(guard.block("https://onlineservices.proteantech.in/paam/ReprintEPan.html"))
        for (address in listOf("https://www.google.com/search?q=example", "https://www.google.com/recaptcha/api2/anchor?k=example",
            "https://www.recaptcha.net/recaptcha/api2/bframe", "https://www.gstatic.com/recaptcha/releases/example/recaptcha__en.js"))
            assertEquals(address, ChromeNavigationBlock.WHITELIST, guard.block(address))
    }

    @Test fun directorySlashAndPathCaseArePreserved() {
        val guard = filter("reddit.com/r/sharks/")
        assertNull(guard.block("https://www.reddit.com/r/sharks/posts"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("reddit.com/r/sharksOther/"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("reddit.com/r/Sharks/posts"))
        // Chrome uses a prefix, rather than adding a slash boundary the user did not save.
        assertNull(filter("example.com/Read").block("https://example.com/Reading"))
    }

    @Test fun extraBlocksOverrideEvenABroadWhitelistAndRemainActiveWithWhitelistOff() {
        for (on in listOf(true, false)) {
            val guard = filter("youtube.com", on = on, extras = setOf("m.youtube.com"))
            assertEquals(ChromeNavigationBlock.EXTRA_SITE, guard.block("m.youtube.com/feed/subscriptions"))
            assertEquals(ChromeNavigationBlock.EXTRA_SITE, guard.block("sub.m.youtube.com/"))
            assertNull(guard.block("www.youtube.com/"))
        }
    }

    @Test fun noActiveEntriesMeansNoWebPagesAreAllowedWhenWhitelistIsOn() {
        assertEquals(ChromeNavigationBlock.WHITELIST, filter().block("m.youtube.com/feed/subscriptions"))
        assertNull(filter(on = false).block("m.youtube.com/shorts/video"))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter("broken entry").block("example.com/"))
    }

    @Test fun queryRestrictionsCannotBeEscapedByDuplicateParameters() {
        val guard = filter("youtube.com/watch?v=allowed")
        assertNull(guard.block("m.youtube.com/watch?other=1&v=allowed#time"))
        assertNull(guard.block("m.youtube.com/watch?v=allowed&v=allowed"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("m.youtube.com/watch?v=another"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("m.youtube.com/watch?v=allowed&v=another"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("m.youtube.com/watch"))
    }

    @Test fun explicitPortsAndDefaultWebPortsAreRespected() {
        val guard = filter("example.com:443/Read/")
        assertNull(guard.block("https://EXAMPLE.COM/Read/post"))
        assertNull(guard.block("example.com/Read/post"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("http://example.com/Read/post"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("https://example.com:8443/Read/post"))
        assertNull(filter("example.com/Read/").block("https://example.com:8443/Read/post"))
    }

    @Test fun dotSegmentsCannotConcealNavigationOutsideTheAllowedDirectory() {
        val guard = filter("m.youtube.com/feed/subscriptions")
        for (path in listOf("/feed/subscriptions/../../shorts/123", "/feed/subscriptions/%2e%2e/%2E%2E/shorts/123"))
            assertEquals(path, ChromeNavigationBlock.WHITELIST, guard.block("https://m.youtube.com$path"))
    }

    @Test fun repeatedSlashesAreNotCollapsedIntoAnAllowedPath() {
        val guard = filter("example.com/feed/subscriptions")
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("example.com/feed//subscriptions"))
        assertNull(guard.block("example.com/feed/./subscriptions"))
        assertNull(filter("example.com:80/feed/").block("HTTP://EXAMPLE.COM/feed/subscriptions"))
    }

    @Test fun fragmentsDoNotWidenTheAllowedPathAndEscapesStayCaseSensitiveForText() {
        assertEquals(ChromeNavigationBlock.WHITELIST,
            filter("m.youtube.com/feed/subscriptions").block("m.youtube.com/shorts/123#/feed/subscriptions"))
        assertNull(filter("example.com/A%2Fb/").block("https://example.com/A%2fb/post"))
        assertEquals(ChromeNavigationBlock.WHITELIST,
            filter("example.com/A%2Fb/").block("https://example.com/a%2fb/post"))
    }

    @Test fun internalPagesAndUncommittedSearchTextAreNotTreatedAsWebUrls() {
        for (address in listOf("chrome://newtab", "chrome://settings", "about:blank", "youtube subscriptions", "",
            "https://example.com:bad/path", "javascript:alert(1)")) assertNull(address, filter().block(address))
    }

    @Test fun actualHostWinsOverUserInfoOrAnAllowedAddressInTheQuery() {
        val guard = filter("m.youtube.com/feed/subscriptions")
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("https://m.youtube.com@evil.example/feed/subscriptions"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("https://evil.example/?next=https://m.youtube.com/feed/subscriptions"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("evil.example/?next=https://m.youtube.com/feed/subscriptions"))
        assertNull(filter("example.com/redirect").block("example.com/redirect?next=https://another.example/"))
    }

    @Test fun localCallbackRuleAllowsChangingCodesWithoutOpeningOtherPathsOrPorts() {
        val guard = filter(SitePattern.normalizeForEntry("http://localhost:40715/callback?state=old-state&code=old-code")!!)
        assertNull(guard.block("http://localhost:40715/callback?state=new-state&iss=https://accounts.example&code=new-code"))
        assertNull(guard.block("localhost:40715/callback?code=next-code"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("http://localhost:40715/other"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("http://localhost:40716/callback?code=next-code"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("https://localhost.evil.example:40715/callback"))
    }

    @Test fun anExplicitPortlessLocalRuleSupportsChangingCallbackPorts() {
        val guard = filter("localhost/callback")
        assertNull(guard.block("http://localhost:40715/callback?code=first-code"))
        assertNull(guard.block("http://localhost:49315/callback?code=second-code"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("http://localhost:49315/other"))
    }

    @Test fun existingQueryScopedLoopbackRulesAreNotBroadenedWhileMatching() {
        val guard = filter("127.0.0.1:40715/callback?mode=allowed")
        assertNull(guard.block("http://127.0.0.1:40715/callback?mode=allowed"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("http://127.0.0.1:40715/callback?mode=other"))
        assertEquals(ChromeNavigationBlock.WHITELIST, guard.block("http://127.0.0.1:40715/callback"))
    }
}
