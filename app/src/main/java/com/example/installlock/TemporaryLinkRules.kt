package com.example.installlock

import java.net.URI
import java.util.Locale

/** A temporary approval never becomes a domain or path-prefix approval. */
internal object TemporaryLinkRules {
    const val ACCESS_MS = 24 * 60 * 60_000L
    private val escape = Regex("%[0-9a-fA-F]{2}")
    private val instagramPage = Regex("^/(?:p|reel|reels)/([A-Za-z0-9_-]{3,128})/?$")
    private fun instagramId(uri: URI): String? = if (uri.host == "instagram.com")
        instagramPage.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1) else null

    fun normalize(input: String): String? = try {
        val text = input.trim()
        val uri = URI(if ("://" in text) text else "https://$text")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        if (text.length > 8192 || scheme !in setOf("https", "http") || host == null ||
            SitePattern.normalize(host) == null || SitePattern.isLocalHost(host) || uri.rawUserInfo != null ||
            uri.port != -1 && uri.port !in 1..65535 || '\\' in path ||
            path.split('/').any { it.replace("%2e", ".", true) in setOf(".", "..") }) null
        else {
            val port = uri.port.takeIf { it != -1 && it != if (scheme == "https") 443 else 80 }
            fun clean(value: String) = escape.replace(value.replace("*", "%2A").replace("@", "%40")) {
                it.value.uppercase(Locale.ROOT)
            }
            // Shared Instagram links often lose these tracking values after their first load.
            // Keep content IDs (e.g. YouTube's v=) so another video cannot inherit permission.
            // Instagram identifies posts/reels by the shortcode in these paths. Share
            // tokens such as xtok disappear on redirects and must not become required
            // Chrome policy query tokens. Other sites retain their identifying query.
            val instagramContent = host == "instagram.com" && instagramPage.matches(path)
            val query = (if (instagramContent) null else uri.rawQuery)?.split('&')?.filterNot { token ->
                val key = token.substringBefore('=').lowercase(Locale.ROOT)
                key.startsWith("utm_") || key in setOf("igsh", "igshid", "fbclid", "gclid")
            }?.filter { it.isNotEmpty() }?.sorted()?.joinToString("&")?.takeIf { it.isNotEmpty() }
            "$scheme://$host" + (port?.let { ":$it" } ?: "") + clean(path.trimEnd('/').ifEmpty { "/" }) +
                (query?.let { "?${clean(it)}" } ?: "")
        }
    } catch (_: Exception) { null }

    fun matches(approved: String, address: String): Boolean {
        val expected = normalize(approved) ?: return false
        val actual = normalize(address) ?: return false
        if (expected == actual) return true
        val a = URI(expected)
        val b = URI(actual)
        val id = instagramId(a) ?: return false
        return a.scheme == b.scheme && a.port == b.port && id == instagramId(b)
    }

    /** Open Instagram's canonical www URL directly instead of depending on an apex redirect. */
    fun openUrl(input: String): String? {
        val normalized = normalize(input) ?: return null
        val uri = URI(normalized)
        if (instagramId(uri) == null) return normalized
        return "${uri.scheme}://www.instagram.com" + (if (uri.port == -1) "" else ":${uri.port}") + uri.rawPath + "/"
    }

    /** Chrome uses prefix paths; the connected native URL monitor enforces the exact page. */
    fun policyPatterns(url: String): List<String> {
        val normalized = normalize(url) ?: return emptyList()
        val uri = URI(normalized)
        val port = if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port
        // A shared post can redirect to the reel presentation of the same shortcode.
        val paths = instagramId(uri)?.let { id -> listOf("/p/$id", "/reel/$id", "/reels/$id") }
            ?: listOf(uri.rawPath)
        return paths.flatMap { path ->
            val suffix = ":$port$path" + (uri.rawQuery?.let { "@$it" } ?: "")
            listOf("${uri.scheme}://.${uri.host}$suffix", "${uri.scheme}://.www.${uri.host}$suffix")
        }
    }
}

internal data class TemporaryLink(
    val id: String, val url: String, val expiresWall: Long = 0, val expiresElapsed: Long = 0,
    val boot: Int = -1, val notified: Boolean = false
) {
    val ready get() = expiresWall == 0L
    fun remaining(now: WatchdogClock): Long = when {
        ready || now.wall < expiresWall - TemporaryLinkRules.ACCESS_MS -> 0
        boot == now.boot -> minOf(expiresWall - now.wall, expiresElapsed - now.elapsed).coerceAtLeast(0)
        // Device-owner policy enforces automatic time. Downtime counts after a reboot;
        // the original deadline is retained instead of starting another day.
        else -> (expiresWall - now.wall).coerceIn(0, TemporaryLinkRules.ACCESS_MS)
    }
    fun active(now: WatchdogClock) = !ready && remaining(now) > 0
    fun open(now: WatchdogClock): TemporaryLink? = when {
        ready -> copy(expiresWall = now.wall + TemporaryLinkRules.ACCESS_MS,
            expiresElapsed = now.elapsed + TemporaryLinkRules.ACCESS_MS, boot = now.boot)
        active(now) -> this // Reopening, notification replay and process restarts cannot extend access.
        else -> null
    }
}
