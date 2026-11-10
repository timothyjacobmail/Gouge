package com.example.installlock

import android.content.Context

internal object JudgeStore {
    private fun prefs(c: Context) = c.getSharedPreferences("website_judge", Context.MODE_PRIVATE)
    fun saved(c: Context): Set<String> = prefs(c).getStringSet("hosts", emptySet()).orEmpty().toSet()
    fun active(c: Context): Set<String> = if (!BrowserKeywordService.isReady(c)) emptySet()
        else JudgeRules.activeHosts(saved(c), Store.extraLockedSites(c).map { it.domain }.toSet(), Store.domainRules(c))

    fun grant(c: Context, judgement: Judgement): Boolean {
        if (rejectionReason(c, judgement) != null) return false
        return prefs(c).edit().putStringSet("hosts", saved(c) + judgement.hosts).commit()
    }

    fun rejectionReason(c: Context, judgement: Judgement): String? {
        if (judgement.decision != JudgeDecision.IMMEDIATE || judgement.hosts.isEmpty()) return "The website did not pass Judge."
        if (!Policy.isOwner(c)) return "Device-owner setup is required."
        if (!BrowserKeywordService.isEnabled(c)) return "Enable Install Lock's browser accessibility service before granting immediate access."
        if (!BrowserKeywordService.isReady(c)) return "Browser monitoring is not ready. Finish banking mode if active, then reconnect Install Lock's browser accessibility service."
        val allowed = JudgeRules.activeHosts(judgement.hosts, Store.extraLockedSites(c).map { it.domain }.toSet(), Store.domainRules(c))
        if (allowed != judgement.hosts) return "An extra site lock, ordered Block rule, or the reviewed-domain policy prevents this grant."
        return null
    }

    fun remove(c: Context, host: String) {
        prefs(c).edit().putStringSet("hosts", saved(c) - host).commit()
        Policy.syncChrome(c)
    }
}
