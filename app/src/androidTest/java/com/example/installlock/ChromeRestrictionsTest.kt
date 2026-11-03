package com.example.installlock

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChromeRestrictionsTest {
    private val captchaResources = listOf(
        "https://.www.google.com/recaptcha/",
        "https://.recaptcha.google.com/recaptcha/",
        "https://.www.recaptcha.net/recaptcha/",
        "https://.www.gstatic.com/recaptcha/"
    )

    private fun allowed(restrictions: Bundle): List<String> =
        JSONArray(restrictions.getString("URLAllowlist")).let { array ->
            (0 until array.length()).map { array.getString(it) }
        }

    @Test
    fun sharedPathChoiceReachesChromeWithItsCaseAndTrailingSlashIntact() {
        val choices = SharedSite.whitelistOptions("https://www.example.com/Topics/Sharks/Post?tracking=private")
        val selected = Store.normalizeSite(choices[1])!!
        assertEquals("example.com/Topics/Sharks/", selected)
        val restrictions = ChromeRestrictions.update(Bundle(), true, listOf(selected))!!
        assertEquals(selected, JSONArray(restrictions.getString("URLAllowlist")).getString(0))
        val blocked = ChromeRestrictions.update(restrictions, true, listOf(selected), listOf("example.com"))!!
        assertEquals("[]", blocked.getString("URLAllowlist"))
    }

    @Test
    fun missingPolicyIsRestoredWithoutTogglingLock() {
        val restored = ChromeRestrictions.update(Bundle(), true, listOf("example.com"))!!
        assertEquals("*", JSONArray(restored.getString("URLBlocklist")).getString(0))
        assertEquals("example.com", JSONArray(restored.getString("URLAllowlist")).getString(0))
        assertNull(ChromeRestrictions.update(restored, true, listOf("example.com")))
        // An external reset must be repaired even if we previously sent the same list.
        assertNotNull(ChromeRestrictions.update(Bundle(), true, listOf("example.com")))
    }

    @Test
    fun pendingSitesBlockEverythingAndActivationUpdatesPolicy() {
        val pending = ChromeRestrictions.update(Bundle(), true, emptyList())!!
        assertEquals("[\"*\"]", pending.getString("URLBlocklist"))
        assertEquals(0, JSONArray(pending.getString("URLAllowlist")).length())
        val active = ChromeRestrictions.update(pending, true, listOf("example.com/path"))!!
        assertEquals("example.com/path", JSONArray(active.getString("URLAllowlist")).getString(0))
        val removed = ChromeRestrictions.update(active, true, emptyList())!!
        assertEquals("[]", removed.getString("URLAllowlist"))
    }

    @Test
    fun oldArrayPoliciesAreMigratedAndOtherRestrictionsArePreserved() {
        val old = Bundle().apply {
            putStringArray("URLBlocklist", arrayOf("*"))
            putStringArray("URLAllowlist", arrayOf("example.com"))
            putBoolean("ForceGoogleSafeSearch", true)
        }
        val migrated = ChromeRestrictions.update(old, true, listOf("example.com"))!!
        assertEquals((listOf("example.com") + captchaResources).sorted(), allowed(migrated))
        assertTrue(migrated.getBoolean("ForceGoogleSafeSearch"))
        val off = ChromeRestrictions.update(migrated, false, emptyList())!!
        assertFalse(off.containsKey("URLBlocklist"))
        assertFalse(off.containsKey("URLAllowlist"))
        assertTrue(off.getBoolean("ForceGoogleSafeSearch"))
        assertNull(ChromeRestrictions.update(off, false, emptyList()))
    }

    @Test
    fun siteOrderDoesNotCauseRepeatedPolicyPushes() {
        val current = ChromeRestrictions.update(Bundle(), true, listOf("b.example", "a.example"))!!
        assertNull(ChromeRestrictions.update(current, true, listOf("a.example", "b.example")))
    }

    @Test
    fun extraSitesRemainBlockedWhenWhitelistIsTurnedOff() {
        val current = ChromeRestrictions.update(Bundle(), true, listOf("example.com"), listOf("blocked.com"))!!
        val off = ChromeRestrictions.update(current, false, listOf("example.com"), listOf("blocked.com"))!!
        assertEquals("[\"blocked.com\"]", off.getString("URLBlocklist"))
        assertFalse(off.containsKey("URLAllowlist"))
        assertNull(ChromeRestrictions.update(off, false, emptyList(), listOf("blocked.com")))
        val removed = ChromeRestrictions.update(off, false, emptyList(), emptyList())!!
        assertFalse(removed.containsKey("URLBlocklist"))
        assertFalse(removed.containsKey("URLAllowlist"))
    }

    @Test
    fun extraBlocksOverrideWhitelistWithoutDeletingSavedAllowEntries() {
        val whitelist = listOf("example.com", "sub.example.com/path", "other.com")
        val blocked = ChromeRestrictions.update(Bundle(), true, whitelist, listOf("example.com"))!!
        assertEquals("[\"*\",\"example.com\"]", blocked.getString("URLBlocklist"))
        assertEquals((listOf("other.com") + captchaResources).sorted(), allowed(blocked))
        val removed = ChromeRestrictions.update(blocked, true, whitelist, emptyList())!!
        assertEquals((whitelist + captchaResources).sorted(), allowed(removed))
    }

    @Test
    fun governmentNamespaceCanBeSavedAndSentToChrome() {
        val government = Store.normalizeSite("https://gov.in/")!!
        val policy = ChromeRestrictions.update(Bundle(), true, listOf(government))!!
        assertTrue(allowed(policy).contains("gov.in"))
        assertNull(ChromeNavigationFilter(ChromeNavigationConfig(true, listOf(government), emptySet()))
            .block("https://traces.tdscpc.gov.in/auth/TracesRegistration/PAORegistration"))
    }

    @Test
    fun captchaResourcesAreAddedForAnApprovedPageWithExactHttpsHostsAndPaths() {
        val policy = ChromeRestrictions.update(Bundle(), true, listOf("onlineservices.proteantech.in"))!!
        assertEquals((listOf("onlineservices.proteantech.in") + captchaResources).sorted(), allowed(policy))
        assertNull(ChromeRestrictions.update(policy, true, listOf("onlineservices.proteantech.in")))
        val removed = ChromeRestrictions.update(policy, true, emptyList())!!
        assertTrue(allowed(removed).isEmpty())
    }

    @Test
    fun captchaResourcesNeverOverrideExtraDomainLocks() {
        val blocked = ChromeRestrictions.update(Bundle(), true, listOf("onlineservices.proteantech.in"),
            listOf("google.com", "recaptcha.net", "gstatic.com"))!!
        assertEquals(listOf("onlineservices.proteantech.in"), allowed(blocked))

        val oneHostBlocked = ChromeRestrictions.update(Bundle(), true, listOf("onlineservices.proteantech.in"),
            listOf("www.google.com"))!!
        assertEquals((listOf("onlineservices.proteantech.in") +
            captchaResources.filterNot { it == "https://.www.google.com/recaptcha/" }).sorted(), allowed(oneHostBlocked))

        val approvedPageBlocked = ChromeRestrictions.update(Bundle(), true, listOf("onlineservices.proteantech.in"),
            listOf("proteantech.in"))!!
        assertTrue(allowed(approvedPageBlocked).isEmpty())
    }

    @Test
    fun domainAllowsProduceSuffixAndExactPolicyEntriesWithCaptchaSupport() {
        val rules = listOf(DomainRule("gov", DomainAction.ALLOW, DomainMatchType.SUFFIX, "gov.in"),
            DomainRule("exact", DomainAction.ALLOW, DomainMatchType.EXACT, "account.example.com"))
        val policy = ChromeRestrictions.update(Bundle(), true, emptyList(), domainRules = rules)!!
        assertEquals((listOf("gov.in", ".account.example.com") + captchaResources).sorted(), allowed(policy))
        assertNull(ChromeRestrictions.update(policy, true, emptyList(), domainRules = rules))
        val revoked = ChromeRestrictions.update(policy, true, emptyList())!!
        assertTrue(allowed(revoked).isEmpty())
    }

    @Test
    fun extraLocksRemoveExactAndSuffixDomainApprovals() {
        val rules = listOf(DomainRule("suffix", DomainAction.ALLOW, DomainMatchType.SUFFIX, "example.com"),
            DomainRule("exact", DomainAction.ALLOW, DomainMatchType.EXACT, "account.example.com"))
        val blocked = ChromeRestrictions.update(Bundle(), true, emptyList(), listOf("example.com"), rules)!!
        assertTrue(allowed(blocked).isEmpty())
    }

    @Test
    fun containsRuleOnlyPermitsVerifiedObservedHostsAndRevokesThemWithTheRule() {
        val rules = listOf(DomainRule("contains", DomainAction.ALLOW, DomainMatchType.CONTAINS, "trusted"))
        val initial = ChromeRestrictions.update(Bundle(), true, emptyList(), domainRules = rules)!!
        assertTrue(allowed(initial).isEmpty())
        val observed = ChromeRestrictions.update(initial, true, emptyList(), domainRules = rules,
            observedAllowedHosts = setOf("trusted.example", "evil.example"))!!
        assertEquals((listOf(".trusted.example") + captchaResources).sorted(), allowed(observed))
        val blocked = ChromeRestrictions.update(observed, true, emptyList(), listOf("trusted.example"), rules,
            setOf("trusted.example"))!!
        assertTrue(allowed(blocked).isEmpty())
        val revoked = ChromeRestrictions.update(observed, true, emptyList(), observedAllowedHosts = setOf("trusted.example"))!!
        assertTrue(allowed(revoked).isEmpty())
    }
}
