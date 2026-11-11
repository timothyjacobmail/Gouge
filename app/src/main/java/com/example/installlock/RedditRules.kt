package com.example.installlock

import java.net.URI
import java.util.Locale

/** No navigation history or previously allowed page can authorize a new screen. */
internal object RedditRules {
    const val OFFICIAL = "com.reddit.frontpage"
    val credentialKinds = setOf(TaskKind.REDDIT_ALLOW, TaskKind.REDDIT_OFF)
    private val name = Regex("[a-z0-9_]{2,21}")
    private val reserved = setOf("all", "popular", "home", "friends", "mod", "modqueue", "random", "randnsfw")
    private val redditHosts = setOf("reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com", "np.reddit.com", "m.reddit.com")

    fun redditHost(host: String?) = host?.lowercase(Locale.ROOT) in redditHosts

    fun normalize(input: String): String? {
        val value = input.trim().lowercase(Locale.ROOT)
        val candidate = if (value.contains("://")) {
            val uri = try { URI(value) } catch (_: Exception) { return null }
            if (uri.scheme !in setOf("http", "https") || !redditHost(uri.host) || uri.userInfo != null || uri.port != -1) return null
            // Encoded slashes and combined feeds must never turn into a single allowed community.
            val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[0] != "r") return null
            parts[1]
        } else value.removePrefix("/").removePrefix("r/").removeSuffix("/")
        return candidate.takeIf { name.matches(it) && it !in reserved }
    }

    fun likelyClient(pkg: String, label: String = "", redditLinkHandler: Boolean = false,
                     originalResources: Boolean = false): Boolean =
        pkg == OFFICIAL || pkg.lowercase(Locale.ROOT).contains("reddit") ||
            Regex("(?i)\\breddit\\b").containsMatchIn(label) || redditLinkHandler || originalResources

    // Official Reddit inserts a zero-width BOM between "r/" and the native community name.
    // Strip formatting marks only from native UI labels; saved names and URL input stay strict.
    fun nativeLabel(raw: String): String = raw.filterNot { Character.getType(it) == Character.FORMAT.toInt() }.trim()

    private val communityIds = setOf("subreddit", "subreddit_name", "subreddit_title", "subreddit_label",
        "community", "community_name", "community_title", "community_label", "post_subreddit", "post_subreddit_name",
        "link_subreddit", "subredditname", "communityname", "subreddit_text", "subreddit_header_title", "subreddit_toolbar_title")
    private val titleIds = setOf("toolbar_title", "action_bar_title", "collapsing_toolbar_title")
    // Compose can mark a title as offscreen while its current native app bar is visible.
    // Never read cached labels from an entirely hidden header or arbitrary hidden content.
    fun mayReadLabel(id: String, visible: Boolean, visibleCommunityHeader: Boolean) = visible ||
        visibleCommunityHeader && (id in communityIds || id == "title_text")
    // action_bar_root and DrawerLayout wrap the entire activity; neither identifies its content.
    fun headerContainer(id: String) = id in setOf("toolbar", "toolbar_container", "action_bar", "app_bar", "appbar") ||
        communityHeaderContainer(id)
    fun communityHeaderContainer(id: String) = listOf("subreddit_header", "community_header", "subreddit_toolbar", "community_nameplate",
        "post_detail_app_bar", "post_detail_header_provider")
        .any { id.contains(it) }
    fun contentContainer(id: String) = listOf("comment_body", "comment_text", "post_body", "post_title", "selftext", "markdown",
        "message_body", "message_text", "search_results", "search_suggestion", "drawer_content", "community_drawer", "navigation_drawer", "sidebar")
        .any { id.contains(it) }

    /** Resource roles come from the native UI, never an arbitrary mention in a post or editor. */
    fun decide(allowed: Set<String>, nodes: List<RedditUiNode>, incomplete: Boolean = false): RedditDecision {
        if (incomplete) return RedditDecision(false, "This Reddit screen cannot be fully inspected.")
        val headers = mutableSetOf<String>()
        val visibleCommunities = mutableSetOf<String>()
        for (node in nodes) {
            if (node.editable || node.content) continue
            val identity = node.id in communityIds
            val title = node.header || node.id in titleIds
            if (!identity && !title) continue
            for (raw in listOf(node.text, node.description).distinct()) {
                val text = nativeLabel(raw)
                val prefixed = text.startsWith("r/", true) || text.startsWith("/r/", true)
                val community = if (identity || prefixed) normalize(text) else null
                if (community != null) {
                    visibleCommunities.add(community)
                    if (node.header && identity || node.communityHeader && prefixed) headers.add(community)
                } else if ((identity || title) && text.lowercase(Locale.ROOT).removePrefix("r/").removePrefix("/r/") in reserved) {
                    return RedditDecision(false, "Mixed Reddit feeds are restricted. Open an allowed subreddit directly.")
                }
            }
        }
        if (headers.size != 1) return RedditDecision(false, "Open an allowed subreddit directly. This screen's community could not be verified.")
        val blocked = visibleCommunities.firstOrNull { it !in allowed }
        if (blocked != null) return RedditDecision(false, "r/$blocked is outside your subreddit allowlist.")
        return RedditDecision(true)
    }
}

internal data class RedditUiNode(val id: String = "", val text: String = "", val description: String = "",
    val header: Boolean = false, val inFeed: Boolean = false, val content: Boolean = false, val editable: Boolean = false,
    val communityHeader: Boolean = false)
internal data class RedditDecision(val allowed: Boolean, val reason: String = "")
