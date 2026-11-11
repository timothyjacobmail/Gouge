package com.example.installlock

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

/** A real accessibility shutdown for banking, with device-owner app locks still enforced. */
internal object BankingMode {
    private const val NOTIFICATION = 8102
    private var notified = false
    private fun prefs(c: Context) = c.getSharedPreferences("banking_mode", Context.MODE_PRIVATE)
    fun active(c: Context) = prefs(c).getBoolean("active", false)
    private fun services(c: Context) = Settings.Secure.getString(c.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()

    fun servicesStillEnabled(c: Context): Boolean =
        BankingAccessibilityRules.entries(services(c)).any { BankingAccessibilityRules.owned(it, c.packageName) } ||
            c.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == c.packageName }

    @Synchronized
    fun start(c: Context): String? {
        if (!Policy.isOwner(c)) return "Device owner setup is required to keep app locks enforced during banking."
        if (active(c)) { reconcile(c); return null }
        val saved = BankingAccessibilityRules.entries(services(c))
            .filter { BankingAccessibilityRules.owned(it, c.packageName) }.toSet()
        if (!prefs(c).edit().putStringSet("savedServices", saved).putBoolean("active", true)
                .remove("automatic").remove("automaticApps").commit())
            return "Could not save banking mode. Accessibility has not been changed."
        // Mark monitoring unavailable and confirm Android has actually suspended every monitored
        // target BEFORE stopping either service. Reject a partial suspension rather than open a gap.
        val locked = try {
            Policy.lockStatuses(c).none { it.reasons.categoryServiceMissing && it.suspended != true }
        } catch (e: RuntimeException) {
            Log.e("InstallLock", "Could not prepare banking app locks", e)
            false
        }
        if (!locked) {
            check(prefs(c).edit().putBoolean("active", false).commit())
            Policy.sweep(c)
            return "Android could not lock all monitored apps. Banking mode was not started. Tap Refresh and retry."
        }
        reconcile(c)
        LockService.start(c)
        return null
    }

    /** Preserve the user's manual choice through restarts; only finish() restores accessibility. */
    fun reconcile(c: Context) {
        if (!active(c)) return
        if (BackgroundPowerProtection.canMaintainGuard(c)) try {
            val before = services(c)
            val after = BankingAccessibilityRules.pause(before, c.packageName)
            if (before != after) {
                check(Settings.Secure.putString(c.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, after))
                // Banks may inspect the global flag as well as the service list. Clear it only
                // when no services remain, so another app's accessibility is never switched off.
                if (after.isEmpty()) check(Settings.Secure.putInt(c.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0))
            }
        } catch (e: RuntimeException) { Log.w("InstallLock", "Use service shutdown for banking", e) }
        BrowserKeywordService.pauseForBanking()
        BackgroundPowerGuardService.pauseForBanking()
        if (!notified) try {
            val manager = c.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("banking", "Banking mode", NotificationManager.IMPORTANCE_LOW))
            val intent = Intent(c, MainActivity::class.java).setAction(MainActivity.ACTION_BANKING_MODE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            manager.notify(NOTIFICATION, Notification.Builder(c, "banking")
                .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Banking mode is active")
                .setContentText("Accessibility is off. Tap to turn it back on manually.")
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(c, NOTIFICATION, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            notified = true
        } catch (e: RuntimeException) { Log.w("InstallLock", "Banking reminder unavailable", e) }
    }

    @Synchronized
    fun finish(c: Context): String {
        if (!active(c)) return "Banking mode is already off."
        val saved = prefs(c).getStringSet("savedServices", emptySet()).orEmpty().toSet()
        if (!prefs(c).edit().putBoolean("active", false).remove("automatic").remove("automaticApps")
                .remove("finishedWall").commit()) return "Could not finish banking mode. Try again."
        c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        notified = false
        var restored = saved.isEmpty()
        if (BackgroundPowerProtection.canMaintainGuard(c)) try {
            val after = BankingAccessibilityRules.restore(services(c), saved, c.packageName)
            restored = Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, after)
            if (restored && after.isNotEmpty()) restored = Settings.Secure.putInt(c.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: RuntimeException) { Log.w("InstallLock", "Accessibility needs manual restoration", e) }
        // Only the services enabled before banking are restored. Other apps' changes are preserved.
        LockService.start(c)
        Policy.sweep(c)
        return if (restored) "Banking mode finished. Previously enabled services are reconnecting."
            else "Banking mode finished. Re-enable Install Lock's services in Android accessibility settings."
    }
}
