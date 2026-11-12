package com.example.installlock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView

/** Only package/resource metadata and counters persist. No screen text, URL or node is queued. */
internal class BetaMonitor(private val service: BrowserKeywordService, private val address: (AccessibilityNodeInfo, String) -> String?) {
    companion object {
        private var active: BetaMonitor? = null
        @Volatile var pickNext = false
        @Volatile var pickUntil = 0L
        @Volatile var pickedIds: List<String> = emptyList()
        fun changed() { Handler(Looper.getMainLooper()).post { active?.start() } }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val overlays = BetaOverlays(service)
    private var lastTime = SystemClock.elapsedRealtime()
    private var lastPkg = ""
    private var lastFeed: String? = null
    private var scroll = false
    private var scrollAt = 0L
    private val movement = ScrollMovement()
    private var lastCount = 0L
    private var lastReasons = emptyList<String>()
    private var running = false
    private var displayBlocked = false
    private var generation = 0
    private var lastWidget = 0L
    private val loop = object : Runnable { override fun run() { scan(); if (running) handler.postDelayed(this, 1000) } }
    fun start() {
        stop()
        active = this
        if (!BetaStore.enabled(service)) return
        running = true; lastTime = SystemClock.elapsedRealtime(); lastPkg = ""; lastFeed = null
        handler.post(loop)
    }
    fun stop(detach: Boolean = false) { val wasRunning=running; running = false; generation++; handler.removeCallbacks(loop); overlays.clear(); BetaGray.restore(service); if (wasRunning) BetaEnforcement.execute { BetaStore.flush(service) }; if (detach && active === this) active = null }
    fun event(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED || e.packageName?.toString() != lastPkg) return
        val source = e.source ?: return
        try {
            val vertical = movement.vertical(source.hashCode(), e.scrollX, e.scrollY, e.fromIndex, e.scrollDeltaX, e.scrollDeltaY)
            val now = SystemClock.elapsedRealtime()
            if (vertical && now - scrollAt >= 600) scroll = true
            if (vertical) scrollAt = now
        } finally { source.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun scan() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsed = (nowElapsed - lastTime).coerceIn(0, 2000)
        lastTime = nowElapsed
        if (!BetaStore.enabled(service) || BankingMode.active(service)) { stop(); return }
        if (!service.getSystemService(PowerManager::class.java).isInteractive || service.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            lastPkg = ""; lastFeed = null; scroll = false; overlays.clear(); BetaGray.restore(service); return
        }
        val root = service.windows.firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root ?: service.rootInActiveWindow
        if (root == null) { lastPkg = ""; lastFeed = null; overlays.clear(); BetaGray.restore(service); return }
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg in BetaSafety.essentials(service)) { lastPkg = ""; lastFeed = null; overlays.clear(); BetaGray.restore(service); return }
            val conf = BetaStore.config(service)
            val ids = linkedSetOf<String>(); val selected = linkedSetOf<String>(); val masks = mutableListOf<Rect>()
            val candidates = mutableListOf<Pair<String, Rect>>()
            var blocked: String? = null
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            if (pkg in ShortFormFeeds.nativePackages || pkg == Policy.CHROME || pkg == BrowserPackages.GOOGLE_APP ||
                conf.ui.any { it.pkg == pkg } || pickNext) queue.add(AccessibilityNodeInfo.obtain(root))
            var visited = 0
            try {
                while (queue.isNotEmpty() && visited++ < 400) {
                    val n = queue.removeFirst()
                    try {
                        if (n.isPassword) continue
                        if (n.isVisibleToUser) {
                            val full = n.viewIdResourceName.orEmpty(); val id = full.substringAfter(":id/", "")
                            if (full.isNotEmpty()) ids.add(full)
                            if (id.isNotEmpty() && candidates.size < 150) { val bounds = Rect(); n.getBoundsInScreen(bounds); if (!bounds.isEmpty) candidates.add(id to bounds) }
                            if (n.isSelected) {
                                n.text?.toString()?.lowercase()?.takeIf { it in ShortFormFeeds.navigationLabels }?.let { selected.add(it) }
                                n.contentDescription?.toString()?.lowercase()?.takeIf { it in ShortFormFeeds.navigationLabels }?.let { selected.add(it) }
                                if (id in ShortFormFeeds.reelsTabs) selected.add("reels")
                                if (id in ShortFormFeeds.homeTabs) selected.add("home")
                            }
                            val rule = conf.ui.firstOrNull { it.pkg == pkg && it.resource == full && (it.text.isEmpty() || n.text?.toString() == it.text) }
                            val hideFeed = conf.on("hideFeeds") && (pkg == "com.instagram.android" && id in ShortFormFeeds.homeTabs ||
                                pkg == "com.google.android.youtube" && id in setOf("home_tab", "pivot_home") || pkg == "com.reddit.frontpage" && id in setOf("home_tab", "popular_tab"))
                            if (rule != null && !rule.hide) blocked = "Custom beta UI rule"
                            if (rule?.hide == true || hideFeed) {
                                val rect = Rect(); n.getBoundsInScreen(rect)
                                if (!rect.isEmpty && masks.size < 20) masks.add(rect)
                            }
                        }
                        if (visited + queue.size < 400) for (i in 0 until minOf(n.childCount, 60)) n.getChild(i)?.let { queue.add(it) }
                    } finally { n.recycle() }
                }
            } finally { queue.forEach { it.recycle() } }
            if (pickNext && nowElapsed <= pickUntil && ids.isNotEmpty()) { pickedIds = ids.take(200); pickNext = false }
            if (nowElapsed > pickUntil) pickNext = false
            val native = ShortFormFeeds.nativeFeed(pkg, ids.map { it.substringAfter(":id/") }.toSet(), selected)
            val shortIds = ids.map { it.substringAfter(":id/") }.toSet()
            candidates.filter { (id, _) ->
                pkg == "com.google.android.youtube" && (conf.on("hideYoutubeHome") && id == "results" && "youtube_logo" in shortIds && "search_query" !in shortIds || conf.on("hideYoutubeSuggestions") && id == "watch_list" || conf.on("hideShortTabs") && id == "pivot_shorts") ||
                pkg == "com.instagram.android" && (conf.on("hideInstagramHome") && native == "Instagram home feed" && id.startsWith("row_feed_") || conf.on("hideInstagramExplore") && id == "grid_card_layout_container" || conf.on("hideShortTabs") && id in ShortFormFeeds.reelsTabs)
            }.take(20-masks.size).forEach { masks.add(it.second) }
            val url = if (pkg == Policy.CHROME || pkg == BrowserPackages.GOOGLE_APP) address(root, pkg) else null
            val feed = native ?: ShortFormFeeds.website(url)
            val short = feed?.let { it.contains("Shorts") || it.contains("Reels") } == true
            val launch = pkg != lastPkg
            val count = short && (feed != lastFeed || launch || scroll && nowElapsed - lastCount >= 900)
            if (count) lastCount = nowElapsed
            scroll = false
            val delta = if (!launch && displayBlocked) 0 else elapsed
            lastPkg = pkg; lastFeed = feed
            if (conf.on("shortBlock") && short) blocked = "Shorts & Reels are blocked by beta settings"
            if (conf.on("proxies") && url != null && BetaPaths.proxy(url)) blocked = "Beta proxy/embedded-page rule"
            val now = System.currentTimeMillis()
            if (url != null) conf.profiles.firstOrNull { p -> p.enabled && (p.scheduled(now) || p.focus(now)) &&
                !(p.overrideUntil > now && !p.committed(now)) && p.sites.any { BetaPaths.matches(it, url) } }?.let { blocked = "${it.name}: website/path rule" }
            val reason = blocked
            val gen = generation
            BetaEnforcement.execute {
                BetaStore.record(service, pkg, delta, short, count, launch, now)
                if (now-lastWidget >= 60_000) { lastWidget=now; BetaStatsWidget.refresh(service) }
                val reasons = BetaStore.reasons(service, pkg, now)
                val shortReason = if (short) conf.profiles.filter { pkg in it.apps }.firstNotNullOfOrNull { p -> BetaRules.shortReason(p, now, BetaStore.usage(service, p, "*", now)) } else null
                if (reasons != lastReasons) { lastReasons = reasons; BetaEnforcement.request(service) }
                val counters = if (short) BetaStore.usage(service, BetaStore.deviceProfile(pkg), "*", now).count else 0
                handler.post {
                    if (!running || gen != generation || lastPkg != pkg) return@post
                    val fullReason = reason ?: reasons.firstOrNull() ?: shortReason
                    displayBlocked = fullReason != null
                    overlays.show(fullReason, masks, if (short && conf.on("counter")) "Beta · $counters shorts today" else null)
                    BetaGray.update(service, conf.on("grayscale") && conf.profiles.any { it.gray && pkg in it.apps && it.scheduled(now) })
                }
            }
        } catch (_: RuntimeException) { overlays.clear(); BetaGray.restore(service) }
        finally { root.recycle() }
    }
}

internal class BetaOverlays(private val service: AccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val views = mutableListOf<View>()
    private var state = ""
    fun clear() { views.forEach { try { manager.removeViewImmediate(it) } catch (_: RuntimeException) {} }; views.clear(); state = "" }
    private fun add(v: View, w: Int, h: Int, x: Int, y: Int, touch: Boolean) {
        val p = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.LEFT; p.x = x; p.y = y
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        try { manager.addView(v, p); views.add(v) } catch (_: RuntimeException) {}
    }
    fun show(reason: String?, masks: List<Rect>, counter: String?) {
        val key = "$reason|$masks|$counter"
        if (key == state) return
        clear(); state = key
        if (reason != null) {
            val theme = android.view.ContextThemeWrapper(service, R.style.Theme_Gouge)
            val panel = LinearLayout(theme).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(36,36,36,36)
                setBackgroundColor(UiStyle.color(theme, com.google.android.material.R.attr.colorSurface))
                addView(UiStyle.label(theme, "Gouge · beta", 24f)); addView(UiStyle.label(theme, reason))
                addView(UiStyle.button(theme, "Go Home") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) })
                addView(UiStyle.button(theme, "Back") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) })
            }
            add(panel, -1, -1, 0, 0, true)
        } else masks.forEach { rect -> add(View(service).apply { setBackgroundColor(Color.rgb(28, 28, 28)) }, rect.width(), rect.height(), rect.left, rect.top, true) }
        if (counter != null && reason == null) add(TextView(service).apply {
            text = counter; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(35,35,35)); textSize = 12f; setPadding(12,6,12,6)
        }, -2, -2, 12, 90, false)
    }
}

/** Persist the original state before touching secure settings, including across process death. */
internal object BetaGray {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"
    fun update(c: android.content.Context, gray: Boolean) {
        if (!gray) { restore(c); return }
        val p = c.getSharedPreferences("gouge_beta_gray", 0)
        if (p.getBoolean("owned", false)) return
        try {
            val oldEnabled = Settings.Secure.getInt(c.contentResolver, ENABLED, 0)
            val oldMode = Settings.Secure.getInt(c.contentResolver, MODE, -1)
            if (!p.edit().putBoolean("owned", true).putInt("enabled", oldEnabled).putInt("mode", oldMode).commit()) return
            if (!Settings.Secure.putInt(c.contentResolver, MODE, 0) || !Settings.Secure.putInt(c.contentResolver, ENABLED, 1)) restore(c)
        } catch (_: RuntimeException) { restore(c) }
    }
    fun restore(c: android.content.Context) {
        val p = c.getSharedPreferences("gouge_beta_gray", 0)
        if (!p.getBoolean("owned", false)) return
        try {
            // Do not overwrite a later user/system colour-mode choice.
            if (Settings.Secure.getInt(c.contentResolver, MODE, -1) == 0) {
                Settings.Secure.putInt(c.contentResolver, ENABLED, p.getInt("enabled", 0))
                Settings.Secure.putInt(c.contentResolver, MODE, p.getInt("mode", -1))
            }
            p.edit().clear().apply()
        } catch (_: RuntimeException) { /* Retain recovery state until settings access returns. */ }
    }
}
