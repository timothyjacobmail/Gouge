package com.example.installlock

import android.content.Context

internal object JudgeStore {
    private fun prefs(c: Context) = c.getSharedPreferences("website_judge", Context.MODE_PRIVATE)
    fun saved(c: Context): Set<String> = prefs(c).getStringSet("hosts", emptySet()).orEmpty().toSet()
    fun active(c: Context): Set<String> = if (!BrowserKeywordService.isReady(c)) emptySet()
        else JudgeRules.activeHosts(saved(c), Store.extraLockedSites(c).map { it.domain }.toSet(), Store.domainRules(c))

    fun grant(c: Context, judgement: Judgement): Boolean {
        if (judgement.decision != JudgeDecision.IMMEDIATE || judgement.hosts.isEmpty() || !Policy.isOwner(c) ||
            !BrowserKeywordService.isReady(c)) return false
        val allowed = JudgeRules.activeHosts(judgement.hosts, Store.extraLockedSites(c).map { it.domain }.toSet(), Store.domainRules(c))
        if (allowed != judgement.hosts) return false
        return prefs(c).edit().putStringSet("hosts", saved(c) + allowed).commit()
    }

    fun remove(c: Context, host: String) {
        prefs(c).edit().putStringSet("hosts", saved(c) - host).commit()
        Policy.syncChrome(c)
    }
}
