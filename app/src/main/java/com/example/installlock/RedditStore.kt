package com.example.installlock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock

/** Rules and remembered client identities belong to Gouge, not to the Reddit installation. */
internal object RedditStore {
    const val ENABLED = "redditEnabled"
    const val ALLOWED = "redditAllowed"
    const val CLIENTS = "redditClients"
    const val DETECTED = "redditDetectedClients"
    private const val HISTORY = "redditClientHistory"
    val keys = setOf(ENABLED, ALLOWED, CLIENTS, DETECTED)
    private fun prefs(c: Context) = c.getSharedPreferences("install_lock", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean(ENABLED, false)
    fun allowed(c: Context): Set<String> = prefs(c).getStringSet(ALLOWED, emptySet()).orEmpty().mapNotNull(RedditRules::normalize).toSet()
    /** Derived from approved membership so existing entries work too, with no second timer or stale copies. */
    fun chromePatterns(c: Context): List<String> = allowed(c).sorted().map { "reddit.com/r/$it/" }
    fun manualClients(c: Context): Set<String> = prefs(c).getStringSet(CLIENTS, emptySet()).orEmpty().toSet()
    fun detected(c: Context): Set<String> = prefs(c).getStringSet(DETECTED, emptySet()).orEmpty().toSet()
    fun targets(c: Context): Set<String> = detected(c) + manualClients(c) + RedditRules.OFFICIAL
    fun trackedClients(c: Context): Set<String> = targets(c) + prefs(c).getStringSet(HISTORY, emptySet()).orEmpty()
    fun credentialsReady(c: Context) = RedditRules.credentialKinds.all { TaskPasswords.has(c, TaskScope(it)) }

    fun enable(c: Context): Boolean = credentialsReady(c) && prefs(c).edit().putBoolean(ENABLED, true).commit()
    fun disable(c: Context, password: String) = TaskPasswords.run(c, TaskScope(TaskKind.REDDIT_OFF), password) {
        prefs(c).edit().putBoolean(ENABLED, false).commit()
    }
    @Synchronized fun addAllowed(c: Context, input: String, password: String): Boolean {
        val name = RedditRules.normalize(input) ?: return false
        return TaskPasswords.run(c, TaskScope(TaskKind.REDDIT_ALLOW), password) {
            prefs(c).edit().putStringSet(ALLOWED, allowed(c) + name).commit()
        }
    }
    @Synchronized fun removeAllowed(c: Context, name: String): Boolean =
        prefs(c).edit().putStringSet(ALLOWED, allowed(c) - name).commit()

    fun validClient(c: Context, pkg: String): Boolean = pkg != c.packageName &&
        Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(pkg) && pkg !in setOf("android", "com.android.systemui", "com.android.settings") &&
        c.packageManager.getLaunchIntentForPackage(pkg) != null

    @Synchronized fun addClient(c: Context, pkg: String): Boolean = validClient(c, pkg) &&
        prefs(c).edit().putStringSet(CLIENTS, manualClients(c) + pkg)
            .putStringSet(HISTORY, trackedClients(c) + pkg).commit()
    @Synchronized fun removeClient(c: Context, pkg: String, password: String): Boolean =
        pkg in manualClients(c) && TaskPasswords.run(c, TaskScope(TaskKind.REDDIT_OFF), password) {
            prefs(c).edit().putStringSet(CLIENTS, manualClients(c) - pkg).commit()
        }

    private var refreshedAt: Long? = null
    private val inspected = mutableSetOf<String>()
    private val labels = PackageLabelCache()
    @Synchronized fun invalidate() { refreshedAt = null; inspected.clear(); labels.clear() }

    /** Specific Reddit link filters catch renamed clients; generic browsers are excluded. */
    @Synchronized fun discover(c: Context, installed: List<PackageInfo>? = null) {
        if (!enabled(c)) return
        val now = SystemClock.elapsedRealtime()
        if (refreshedAt?.let { now - it in 0..30_000 } == true) return
        val pm = c.packageManager
        val flags = PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.GET_RESOLVED_FILTER
        val handlers = listOf("https://www.reddit.com/r/AskReddit/", "https://reddit.com/r/AskReddit/").flatMap { url ->
            pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE), flags)
                .filter { info -> info.filter?.let { filter ->
                    (0 until filter.countDataAuthorities()).any { RedditRules.redditHost(filter.getDataAuthority(it).host) }
                } == true }.map { it.activityInfo.packageName }
        }.toSet() - BrowserPackages.discover(c)
        val packages = installed ?: pm.getInstalledPackages(0)
        labels.retain(packages.map { it.packageName }.toSet())
        val locales = c.resources.configuration.locales.toLanguageTags()
        val knownTargets = targets(c)
        val found = packages.mapNotNull { pkg ->
            val info = pkg.applicationInfo ?: return@mapNotNull null
            if (pkg.packageName == c.packageName) return@mapNotNull null
            val label = labels.get(PackageLabelKey(pkg.packageName, pkg.firstInstallTime, pkg.lastUpdateTime,
                info.labelRes, info.nonLocalizedLabel?.toString(), locales)) { info.loadLabel(pm).toString() }
            // Repackaged official clients often retain the original resource namespace.
            val original = if (pkg.packageName in inspected || pkg.packageName in knownTargets) false else try {
                pm.getResourcesForApplication(info).getIdentifier("app_name", "string", RedditRules.OFFICIAL) != 0
            } catch (_: Exception) { false }
            inspected.add(pkg.packageName)
            pkg.packageName.takeIf { RedditRules.likelyClient(it, label, it in handlers, original) }
        }.toSet()
        remember(c, found)
        refreshedAt = now
    }

    @Synchronized private fun remember(c: Context, found: Set<String>) {
        val previous = detected(c)
        if (!previous.containsAll(found)) check(prefs(c).edit().putStringSet(DETECTED, previous + found).commit())
    }
}
