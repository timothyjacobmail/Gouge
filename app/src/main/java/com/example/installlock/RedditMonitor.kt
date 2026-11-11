package com.example.installlock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** An opaque, touch-blocking cover stays up until the current native screen is verified. */
@Suppress("DEPRECATION")
internal class RedditMonitor(private val service: AccessibilityService, private val handler: Handler) {
    private var running = false
    private var cover: View? = null
    private var shown: Triple<String, String, Set<String>>? = null
    private val windowClients = mutableMapOf<Int, String>()
    private val manager = service.getSystemService(WindowManager::class.java)
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { sample() } catch (_: RuntimeException) {
                // Invalidated trees are unknown, never a cached allow decision.
                (shown?.first ?: windowClients.values.firstOrNull())?.let { block(it, "Waiting for a verifiable Reddit screen.") }
            }
            if (running) handler.postDelayed(this, 200)
        }
    }

    fun start() { if (!running) { running = true; handler.post(tick) } }
    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        windowClients.clear()
        dismiss()
    }
    fun changed() {
        if (!running) return
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    fun onEvent(event: AccessibilityEvent) {
        if (!running || !RedditStore.enabled(service)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in RedditStore.targets(service)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                block(pkg, "Checking this Reddit screen…")
            changed()
        }
    }

    private fun sample() {
        if (!RedditStore.enabled(service) || BankingMode.active(service) ||
            service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            windowClients.clear(); dismiss(); return
        }
        val targets = RedditStore.targets(service)
        val windows = service.windows
        try {
        val applicationWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val visibleIds = applicationWindows.map { it.id }.toSet()
        windowClients.keys.retainAll(visibleIds)
        var restriction: Pair<String, String>? = null
        var inspectedApplication = false
        for (window in applicationWindows) {
            val root = window.root
            if (root == null) {
                windowClients[window.id]?.let { restriction = it to "This Reddit window cannot be inspected." }
                continue
            }
            try {
                inspectedApplication = true
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg !in targets) { windowClients.remove(window.id); continue }
                windowClients[window.id] = pkg
                val decision = inspect(root, pkg)
                if (!decision.allowed) restriction = pkg to decision.reason
            } catch (_: RuntimeException) {
                windowClients[window.id]?.let { restriction = it to "Waiting for a verifiable Reddit screen." }
            } finally { root.recycle() }
        }
        if (!inspectedApplication && applicationWindows.isEmpty()) {
            val root = service.rootInActiveWindow
            if (root != null) try {
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg in targets) {
                    val decision = inspect(root, pkg)
                    if (!decision.allowed) restriction = pkg to decision.reason
                } else if (pkg == service.packageName && cover != null) return
            } finally { root.recycle() }
            else { if (cover != null) return }
        }
        restriction?.let { block(it.first, it.second) } ?: dismiss()
        } finally { windows.forEach { it.recycle() } }
    }

    internal fun inspect(root: AccessibilityNodeInfo, pkg: String): RedditDecision {
        val nodes = mutableListOf<RedditUiNode>()
        var visited = 0
        var incomplete = false
        fun walk(node: AccessibilityNodeInfo, depth: Int, inFeed: Boolean = false, inHeader: Boolean = false,
                 content: Boolean = false, communityHeader: Boolean = false, visibleCommunityHeader: Boolean = false) {
            if (++visited > 2500 || depth > 55) { incomplete = true; return }
            val visible = node.isVisibleToUser
            val owner = node.packageName?.toString()
            if (owner != null && owner != pkg) { incomplete = true; return }
            val type = node.className?.toString().orEmpty()
            if (type == "android.webkit.WebView" || type.endsWith("GeckoView")) { if (visible) incomplete = true; return }
            val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase(Locale.ROOT).orEmpty()
            val body = content || RedditRules.contentContainer(id)
            val community = !body && (communityHeader || RedditRules.communityHeaderContainer(id))
            val visibleHeader = !body && (visibleCommunityHeader || visible && RedditRules.communityHeaderContainer(id))
            val header = !body && (inHeader || community || RedditRules.headerContainer(id))
            val editable = node.isEditable || node.isPassword
            // Search and composer overlays must not reuse the allowed page behind them.
            if (visible && (editable || id.contains("community_drawer") || id.contains("search_results"))) incomplete = true
            if (RedditRules.mayReadLabel(id, visible, visibleHeader)) nodes.add(RedditUiNode(id, if (editable) "" else node.text?.toString().orEmpty(),
                if (editable) "" else node.contentDescription?.toString().orEmpty(), header, inFeed, body, editable, community))
            if (editable) return
            val feed = inFeed || node.isScrollable && !type.contains("Horizontal")
            for (i in 0 until node.childCount) {
                if (visited >= 2500) { incomplete = true; break }
                val child = node.getChild(i)
                if (child == null) { incomplete = true; continue }
                try { walk(child, depth + 1, feed, header, body, community, visibleHeader) } finally { child.recycle() }
            }
        }
        walk(root, 0)
        return RedditRules.decide(RedditStore.allowed(service), nodes, incomplete)
    }

    private fun block(pkg: String, reason: String) {
        val allowed = RedditStore.allowed(service)
        val state = Triple(pkg, reason, allowed)
        if (shown == state && cover != null) return
        val previous = cover
        val density = service.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding * 2, padding, padding)
            setBackgroundColor(Color.rgb(20, 24, 30))
        }
        fun text(value: String, size: Float) = TextView(service).apply {
            this.text = value; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, padding / 2, 0, padding / 2)
        }
        fun button(title: String, action: () -> Unit) {
            column.addView(Button(service).apply { text = title; setOnClickListener { action() } })
        }
        column.addView(text("Reddit restricted", 25f))
        column.addView(text(reason, 17f))
        column.addView(text("Open an allowed community", 19f))
        if (allowed.isEmpty()) column.addView(text("Your subreddit allowlist is empty.", 16f))
        for (name in allowed.sorted()) button("Open r/$name") {
            if (!RedditStore.enabled(service) || name !in RedditStore.allowed(service)) return@button
            try {
                service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/r/$name/"))
                    .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // Keep the cover until the destination identifies itself. The launch URL is not authorization.
                changed()
            } catch (_: RuntimeException) {
                Toast.makeText(service, "This client could not open the community link.", Toast.LENGTH_LONG).show()
            }
        }
        button("Manage subreddit allowlist") {
            try {
                // Launch directly: Home can arrive afterward and cover the settings activity on Vivo.
                // Keep the restriction cover until the foreground window actually changes.
                service.startActivity(Intent(service, MainActivity::class.java).setAction(MainActivity.ACTION_REDDIT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (error: RuntimeException) {
                android.util.Log.w("InstallLockReddit", "Could not open subreddit settings", error)
                Toast.makeText(service, "Could not open settings. Open Gouge and choose Reddit subreddit allowlist.", Toast.LENGTH_LONG).show()
            }
        }
        button("Leave Reddit") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        val view = ScrollView(service).apply { setBackgroundColor(Color.rgb(20, 24, 30)); isFillViewport = true; addView(column) }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            // Add the replacement before removing the old cover; changing a reason/list must not expose content.
            manager.addView(view, params)
            cover = view; shown = state
            previous?.let { try { manager.removeViewImmediate(it) } catch (_: RuntimeException) { } }
        }
        catch (_: RuntimeException) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Toast.makeText(service, "Reddit restricted: $reason", Toast.LENGTH_SHORT).show()
        }
    }
    private fun dismiss() {
        cover?.let { try { manager.removeViewImmediate(it) } catch (_: RuntimeException) { } }
        cover = null; shown = null
    }
}
