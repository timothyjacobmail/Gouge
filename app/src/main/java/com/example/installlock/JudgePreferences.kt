package com.example.installlock

import java.net.URI
import java.security.MessageDigest

/** Immutable local policy snapshot. Never contains passwords, private secrets or page history. */
internal data class JudgePreferences(
    val approvedPatterns: List<String> = emptyList(),
    val blockedWords: Set<String> = emptySet(),
    val wordExceptions: Map<String, Set<String>> = emptyMap(),
    val pageExceptions: Set<String> = emptySet(),
    val extraLocks: Set<String> = emptySet(),
    val dnsBlocks: Set<String> = emptySet(),
    val domainRules: List<DomainRule> = emptyList(),
    val keywordFilterEnabled: Boolean = true,
    val leisureCatalog: Set<String> = emptySet(),
    val engineRevision: String = "personalized-v1"
) {
    private val genericLabels = setOf("www", "com", "co", "org", "net", "gov", "edu", "ac", "docs", "support", "auth", "accounts", "mail", "one", "setup", "faq")
    private val wholeSiteHosts: Set<String> = approvedPatterns.mapNotNull { pattern ->
        val uri = JudgeRules.webUri(pattern) ?: return@mapNotNull null
        if (uri.path !in setOf("", "/") || uri.query != null) return@mapNotNull null
        val parts = uri.host.split('.')
        if (parts.size < 2 || parts.size == 2 && parts[0] in genericLabels && parts[1].length == 2) return@mapNotNull null
        uri.host.takeUnless { host -> JudgeRules.leisureHost(host, leisureCatalog) ||
            extraLocks.any { host == it || host.endsWith(".$it") } ||
            dnsBlocks.any { host == it || host.endsWith(".$it") } ||
            DomainRules(domainRules).match(host)?.rule?.action == DomainAction.BLOCK ||
            keywordFilterEnabled && KeywordMatcher(blockedWords).findMatch(host.replace('.', ' ').replace('-', ' ')) != null }
    }.toSet()
    // The existing whole-domain permission already includes its conventional www host.
    // Do not derive arbitrary sibling/child hosts from a completed approval.
    val learnedHosts: Set<String> = (wholeSiteHosts + wholeSiteHosts.filter { it.count { ch -> ch == '.' } == 1 }
        .map { "www.$it" }).filter { host ->
        !JudgeRules.leisureHost(host, leisureCatalog) && extraLocks.none { host == it || host.endsWith(".$it") } &&
            dnsBlocks.none { host == it || host.endsWith(".$it") } &&
            DomainRules(domainRules).match(host)?.rule?.action != DomainAction.BLOCK &&
            (!keywordFilterEnabled || KeywordMatcher(blockedWords).findMatch(host.replace('.', ' ').replace('-', ' ')) == null)
    }.toSet()
    val eligibleHosts: Set<String> = JudgeRules.reviewedHosts + learnedHosts
    private val keywordFilter = KeywordPageFilter(blockedWords, wordExceptions, pageExceptions)

    /** Order matters for domain rules; all other collections have canonical ordering. */
    val revision: String by lazy {
        val fields = listOf(engineRevision, keywordFilterEnabled.toString()) +
            approvedPatterns.sorted().map { "site:$it" } + blockedWords.sorted().map { "word:$it" } +
            wordExceptions.toSortedMap().flatMap { (word, values) -> values.sorted().map { "exception:$word|$it" } } +
            pageExceptions.sorted().map { "page:$it" } + extraLocks.sorted().map { "lock:$it" } + dnsBlocks.sorted().map { "dns:$it" } +
            domainRules.map { "domain:${it.encode()}" } + leisureCatalog.sorted().map { "leisure:$it" }
        MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun veto(address: String, text: String? = null): String? {
        val uri = JudgeRules.webUri(address) ?: return "This address is not eligible for Judge."
        if (JudgeRules.leisureHost(uri.host, leisureCatalog)) return "Leisure sites keep the normal approval delay."
        if (extraLocks.any { uri.host == it || uri.host.endsWith(".$it") }) return "Your extra locked sites block this host."
        if (dnsBlocks.any { uri.host == it || uri.host.endsWith(".$it") }) return "Your Google DNS blocklist excludes this host from Judge."
        if (DomainRules(domainRules).match(uri.host)?.rule?.action == DomainAction.BLOCK)
            return "Your first matching domain rule blocks this host."
        if (keywordFilterEnabled) {
            // A URL must not satisfy its own context exception using arbitrary query text.
            val hostWord = KeywordMatcher(blockedWords).findMatch(uri.host.replace('.', ' ').replace('-', ' '))
            if (hostWord != null) return "Your blocked word '$hostWord' matches this host."
            if (text != null) keywordFilter.findBlock(text)?.let { return "Your blocked word '$it' matches this page without an applicable exception." }
        }
        return null
    }

    fun recognizesUsefulContent(host: String, text: String): Boolean {
        if (host !in learnedHosts) return false
        val brands = host.split('.').dropLast(1).flatMap { it.split('-') }.filter { it.length >= 3 && it !in genericLabels }
        if (brands.none { Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(text) }) return false
        val practical = setOf("document", "documents", "pdf", "convert", "compress", "documentation", "developer",
            "software", "code", "programming", "engineering", "technical", "installation", "troubleshooting",
            "support", "account", "payment", "application", "registration", "license", "licence", "government",
            "training", "course", "safety", "maintenance", "bible", "scripture", "theology", "christian", "study")
        return KeywordMatcher(practical).findMatches(text).size >= 2
    }

    fun refreshCandidates(saved: Set<String>): Set<String> = saved.filter {
        it in eligibleHosts && veto("https://$it/") == null
    }.toSet()
}
