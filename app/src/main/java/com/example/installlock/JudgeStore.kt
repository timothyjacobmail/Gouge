package com.example.installlock

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.Executors

internal object JudgeStore {
    private val snapshots = ParsedValueCache<JudgePolicyInputs, JudgePreferences> { it.compile() }
    @Volatile private var engineRevision: String? = null
    private val refreshExecutor = Executors.newSingleThreadExecutor()
    private val attempts = mutableMapOf<String, Pair<String, Long>>()
    private var refreshing = false
    private fun prefs(c: Context) = c.getSharedPreferences("website_judge", Context.MODE_PRIVATE)
    fun snapshot(c: Context): JudgePreferences {
        val now = System.currentTimeMillis()
        return snapshots.get(JudgePolicyInputs(
            approvedPatterns = Store.sites(c).filter { it.activeAt <= now }.map { it.pattern },
            blockedWords = Store.browserKeywords(c), wordExceptions = Store.keywordExceptions(c),
            pageExceptions = Store.genericKeywordExceptions(c),
            extraLocks = Store.extraLockedSites(c).map { it.domain }.toSet(), dnsBlocks = Store.blockedDomains(c), domainRules = Store.domainRules(c),
            keywordFilterEnabled = Store.browserKeywordFilterEnabled(c), leisureCatalog = WatchdogStore.domains(c),
            engineRevision = engineRevision ?: run {
                @Suppress("DEPRECATION") val revision = "personalized-v1:" + c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
                engineRevision = revision
                revision
            }))
    }
    fun eligible(c: Context) = snapshot(c).eligibleHosts
    /** Recheck only previously approved Judge hosts, keeping stale grants inactive. */
    @Synchronized fun refreshStale(context: Context) {
        if (refreshing || !Policy.isOwner(context) || !Store.chromeLock(context) || !BrowserKeywordService.isReady(context)) return
        val c = context.applicationContext
        val policy = snapshot(c)
        val p = prefs(c)
        attempts.keys.retainAll(saved(c))
        val now = SystemClock.elapsedRealtime()
        val candidates = policy.refreshCandidates(saved(c)).filter { host ->
            p.getString("policy.$host", null) != policy.revision &&
                attempts[host]?.let { (revision, time) -> revision != policy.revision || now - time >= 5 * 60_000L } != false
        }.take(4)
        if (candidates.isEmpty()) return
        refreshing = true
        candidates.forEach { attempts[it] = policy.revision to now }
        refreshExecutor.execute {
            try {
                for (host in candidates) {
                    // A rule change or manual removal while queued cancels this operation.
                    if (host !in saved(c) || snapshot(c).revision != policy.revision) continue
                    val result = WebsiteJudge(leisureCatalog = policy.leisureCatalog, preferences = policy).judge("https://$host/")
                    if (result.decision == JudgeDecision.IMMEDIATE && result.hosts == setOf(host) && host in saved(c)) {
                        if (grant(c, result, requireExisting = true)) Policy.syncChrome(c)
                    }
                }
            } catch (_: Exception) {
                // Network/provider failures leave approvals inactive and eligible for a later retry.
            } finally { synchronized(this) { refreshing = false } }
        }
    }
    fun saved(c: Context): Set<String> = prefs(c).getStringSet("hosts", emptySet()).orEmpty().toSet()
    fun active(c: Context): Set<String> = if (!BrowserKeywordService.isReady(c)) emptySet()
        else snapshot(c).let { policy ->
            JudgeRules.activeHosts(saved(c).filter { prefs(c).getString("policy.$it", null) == policy.revision }.toSet(),
                policy.extraLocks, policy.domainRules, policy.eligibleHosts)
                .filter { policy.veto("https://$it/") == null }.toSet()
        }

    @Synchronized fun grant(c: Context, judgement: Judgement, requireExisting: Boolean = false): Boolean {
        if (requireExisting && judgement.hosts.any { it !in saved(c) }) return false
        if (rejectionReason(c, judgement) != null) return false
        return prefs(c).edit().putStringSet("hosts", saved(c) + judgement.hosts).apply {
            judgement.hosts.forEach { putString("policy.$it", judgement.policyRevision) }
        }.commit()
    }

    fun rejectionReason(c: Context, judgement: Judgement): String? {
        if (judgement.decision != JudgeDecision.IMMEDIATE || judgement.hosts.isEmpty()) return "The website did not pass Judge."
        val policy = snapshot(c)
        if (judgement.policyRevision != policy.revision) return "Your website rules changed while Judge was checking. Judge this page again with your current rules."
        if (!Policy.isOwner(c)) return "Device-owner setup is required."
        if (!BrowserKeywordService.isEnabled(c)) return "Enable Gouge's browser accessibility service before granting immediate access."
        if (!BrowserKeywordService.isReady(c)) return "Browser monitoring is not ready. Finish banking mode if active, then reconnect Gouge's browser accessibility service."
        judgement.hosts.forEach { policy.veto("https://$it/")?.let { reason -> return reason } }
        val allowed = JudgeRules.activeHosts(judgement.hosts, policy.extraLocks, policy.domainRules, policy.eligibleHosts)
        if (allowed != judgement.hosts) return "An extra site lock, ordered Block rule, or the reviewed-domain policy prevents this grant."
        return null
    }

    @Synchronized fun remove(c: Context, host: String) {
        prefs(c).edit().putStringSet("hosts", saved(c) - host).remove("policy.$host").commit()
        Policy.syncChrome(c)
    }
}
