package com.example.installlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.delayedsecrect.SecretClipboard

class LockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val accessibilityObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            BackgroundPowerProtection.ensureEnabled(this@LockService)
            Policy.sweep(this@LockService)
            BankingMode.reconcile(this@LockService)
        }
    }
    private var observingAccessibility = false
    private var scheduledBoundary = 0L
    private val boundaryAlarm = AlarmManager.OnAlarmListener {
        scheduledBoundary = 0L
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            scheduledBoundary = 0L
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun scheduleBoundary(): Long {
        val now = System.currentTimeMillis()
        val cooldown = WatchdogStore.remaining(this)
        val focus = FocusLockStore.remaining(this)
        val bankingWait = BankingAppStore.waits(this).values.minOrNull() ?: Long.MAX_VALUE
        val taskDelay = ScheduledTasks.nextDelay(this)
        val next = minOf(CurfewRules.nextBoundary(now, CurfewStore.zone(), CurfewStore.appealedNight(this)),
            if (cooldown > 0) now + cooldown else Long.MAX_VALUE,
            if (focus > 0) now + focus else Long.MAX_VALUE,
            taskDelay?.let { now + it.coerceAtLeast(1000L) } ?: Long.MAX_VALUE, bankingWait)
        if (scheduledBoundary != next) {
            val alarms = getSystemService(AlarmManager::class.java)
            alarms.cancel(boundaryAlarm)
            // Listener alarms need no exact-alarm permission. Screen-on reconciles immediately
            // after Doze, and service/boot restarts recreate this process-owned alarm.
            alarms.setExact(AlarmManager.RTC_WAKEUP, next, "Gouge curfew", boundaryAlarm, handler)
            scheduledBoundary = next
        }
        return (next - now).coerceAtLeast(100L)
    }

    private val tick = object : Runnable {
        override fun run() {
            var next = 5000L
            try {
                EnforcementRecovery.arm(this@LockService)
                BackgroundPowerProtection.ensureEnabled(this@LockService)
                // Enforcement takes priority over optional task/clipboard maintenance.
                TemporaryLinks.reconcile(this@LockService)
                Policy.sweep(this@LockService)
                BankingMode.reconcile(this@LockService)
                if (BankingAppStore.detectFinance(this@LockService)) BankingCategoryDiscovery.refresh(this@LockService)
                SecretClipboard.reconcile()
                Store.applyDueKeywordRemovals(this@LockService)
                ScheduledTasks.reconcileAsync(this@LockService)
                RetiredFeatureCleanup.schedule(this@LockService)
                next = minOf(next, scheduleBoundary(),
                    Store.timingConfig(this@LockService).milliseconds(TimingOption.POLICY_INTERVAL))
            } catch (e: RuntimeException) {
                Log.e("InstallLock", "Protection check failed; retrying", e)
            } finally {
                // One failed provider, preference read, or policy call cannot stop the check loop.
                handler.postDelayed(this, next)
            }
        }
    }

    // Fires instantly when an app is installed so it's locked before you can open it
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.data?.schemeSpecificPart == Policy.CHROME) Policy.syncChrome(c, force = true)
            try { Policy.sweep(c) }
            catch (e: RuntimeException) { Log.e("InstallLock", "Install check failed; scheduled sweep will retry", e) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("enforcement_v2", "Gouge protection", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "enforcement_v2")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.lock_notification_title))
            .setContentText(getString(R.string.lock_notification_text))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_LOCK_REASONS)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        EnforcementRecovery.arm(this, force = true)
        SecretClipboard.initialize(this)
        registerReceiver(
            pkgReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        )
        registerReceiver(wakeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessibilityObserver)
            observingAccessibility = true
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, accessibilityObserver)
        } catch (_: SecurityException) {
            // Service disconnect callbacks and the repeating sweep still enforce the guard.
        }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Includes opening Gouge, boot, and sticky restarts after process death.
        EnforcementRecovery.arm(this, force = true)
        try { Policy.apply(this) }
        catch (e: RuntimeException) { Log.e("InstallLock", "Could not refresh device-owner protection", e) }
        try { Policy.syncChrome(this, force = true); ScheduledTasks.reschedule(this) }
        catch (e: RuntimeException) { Log.e("InstallLock", "Startup reconciliation will retry", e) }
        // Re-schedule immediately when settings change; keep exactly one repeating tick.
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        EnforcementRecovery.arm(this, soon = true)
        handler.removeCallbacks(tick)
        unregisterReceiver(pkgReceiver)
        unregisterReceiver(wakeReceiver)
        getSystemService(AlarmManager::class.java).cancel(boundaryAlarm)
        if (observingAccessibility) contentResolver.unregisterContentObserver(accessibilityObserver)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        EnforcementRecovery.arm(this, soon = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(c: Context) {
            c.startForegroundService(Intent(c, LockService::class.java))
        }
    }
}
