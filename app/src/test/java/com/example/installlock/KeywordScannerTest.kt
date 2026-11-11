package com.example.installlock

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.text.Normalizer
import java.util.Locale

class KeywordScannerTest {
    private fun originalNormalization(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(Regex("\\p{Cf}"), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    private fun reference(words: Set<String>, text: String): Set<String> {
        val page = KeywordMatcher.normalizeText(text)
        return words.mapNotNull(KeywordMatcher::normalizeWord).distinct().sorted().filter {
            Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])").containsMatchIn(page)
        }.toCollection(linkedSetOf())
    }

    @Test fun bothStrategiesPreserveUnicodeWordBoundariesAndLexicalPriority() {
        val words = setOf("anime", "comic book", "manga", "x-ray", "a", "book", "art", "தமிழ்", "中文", "𐐨")
        val pages = listOf("manga then anime", "comic book and book", "chart art_artist art2", "x-ray x-rays",
            "an\u200bime", "ＡＮＩＭＥ", "தமிழ் 中文", "𐐨anime anime𐐨", "²anime anime²", "Ⅳanime animeⅣ",
            "\u0301anime anime\u0301", "🙂anime🙂", "comic\n\tbook", "'art'", "𐐀", "", "\ud800anime\udc00")
        for (configured in listOf(words, words + (1..25).map { "filler$it" })) {
            val scanner = KeywordMatcher(configured)
            for (page in pages) {
                val expected = reference(configured, page)
                assertEquals(page, expected, scanner.findMatches(page))
                assertEquals(page, expected.firstOrNull(), scanner.findMatch(page))
            }
        }
    }

    @Test fun overlappingPhrasesAndFailureLinksReturnAllMatchesInStableOrder() {
        val words = setOf("a", "a a", "a a a", "a book", "book", "comic book", "book review", "review") +
            (1..20).map { "filler$it" }
        val page = "comic book review; a a a book review"
        val expected = reference(words, page)
        assertEquals(expected.toList(), KeywordMatcher(words).findMatches(page).toList())
        assertEquals(expected.first(), KeywordMatcher(words).findMatch(page))
    }

    @Test fun randomizedPagesMatchTheOriginalRegexImplementation() {
        val random = Random(12452)
        val vocabulary = listOf("a", "art", "artist", "comic", "book", "review", "manga", "தமிழ்", "中文", "𐐨", "x-ray")
        val separators = listOf(" ", "_", "2", "²", "Ⅳ", ".", "-", "\n", "\u200b", "🙂", "\u0301")
        repeat(300) {
            val words = buildSet {
                repeat(1 + random.nextInt(45)) {
                    add(vocabulary[random.nextInt(vocabulary.size)] + if (random.nextBoolean())
                        " " + vocabulary[random.nextInt(vocabulary.size)] else "")
                }
            }
            val page = buildString {
                repeat(40) { append(vocabulary[random.nextInt(vocabulary.size)]); append(separators[random.nextInt(separators.size)]) }
            }
            val expected = reference(words, page)
            val matcher = KeywordMatcher(words)
            assertEquals(page, expected, matcher.findMatches(page))
            assertEquals(page, expected.firstOrNull(), matcher.findMatch(page))
        }
    }

    @Test fun normalizationDuplicatesEmptyRulesAndRepeatedCallsDoNotRetainPageState() {
        val matcher = KeywordMatcher(setOf("anime", " ANIME ", "ＡＮＩＭＥ", "invalid|word"))
        assertEquals(setOf("anime"), matcher.findMatches("anime"))
        assertNull(matcher.findMatch("safe page"))
        assertTrue(matcher.findMatches("safe page").isEmpty())
        assertNull(KeywordMatcher(emptySet()).findMatch("anime"))
        val many = KeywordMatcher((1..30).map { "word$it" }.toSet())
        assertEquals(setOf("word10", "word20"), many.findMatches("word20 word10"))
        assertTrue(many.findMatches("safe page").isEmpty())
    }

    @Test fun asciiFastPathMatchesOriginalNormalizationIncludingEveryControlCharacter() {
        val random = Random(925)
        val cases = listOf("", "ALL CAPS", "already normalized", "\t  A\r\n  B \u001c", "\u001cA\u001dB\u001e",
            (0..127).map(Int::toChar).joinToString("")) + List(300) {
                buildString { repeat(random.nextInt(512)) { append(random.nextInt(128).toChar()) } }
            }
        for (text in cases) {
            assertEquals(originalNormalization(text), KeywordScanner.normalizeAscii(text))
            assertEquals(originalNormalization(text), KeywordMatcher.normalizeText(text))
        }
        for (text in listOf("ＡＮＩＭＥ", "an\u200bime", "Tamil தமிழ்", "emoji 🙂", "İIıi", "a\u00a0b")) {
            assertNull(KeywordScanner.normalizeAscii(text))
            assertEquals(originalNormalization(text), KeywordMatcher.normalizeText(text))
        }
    }
}
