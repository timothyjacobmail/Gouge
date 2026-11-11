package com.example.installlock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

internal data class BankingCategorySnapshot(val finance: Set<String> = emptySet(), val verified: Int = 0, val total: Int = 0)

/** Scan launchable apps on a worker and reuse the installation/version-bound Play category cache. */
internal object BankingCategoryDiscovery {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var snapshot = BankingCategorySnapshot()
    private var running = false
    private var lastScanElapsed: Long? = null
    private var seenRevision = -1L
    private var packageEpoch = 0L

    fun snapshot(): BankingCategorySnapshot = snapshot

    @Synchronized
    fun packageChanged(c: Context, app: String) {
        packageEpoch++
        snapshot = snapshot.copy(finance = snapshot.finance - app)
        lastScanElapsed = null
        if (BankingAppStore.detectFinance(c)) refresh(c, force = true)
    }

    @Synchronized
    fun refresh(context: Context, force: Boolean = false) {
        val elapsed = SystemClock.elapsedRealtime()
        val age = lastScanElapsed?.let { elapsed - it }
        if (running || (!force && age != null && age < 3000)) return
        val revision = PlayCategoryLookup.revision()
        if (!force && revision == seenRevision && age != null && age < 60_000) return
        val c = context.applicationContext
        val epoch = packageEpoch
        running = true
        lastScanElapsed = elapsed
        worker.execute {
            try {
                val pm = c.packageManager
                val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS)
                    .map { it.activityInfo.packageName }.toSet()
                val excluded = BankingAppStore.excluded(c)
                val apps = pm.getInstalledPackages(0).filter { it.packageName in launchers && it.packageName !in excluded }
                val categories = apps.associate { it.packageName to PlayCategoryLookup.category(c, it, background = true) }
                synchronized(this) {
                    if (epoch == packageEpoch) {
                        snapshot = BankingCategorySnapshot(BankingCategoryRules.financeApps(categories, excluded),
                            categories.values.count { it != null }, apps.size)
                        seenRevision = revision
                    }
                }
            } catch (e: RuntimeException) {
                Log.w("InstallLock", "Banking category discovery will retry", e)
            } finally {
                val changed = synchronized(this) { running = false; epoch != packageEpoch }
                if (changed && BankingAppStore.detectFinance(c)) refresh(c, force = true)
            }
        }
    }

}
