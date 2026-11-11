package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class RedditRulesTest {
    private val allowed = setOf("android", "kotlin")
    private fun header(name: String) = RedditUiNode(id = "subreddit_name", text = name, header = true)
    private fun post(name: String) = RedditUiNode(id = "community_name", text = name, inFeed = true)

    @Test fun namesAndCanonicalLinksNormalizeWithoutAllowingCombinedOrSpecialFeeds() {
        assertEquals("android", RedditRules.normalize(" /r/Android/ "))
        assertEquals("kotlin", RedditRules.normalize("https://old.reddit.com/r/Kotlin/comments/abc/post/?x=1"))
        for (input in listOf("r/all", "popular", "r/random", "r/mod", "android+kotlin", "r/android/comments",
            "https://reddit.com.evil.org/r/android", "https://reddit.com@evil.org/r/android", "https://evil.org/r/android",
            "https://reddit.com/r/android%2Fkotlin", "https://reddit.com/r/android+kotlin", "https://reddit.com/u/android",
            "https://reddit.com:444/r/android", "javascript:android", "a", "a".repeat(22)))
            assertNull(input, RedditRules.normalize(input))
    }

    @Test fun everyVisibleNativeCommunityIdentityMustBeAllowed() {
        assertTrue(RedditRules.decide(allowed, listOf(RedditUiNode(id = "subreddit_header_title", text = "r/\uFEFFandroid", header = true, communityHeader = true))).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(header("r/\u200Bother"))).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(header("r/\uFEFFall"))).allowed)
        assertTrue(RedditRules.decide(allowed, listOf(header("r/Android"), post("r/kotlin"))).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(header("r/android"), post("r/other"))).allowed)
        assertFalse(RedditRules.decide(emptySet(), listOf(header("r/android"))).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(header("r/other"))).allowed)
    }

    @Test fun allowedMentionPostTitleEditorOrFeedCardCannotAuthorizeAnUnknownScreen() {
        for (node in listOf(RedditUiNode(text = "r/android"), RedditUiNode(id = "title", text = "r/android"),
            RedditUiNode(id = "subreddit_name", text = "r/android"),
            RedditUiNode(id = "subreddit_name", text = "r/android", editable = true),
            RedditUiNode(id = "subreddit_name", text = "r/android", content = true), post("r/android")))
            assertFalse(node.toString(), RedditRules.decide(allowed, listOf(node)).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(post("r/android"), post("r/kotlin"))).allowed)
    }

    @Test fun missingAmbiguousAndIncompleteScreensAlwaysBlockIncludingAfterAnAllowedPage() {
        assertTrue(RedditRules.decide(allowed, listOf(header("r/android"))).allowed)
        assertFalse(RedditRules.decide(allowed, emptyList()).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(header("r/android")), incomplete = true).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(header("r/android"), header("r/kotlin"))).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(RedditUiNode(id = "toolbar_title", text = "r/all"), post("r/android"))).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(RedditUiNode(id = "toolbar_title", text = "Search"), post("r/android"))).allowed)
    }

    @Test fun specificCommunityHeadersSupportRepackagedClientsButArbitraryToolbarTitlesDoNot() {
        assertFalse(RedditRules.headerContainer("action_bar_root"))
        assertFalse(RedditRules.contentContainer("drawer_layout"))
        assertFalse(RedditRules.contentContainer("comments_page"))
        assertTrue(RedditRules.contentContainer("comment_body"))
        assertTrue(RedditRules.communityHeaderContainer("post_detail_app_bar"))
        assertFalse(RedditRules.mayReadLabel("title_text", false, false))
        assertFalse(RedditRules.mayReadLabel("post_subreddit", false, false))
        assertFalse(RedditRules.mayReadLabel("post_body", false, true))
        assertTrue(RedditRules.mayReadLabel("title_text", false, true))
        assertTrue(RedditRules.decide(allowed, listOf(RedditUiNode(id = "title_text", text = "r/android", header = true,
            communityHeader = true, inFeed = true))).allowed)
        assertTrue(RedditRules.decide(allowed, listOf(RedditUiNode(text = "r/android", header = true, communityHeader = true))).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(RedditUiNode(text = "r/android", header = true))).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(RedditUiNode(id = "toolbar_title", text = "r/android"))).allowed)
        assertFalse(RedditRules.decide(allowed, listOf(RedditUiNode(text = "android", header = true))).allowed)
        assertTrue(RedditRules.likelyClient("com.reddit.frontpage"))
        assertTrue(RedditRules.likelyClient("com.reddit.frontpage.revanced"))
        assertTrue(RedditRules.likelyClient("custom.reader", "Reddit Mod"))
        assertTrue(RedditRules.likelyClient("custom.reader", "Reader", redditLinkHandler = true))
        assertTrue(RedditRules.likelyClient("custom.reader", originalResources = true))
        assertFalse(RedditRules.likelyClient("com.android.chrome", "Chrome"))
    }

    @Test fun disconnectedRedditMonitoringIsAnIndependentAppLockReason() {
        val reasons = AppLockReasons(redditServiceMissing = true, appLock = false)
        assertTrue(reasons.shouldLock)
        assertTrue(reasons.descriptions().any { it.contains("subreddit allowlist") })
    }
}
