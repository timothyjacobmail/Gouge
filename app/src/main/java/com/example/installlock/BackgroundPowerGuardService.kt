package com.example.installlock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast

/** Reads system power/settings content; Play Store fallback uses foreground package identity only. */
class BackgroundPowerGuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var shield: View? = null
    private var lastNotice = 0L
    private var lastPlayStoreRedirect = 0L
    private val check = Runnable { inspect() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (BankingMode.active(this)) { disableSelf(); return }
        connected = true
        EnforcementRecovery.recover(this)
        handler.post(check)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        guardPlayStore(event?.takeIf { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }?.packageName?.toString())
        handler.removeCallbacks(check)
        handler.post(check)
    }

    private fun powerWindow(): AccessibilityNodeInfo? {
        // Exclude our accessibility overlay; never traverse another app's screen contents.
        for (window in windows.sortedByDescending { it.layer }) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive) continue
            val root = window.root ?: continue
            return root.takeIf { it.packageName?.toString() in BackgroundPowerGuardRules.settingsPackages }
        }
        return null
    }

    private fun snapshot(root: AccessibilityNodeInfo): PowerSettingScreen {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val nodes = mutableListOf<PowerSettingNode>()
        pending.add(root)
        while (pending.isNotEmpty() && nodes.size < 400) {
            val node = pending.removeFirst()
            nodes += PowerSettingNode(node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty())
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::addLast)
        }
        return PowerSettingScreen(root.packageName.toString(), nodes)
    }

    private fun inspect() {
        if (BankingMode.active(this)) { onInterrupt(); disableSelf(); return }
        try {
            guardPlayStore()
            val root = powerWindow()
            if (!Policy.isOwner(this) || root == null ||
                !BackgroundPowerGuardRules.protects(snapshot(root), getString(R.string.app_name))) {
                removeShield()
                return
            }
            showShield()
            Log.i("InstallLockPowerGuard", "Blocked Gouge background-power editor")
            // Restore Allow if a tap raced window delivery, then leave the protected editor.
            root.findAccessibilityNodeInfosByViewId("com.iqoo.powersaving:id/all_opt")
                .firstOrNull()?.takeIf { !it.isChecked }?.let {
                    val restored = it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i("InstallLockPowerGuard", "Restore Allow background power usage: $restored")
                }
            performGlobalAction(GLOBAL_ACTION_BACK)
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotice > 2000) {
                Toast.makeText(this, "Gouge's background power usage is locked to Allow.", Toast.LENGTH_SHORT).show()
                lastNotice = now
            }
            handler.postDelayed(check, 100)
        } catch (e: RuntimeException) {
            Log.e("InstallLock", "Could not inspect background-power settings", e)
            removeShield()
        }
    }

    private fun guardPlayStore(windowPackage: String? = null) {
        if (BankingMode.active(this) || !PlayStoreDelayStore.enabled(this) || BrowserKeywordService.isReady(this) ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayStoreRedirect < 1000) return
        try {
            val pkg = if (windowPackage == PlayStoreDelayStore.PACKAGE) windowPackage else {
                val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                val root = (apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive })?.root
                    ?: rootInActiveWindow
                root?.let { try { it.packageName?.toString() } finally { @Suppress("DEPRECATION") it.recycle() } }
            }
            if (pkg != PlayStoreDelayStore.PACKAGE) return
            lastPlayStoreRedirect = now
            PlayStoreDelayStore.endSession()
            Log.i("InstallLockPlayStore", "Background guard blocking Play Store while browser monitor is disconnected")
            startActivity(PlayStoreDelayActivity.openIntent(this))
        } catch (error: RuntimeException) {
            Log.w("InstallLockPlayStore", "Play Store fallback will retry on the next window event", error)
        }
    }

    private fun showShield() {
        if (shield != null) return
        val view = TextView(this).apply {
            text = "Gouge background protection is locked."
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(23, 29, 27))
            setBackgroundColor(Color.rgb(245, 251, 248))
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnTouchListener { _, _ -> true }
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE))
            shield = view
        } catch (e: RuntimeException) { Log.w("InstallLock", "Power guard will use immediate Back navigation", e) }
    }

    private fun removeShield() {
        shield?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) }
            catch (_: RuntimeException) { }
        }
        shield = null
    }

    override fun onInterrupt() {
        handler.removeCallbacks(check)
        removeShield()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        connected = false
        onInterrupt()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        connected = false
        onInterrupt()
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile internal var connected = false
        @Volatile private var instance: BackgroundPowerGuardService? = null
        internal fun enforcePlayStoreFallback() {
            instance?.let { service -> service.handler.post { service.guardPlayStore() } }
        }
        internal fun pauseForBanking() {
            instance?.let { service -> service.handler.post {
                if (BankingMode.active(service)) { service.onInterrupt(); service.disableSelf() }
            } }
        }
    }
}
