package com.example.installlock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo

/** Banking list membership and its fixed wait are independent of manual accessibility controls. */
internal object BankingAppStore {
    // Preserve the manual selections and Finance preference from the previous UI.
    private fun settings(c: Context) = c.getSharedPreferences("automatic_banking", Context.MODE_PRIVATE)
    private fun prefs(c: Context) = c.getSharedPreferences("banking_list", Context.MODE_PRIVATE)
    fun manualBanks(c: Context): Set<String> = settings(c).getStringSet("banks", emptySet()).orEmpty().toSet()
    fun detectFinance(c: Context) = settings(c).getBoolean("detectFinance", true)
    fun targets(c: Context): Set<String> = prefs(c).getStringSet("targets", emptySet()).orEmpty().toSet()
    fun managed(c: Context): Set<String> = prefs(c).getStringSet("managed", emptySet()).orEmpty().toSet()
    private fun clock(c: Context) = WatchdogStore.clock(c).let { BankingListClock(it.wall, it.elapsed, it.boot) }

    fun excluded(c: Context): Set<String> = WatchdogStore.monitoredPackages(c) + setOfNotNull(c.packageName,
        "com.android.settings", c.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName)

    @Synchronized
    fun reconcile(c: Context, packages: List<PackageInfo>) {
        val p = prefs(c)
        val now = clock(c)
        val excluded = excluded(c)
        val installed = packages.associateBy { it.packageName }
        val desired = BankingCategoryRules.targets(manualBanks(c), BankingCategoryDiscovery.snapshot().finance,
            detectFinance(c)).intersect(installed.keys) - excluded
        val before = targets(c)
        val tracked = managed(c)
        val edit = p.edit()
        var changed = false
        // Preserve entries selected or verified before this upgrade. New UI additions are saved
        // only after this one-time migration, and new category results receive the full wait.
        val legacy = if (!p.getBoolean("initialized", false)) {
            val cached = c.getSharedPreferences("play_categories", Context.MODE_PRIVATE)
            (manualBanks(c) + if (detectFinance(c)) packages.filter { pkg ->
                val record = PlayCategoryRecord.decode(cached.getString(pkg.packageName, null))
                record != null && record.installedAt == pkg.firstInstallTime &&
                    record.category == PlayInstallCategory.FINANCE.storeId
            }.map { it.packageName } else emptyList()).intersect(installed.keys) - excluded
        } else emptySet()
        for (app in desired + legacy) {
            val pkg = installed.getValue(app)
            val old = BankingListEntry.decode(p.getString("entry.$app", null))
            val next = BankingListRules.enroll(old, pkg.firstInstallTime, now, legacy = app in legacy)
            if (old != next) { edit.putString("entry.$app", next.encode()); changed = true }
        }
        val afterManaged = tracked + desired + legacy
        if (!p.getBoolean("initialized", false) || before != desired || tracked != afterManaged) {
            edit.putBoolean("initialized", true).putStringSet("targets", desired).putStringSet("managed", afterManaged)
            changed = true
        }
        if (changed) check(edit.commit())
    }

    @Synchronized
    fun waits(c: Context): Map<String, Long> {
        val p = prefs(c)
        val now = clock(c)
        val edit = p.edit()
        var changed = false
        val pending = mutableMapOf<String, Long>()
        for (app in managed(c)) {
            val old = BankingListEntry.decode(p.getString("entry.$app", null)) ?: continue
            val next = BankingListRules.reconcile(old, now)
            if (next != old) { edit.putString("entry.$app", next.encode()); changed = true }
            val remaining = BankingListRules.remaining(next, now)
            if (remaining > 0) pending[app] = now.wall + remaining
        }
        if (changed) check(edit.commit())
        return pending
    }

    fun configure(c: Context, selected: Set<String>, financeDetection: Boolean): String? {
        if (!Policy.isOwner(c)) return "Device owner setup is required."
        val installed = c.packageManager.getInstalledPackages(0)
        reconcile(c, installed) // Seed only selections that existed before this save.
        val safe = selected - excluded(c)
        if (!settings(c).edit().putStringSet("banks", safe).putBoolean("detectFinance", financeDetection)
                .remove("enabled").commit()) return "Could not save the banking list."
        reconcile(c, installed)
        if (financeDetection) BankingCategoryDiscovery.refresh(c, force = true)
        Policy.sweep(c)
        LockService.start(c)
        return null
    }
}
