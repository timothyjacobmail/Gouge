package com.example.installlock

/** Multiple independent, visible purpose signals; never a positive score from a page's own "safe" claim. */
internal object JudgeContent {
    private val generic = setOf("www", "docs", "support", "accounts", "auth", "mail", "setup", "faq")
    private val countrySecondLevels = setOf("co", "com", "org", "net", "gov", "edu", "ac")
    private data class Purpose(val name: String, val signals: List<Regex>)
    private fun words(vararg patterns: String) = patterns.map { Regex("(?i)\\b(?:$it)\\b") }
    private val purposes = listOf(
        Purpose("document tools", words("pdf|documents?", "merge|split|compress|convert|ocr", "files?|upload|download|edit|sign")),
        Purpose("technical documentation", words("documentation|reference|manuals?", "api|software|engineering|technical", "install(?:ation)?|configuration|troubleshoot(?:ing)?|parameters?|specifications?")),
        Purpose("professional services", words("engineering|fire safety|security systems|electrical|mechanical", "maintenance|inspection|installation|design", "services?|projects?|contact|certification")),
        Purpose("public services", words("government|municipality|public services|licen[cs]e", "application|registration|renewal|permit", "services?|payment|documents?|requirements")),
        Purpose("study resources", words("bible|scripture|theology|mathematics|physics|chemistry", "study|lessons?|courses?|exercises?", "explanations?|reference|questions?|education|learning"))
    )

    fun identity(host: String, text: String): Boolean {
        val labels = host.split('.')
        val index = if (labels.size >= 3 && labels.last().length == 2 && labels[labels.lastIndex - 1] in countrySecondLevels)
            labels.lastIndex - 2 else labels.lastIndex - 1
        val brand = labels.getOrNull(index)?.takeUnless { it in generic || it.length < 3 } ?: return false
        // Spaces/punctuation in a real business name need not match the DNS spelling.
        val letters = brand.filter(Char::isLetterOrDigit).map { Regex.escape(it.toString()) }
        return Regex("(?i)(?<![\\p{L}\\p{N}])" + letters.joinToString("[\\s\\p{Punct}]*") + "(?![\\p{L}\\p{N}])")
            .containsMatchIn(text)
    }

    fun classify(host: String, html: String): String? {
        val readable = JudgeRules.readableMarkup(html)
        val heading = Regex("(?is)<(?:title|h1)\\b[^>]*>(.*?)</(?:title|h1)>").findAll(readable)
            .joinToString(" ") { JudgeRules.visibleText(it.groupValues[1]) }
        if (!identity(host, heading)) return null
        val main = Regex("(?is)<(?:main|article)\\b[^>]*>(.*?)</(?:main|article)>").find(readable)?.groupValues?.get(1)
            ?: Regex("(?is)<body\\b[^>]*>(.*?)</body>").find(readable)?.groupValues?.get(1) ?: return null
        val text = JudgeRules.visibleText(main)
        if (text.length < 200) return null
        return purposes.firstOrNull { purpose -> purpose.signals.all { it.containsMatchIn(text) } &&
            purpose.signals.take(2).any { it.containsMatchIn(heading) } }?.name
    }
}
