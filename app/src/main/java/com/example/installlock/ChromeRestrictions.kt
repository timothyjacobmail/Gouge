package com.example.installlock

import android.os.Bundle
import org.json.JSONArray

internal object ChromeRestrictions {
    private const val BLOCKLIST = "URLBlocklist"
    private const val ALLOWLIST = "URLAllowlist"
    // Chrome also filters iframe navigations. These endpoints let an approved page
    // display reCAPTCHA without allowing Google Search or other Google pages.
    // Keep these out of ChromeNavigationFilter: they are embedded resources, not
    // independently approved destinations in the native address bar.
    private val captchaResources = listOf(
        "www.google.com/recaptcha/",
        "recaptcha.google.com/recaptcha/",
        "www.recaptcha.net/recaptcha/",
        "www.gstatic.com/recaptcha/"
    )

    /** Null means Android already holds the requested policy. Preserve unrelated restrictions. */
    @Suppress("DEPRECATION")
    fun update(current: Bundle, lockOn: Boolean, activePatterns: List<String>,
               extraLockedDomains: List<String> = emptyList(), domainRules: List<DomainRule> = emptyList(),
               observedAllowedHosts: Set<String> = emptySet(), judgeHosts: Set<String> = emptySet(),
               judgeEligibleHosts: Set<String> = JudgeRules.reviewedHosts,
               temporaryUrls: List<String> = emptyList()): Bundle? {
        if (!lockOn && extraLockedDomains.isEmpty()) {
            if (!current.containsKey(BLOCKLIST) && !current.containsKey(ALLOWLIST)) return null
            return Bundle(current).apply {
                remove(BLOCKLIST)
                remove(ALLOWLIST)
            }
        }

        // Chrome's Android managed configuration declares these lists as JSON strings.
        val extras = ExtraSiteLocks(extraLockedDomains.toSet())
        val blocked = JSONArray((if (lockOn) listOf("*") else emptyList()) + extras.domains.sorted()).toString()
        val judged = JudgeRules.activeHosts(judgeHosts, extraLockedDomains.toSet(), domainRules, judgeEligibleHosts)
            .map { "https://.$it:443/" }
        val ordered = DomainRules(domainRules)
        val temporary = temporaryUrls.flatMap(TemporaryLinkRules::policyPatterns).filter { pattern ->
            val host = pattern.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix(".")
            !extras.domains.any { host == it || host.endsWith(".$it") } &&
                ordered.match(host)?.rule?.action != DomainAction.BLOCK
        }
        val websites = extras.allowedPatterns(activePatterns + ChromeDomainApprovals(domainRules).policyPatterns(observedAllowedHosts)) + judged + temporary
        val resources = if (websites.isEmpty()) emptyList() else
            extras.allowedPatterns(captchaResources).map { "https://.$it" }
        val allowed = if (lockOn) JSONArray((websites + resources).distinct().sorted()).toString() else null
        if (current[BLOCKLIST] == blocked && current[ALLOWLIST] == allowed &&
            (lockOn || !current.containsKey(ALLOWLIST))) return null
        return Bundle(current).apply {
            putString(BLOCKLIST, blocked)
            if (allowed != null) putString(ALLOWLIST, allowed) else remove(ALLOWLIST)
        }
    }
}
