package com.example.installlock

import android.app.KeyguardManager
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo

/** Watches foreground package identity only, never Play Store content. */
internal class PlayStoreMonitor(private val service: BrowserKeywordService, private val handler: Handler) {
    private var entered = false
    private var launchUntil = 0L
    private var redirectAt = 0L
    private var running = false
    private val foreground = PlayStoreForegroundState()
    private var samples = 0L
    private var showingKeyguard = false
    fun debugStatus() = "running=$running samples=$samples keyguard=$showingKeyguard foreground=${foreground.previous} session=${PlayStoreDelayStore.sessionOpen} entered=$entered"
    private val keyboardPackage by lazy {
        Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { check() } finally { if (running) handler.postDelayed(this, 250) }
        }
    }
    fun start() { stop(); running = true; handler.post(tick) }
    fun stop() {
        running = false; handler.removeCallbacks(tick); PlayStoreDelayStore.endSession()
        entered = false; launchUntil = 0; redirectAt = 0; foreground.reset()
    }

    fun check(windowPackage: String? = null, focusChanged: Boolean = false) {
        try {
            checkForeground(windowPackage, focusChanged)
        } catch (e: RuntimeException) {
            // Windows can disappear during navigation. Retry on the next event/poll.
            PlayStoreDelayStore.endSession()
            entered = false
            launchUntil = 0
            foreground.reset()
            Log.w("InstallLockPlayStore", "Could not inspect foreground window; retrying", e)
        }
    }

    private fun checkForeground(windowPackage: String?, focusChanged: Boolean) {
        samples++
        if (!running) return
        if (!PlayStoreDelayStore.enabled(service)) {
            PlayStoreDelayStore.endSession(); entered = false; launchUntil = 0; foreground.reset(); return
        }
        // Vivo can retain deviceLocked=true in TrustManager after keyguard is dismissed.
        // Pause only while the lock screen is actually showing.
        showingKeyguard = service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (showingKeyguard) {
            PlayStoreDelayStore.endSession(); entered = false; launchUntil = 0; foreground.reset(); return
        }
        // A real window-state event is sufficient; avoid synchronous window-tree IPC on
        // this immediate path. Focus events and the poll provide a root fallback.
        val root = if (PlayStoreForeground.application(windowPackage, keyboardPackage) != null) null else {
            val windows = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            (windows.firstOrNull { it.isFocused } ?: windows.firstOrNull { it.isActive })?.root
                ?: service.rootInActiveWindow
        }
        val rootPackage = root?.let {
            try { it.packageName?.toString() } finally { @Suppress("DEPRECATION") it.recycle() }
        }
        // Some Vivo system overlays hide every application window from getWindows().
        // Window-state events still identify the foreground app in that situation.
        val now = SystemClock.elapsedRealtime()
        // Vivo may keep the old app root even after Home is fully visible. Once we
        // receive window transitions, their package identity is authoritative.
        val pkg = foreground.resolve(windowPackage, rootPackage, keyboardPackage, focusChanged) ?: return
        if (PlayStoreDelayStore.sessionOpen) {
            if (pkg == PlayStoreDelayStore.PACKAGE) {
                if (!entered) Log.i("InstallLockPlayStore", "Play Store session entered")
                entered = true; return
            }
            if (!entered) {
                if (launchUntil == 0L) launchUntil = now + 10_000
                if (now < launchUntil) return
            }
            if (pkg == "com.android.systemui") return
            PlayStoreDelayStore.endSession(); entered = false; launchUntil = 0
            Log.i("InstallLockPlayStore", "Play Store session ended")
        }
        if (pkg != PlayStoreDelayStore.PACKAGE || now - redirectAt < 1000) return
        redirectAt = now
        Log.i("InstallLockPlayStore", "Opening two-minute countdown")
        // Starting Home as well races the countdown activity and can cover it on Vivo.
        service.startActivity(PlayStoreDelayActivity.openIntent(service))
    }
}
