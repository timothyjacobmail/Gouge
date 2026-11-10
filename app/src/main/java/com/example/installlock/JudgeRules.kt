package com.example.installlock

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal enum class JudgeDecision { IMMEDIATE, DELAY, UNVERIFIED }
internal data class Judgement(val decision: JudgeDecision, val reason: String,
                              val address: String = "", val hosts: Set<String> = emptySet())

/** Website claims never expand this catalog. Only a signed app update can change it. */
internal object JudgeRules {
    val reviewedHosts = setOf("edcad.ae", "www.edcad.ae", "pay.edcad.ae", "elearning.edcad.ae")
    private val leisureDomains = setOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com",
        "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com", "redgifs.com",
        "instagram.com", "cdninstagram.com", "facebook.com", "fb.com", "fb.watch", "fbcdn.net",
        "x.com", "twitter.com", "t.co", "twimg.com", "threads.net", "threads.com",
        "mangadex.org", "mangadex.com", "weebcentral.com", "deviantart.com", "wixmp.com",
        "chess.com", "lichess.org", "chess24.com", "twitch.tv", "kick.com", "tiktok.com",
        "netflix.com", "primevideo.com", "disneyplus.com", "hulu.com", "max.com", "hbomax.com",
        "crunchyroll.com", "hidive.com", "bilibili.com", "bilibili.tv", "dailymotion.com", "vimeo.com",
        "9anime.to", "aniwatch.to", "hianime.to", "animepahe.ru", "animepahe.com", "myanimelist.net",
        "anilist.co", "fandom.com", "webtoons.com", "mangaplus.shueisha.co.jp", "mangareader.to",
        "pinterest.com", "tumblr.com", "9gag.com", "imgur.com", "discord.com", "discord.gg",
        "roblox.com", "steampowered.com", "store.steampowered.com", "epicgames.com", "poki.com",
        "crazygames.com", "coolmathgames.com", "spotify.com", "soundcloud.com", "imdb.com"
    )
    private val leisureWords = Regex("(?i)\\b(anime|manga|manhwa|comics?|movies?|streaming|entertainment|games?|gaming|video[ -]?games?|chess|hentai|porn|casino|gambling|social[ -]network|watch[ -]online)\\b")
    private val challenge = Regex("(?i)(just a moment|verify you are human|checking your browser|access denied|enable javascript|captcha challenge)")

    fun webUri(input: String): URI? = runCatching {
        val text = input.trim()
        require(text.length in 1..4096 && text.none { it.isWhitespace() || it == '\\' || it.code < 32 })
        val uri = URI(if (text.contains("://")) text else "https://$text")
        val host = uri.host?.lowercase(Locale.ROOT) ?: error("No host")
        require(uri.scheme.equals("https", true) && uri.port in setOf(-1, 443) && uri.userInfo == null)
        require(host == DomainRules.normalizeHost(host) && '.' in host && !host.endsWith(".local"))
        require(!host.split('.').all { it.toIntOrNull() != null })
        URI("https", null, host, -1, uri.path.ifEmpty { "/" }, uri.query, null)
    }.getOrNull()

    fun leisureHost(host: String, extra: Set<String> = emptySet()): Boolean {
        val normalized = DomainRules.normalizeHost(host) ?: return true
        return (leisureDomains + extra).any { normalized == it || normalized.endsWith(".$it") } ||
            leisureWords.containsMatchIn(normalized.replace('.', ' ').replace('-', ' '))
    }

    fun unsafeRoute(address: String): Boolean = runCatching {
        val uri = webUri(address) ?: return true
        // Multiple layers of encoding must not hide a leisure or redirect route.
        var route = uri.rawPath.orEmpty() + "?" + uri.rawQuery.orEmpty()
        repeat(3) { route = URLDecoder.decode(route, "UTF-8") }
        leisureWords.containsMatchIn(route.replace('/', ' ').replace('_', ' ').replace('-', ' ')) ||
            Regex("(?i)(https?[:/]|(?:^|[?&/])(url|redirect|redirect_uri|redirect_url|target|next|continue|dest|destination|return|returnurl|return_url|proxy|embed|watch)(?:[=/]|$))").containsMatchIn(route)
    }.getOrDefault(true)

    fun visibleText(html: String): String {
        var text = html.take(512 * 1024)
        text = text.replace(Regex("<!--[\\s\\S]*?-->"), " ")
        text = text.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|aside)\\b[^>]*>[\\s\\S]*?</\\1\\s*>"), " ")
        text = text.replace(Regex("<[^>]*>"), " ")
        text = text.replace(Regex("&#(?:x([0-9a-fA-F]+)|([0-9]+));")) {
            val n = it.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull(16)
                ?: it.groupValues[2].toIntOrNull()
            n?.takeIf { code -> code in 32..126 }?.toChar()?.toString() ?: " "
        }
        return text.replace(Regex("&[a-zA-Z]+;"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun inspect(address: String, html: String): Judgement {
        val uri = webUri(address) ?: return Judgement(JudgeDecision.UNVERIFIED, "Use a public HTTPS website on its normal port.")
        if (leisureHost(uri.host) || unsafeRoute(address))
            return Judgement(JudgeDecision.DELAY, "Leisure content or a redirect route keeps the normal whitelist delay.")
        if (Regex("(?is)<meta\\b[^>]*http-equiv\\s*=\\s*['\"]?refresh").containsMatchIn(html))
            return Judgement(JudgeDecision.UNVERIFIED, "This page redirects through HTML. Its destination must be checked separately with the normal delay unchanged.")
        val leisureLinks = Regex("(?i)(?:href|src)\\s*=\\s*['\"]([^'\"]+)['\"]").findAll(html)
            .mapNotNull { runCatching { webUri(uri.resolve(it.groupValues[1].replace("&amp;", "&")).toString())?.host }.getOrNull() }
            .any { leisureHost(it) }
        val text = visibleText(html)
        if (text.length < 120 || challenge.containsMatchIn(text))
            return Judgement(JudgeDecision.UNVERIFIED, "The page could not be read reliably. The normal delay still applies.")
        if (leisureWords.containsMatchIn(text))
            return Judgement(JudgeDecision.DELAY, "The page contains leisure or mixed-purpose content. The normal delay still applies.")
        if (uri.host !in reviewedHosts)
            return Judgement(JudgeDecision.UNVERIFIED, "Page inspected, but this domain is not independently reviewed. Its text alone cannot authorize immediate access.")
        val identity = Regex("(?i)(emirates driving|edc student|edc e-learning|edc elearning)").containsMatchIn(text)
        val purpose = Regex("(?i)(driving|driver|licen[cs]e|training|practical|theory|lesson|course)").containsMatchIn(text)
        if (!identity || !purpose)
            return Judgement(JudgeDecision.UNVERIFIED, "This page does not confirm the reviewed driving-training service. The normal delay still applies.")
        val links = if (leisureLinks) " Links to leisure platforms were found; those destinations remain blocked." else ""
        return Judgement(JudgeDecision.IMMEDIATE, "Reviewed driving-training service; readable content confirms its purpose.$links", uri.toASCIIString(), setOf(uri.host))
    }

    /** Persisted entries are never trusted as arbitrary website approvals. */
    fun activeHosts(saved: Set<String>, extraLocks: Set<String>, domainRules: List<DomainRule>): Set<String> =
        saved.filter { host -> host in reviewedHosts && !leisureHost(host) &&
            extraLocks.none { host == it || host.endsWith(".$it") } &&
            DomainRules(domainRules).match(host)?.rule?.action != DomainAction.BLOCK }.toSet()
}
