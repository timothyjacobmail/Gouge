package com.example.installlock

import java.net.URI
import java.util.Locale

/** A temporary approval never becomes a domain or path-prefix approval. */
internal object TemporaryLinkRules {
    const val ACCESS_MS = 24 * 60 * 60_000L
    private val escape = Regex("%[0-9a-fA-F]{2}")

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
            val query = uri.rawQuery?.split('&')?.filterNot { token ->
                val key = token.substringBefore('=').lowercase(Locale.ROOT)
                key.startsWith("utm_") || key in setOf("igsh", "igshid", "fbclid", "gclid")
            }?.filter { it.isNotEmpty() }?.sorted()?.joinToString("&")?.takeIf { it.isNotEmpty() }
            "$scheme://$host" + (port?.let { ":$it" } ?: "") + clean(path.trimEnd('/').ifEmpty { "/" }) +
                (query?.let { "?${clean(it)}" } ?: "")
        }
    } catch (_: Exception) { null }

    fun matches(approved: String, address: String) = normalize(address)?.let { it == normalize(approved) } == true

    /** Chrome uses prefix paths; the connected native URL monitor enforces the exact page. */
    fun policyPatterns(url: String): List<String> {
        val normalized = normalize(url) ?: return emptyList()
        val uri = URI(normalized)
        val port = if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port
        val suffix = ":$port${uri.rawPath}" + (uri.rawQuery?.let { "@$it" } ?: "")
        return listOf("${uri.scheme}://.${uri.host}$suffix", "${uri.scheme}://.www.${uri.host}$suffix")
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
