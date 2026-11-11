package com.example.installlock

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobParameters
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Build
import android.os.UserManager
import android.util.Log

/** Recovery must live in Android's scheduler, outside the process it is protecting. */
internal object EnforcementRecovery {
    const val ACTION = "com.example.installlock.RECOVER_ENFORCEMENT"
    const val JOB_ID = 8101
    private const val REQUEST_CODE = 8101
    private const val RECOVERY_MS = 60_000L
    private const val RENEW_MS = 30_000L
    private var lastArmed: Long? = null

    fun unlocked(c: Context) = c.getSystemService(UserManager::class.java).isUserUnlocked

    @Synchronized
    fun arm(c: Context, soon: Boolean = false, force: Boolean = false) {
        if (!Policy.isOwner(c)) return
        val now = SystemClock.elapsedRealtime()
        if (!soon && !force && lastArmed?.let { now - it in 0 until RENEW_MS } == true) return
        val intent = PendingIntent.getBroadcast(c, REQUEST_CODE,
            Intent(c, EnforcementRecoveryReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = c.getSystemService(AlarmManager::class.java)
        val at = now + if (soon) 1000L else RECOVERY_MS
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
            lastArmed = now
        } catch (e: SecurityException) {
            // Revoking exact-alarm access must not remove all recovery paths.
            try {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
                lastArmed = now
            } catch (fallback: RuntimeException) { Log.e("InstallLock", "Recovery alarm unavailable", fallback) }
        } catch (e: RuntimeException) { Log.e("InstallLock", "Could not arm enforcement recovery", e) }

        // Persisted jobs survive reboot and provide a separate fallback if an OEM drops an alarm.
        try {
            val jobs = c.getSystemService(JobScheduler::class.java)
            if (jobs.getPendingJob(JOB_ID) == null) {
                val result = jobs.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, EnforcementRecoveryJob::class.java))
                    .setPeriodic(15 * 60_000L).setPersisted(true).build())
                if (result != JobScheduler.RESULT_SUCCESS) Log.e("InstallLock", "Recovery job scheduling rejected")
            }
        } catch (e: RuntimeException) { Log.e("InstallLock", "Could not persist enforcement recovery", e) }
    }

    fun recover(c: Context): Boolean {
        if (!Policy.isOwner(c)) return false
        // Arm first: even a rejected service start leaves another attempt in the system.
        arm(c, force = true)
        if (!unlocked(c)) return false // Policy and passwords remain in credential-protected storage.
        return try {
            LockService.start(c)
            true
        } catch (e: RuntimeException) {
            Log.e("InstallLock", "Could not restart policy enforcement; recovery remains scheduled", e)
            false
        }
    }
}

class EnforcementRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == EnforcementRecovery.ACTION) EnforcementRecovery.recover(context)
    }
}

class EnforcementRecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        EnforcementRecovery.recover(this)
        return false
    }
    override fun onStopJob(params: JobParameters) = true
}

/** A manifest receiver can wake an exempt device owner when the service's receiver is gone.
 * Android may restrict package broadcasts on other deployments; the alarm and job remain required. */
class InstalledPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_FULLY_REMOVED) && intent.data?.scheme == "package") {
            if (EnforcementRecovery.unlocked(context)) intent.data?.schemeSpecificPart?.let {
                RedditStore.invalidate()
                BankingCategoryDiscovery.packageChanged(context, it)
            }
            EnforcementRecovery.recover(context)
        }
    }
}
