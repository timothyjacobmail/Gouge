package com.example.installlock

import java.text.Normalizer
import java.util.Locale

internal class KeywordMatcher(words: Set<String>) {
    private val keywords = words.mapNotNull { normalizeWord(it) }.distinct().sorted()
    private val scanner = KeywordScanner(keywords.toTypedArray())

    fun findMatch(text: String): String? {
        return findNormalizedMatch(normalizeText(text))
    }

    internal fun findNormalizedMatch(text: String): String? =
        scanner.firstMatch(text).takeIf { it >= 0 }?.let(keywords::get)

    fun findMatches(text: String): Set<String> {
        return findNormalizedMatches(normalizeText(text))
    }

    internal fun findNormalizedMatches(text: String): Set<String> =
        scanner.matchingIndices(text).mapTo(linkedSetOf(), keywords::get)

    companion object {
        private val formatCharacters = Regex("\\p{Cf}")
        private val whitespace = Regex("\\s+")
        private val validWord = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} '\\-]*$")
        internal fun normalizeText(text: String): String =
            KeywordScanner.normalizeAscii(text) ?: Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(formatCharacters, "")
                .lowercase(Locale.ROOT).replace(whitespace, " ").trim()

        fun normalizeWord(input: String): String? {
            val word = normalizeText(input)
            return word.takeIf { it.length in 1..80 &&
                validWord.matches(it) }
        }
    }
}
