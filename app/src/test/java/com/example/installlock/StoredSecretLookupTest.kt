package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class StoredSecretLookupTest {
    private data class Entry(val value: String, val delay: Int = 1, val locked: Boolean = false)
    @Test fun hashesEachDistinctValueOnceAndPreservesAllDuplicateDelaysAndLocks() {
        val entries = listOf(Entry("wrong"), Entry("right"), Entry("irrelevant"), Entry("right", 60, true))
        val tested = mutableListOf<String>()
        val matches = StoredSecretLookup.matching(entries, { it.value }) { tested.add(it); it == "right" }
        assertEquals(listOf("wrong", "right", "irrelevant"), tested)
        assertEquals(listOf(entries[1], entries[3]), matches)
        assertEquals(60, matches.maxOf { it.delay })
        assertTrue(matches.any { it.locked })
    }
    @Test fun distinctValuesWhichAuthenticateToTheSameCredentialKeepAllRestrictions() {
        val entries = listOf(Entry("right"), Entry("right\u0000", 60, true))
        assertEquals(entries, StoredSecretLookup.matching(entries, { it.value }) { true })
    }
    @Test fun verifiesDistinctCandidatesOnlyAndDoesNotRememberFailedGuesses() {
        val entries = listOf(Entry("a"), Entry("b"), Entry("a"), Entry("b"))
        var calls = 0
        repeat(2) { assertTrue(StoredSecretLookup.matching(entries, { it.value }) { calls++; false }.isEmpty()) }
        assertEquals(4, calls)
    }
    @Test fun emptyVaultNeverVerifiesAndEachScopeChoosesItsOwnValue() {
        assertTrue(StoredSecretLookup.matching(emptyList<Entry>(), { it.value }) { error("Unexpected verification") }.isEmpty())
        val entries = listOf(Entry("a"), Entry("b"))
        assertEquals(listOf(entries[0]), StoredSecretLookup.matching(entries, { it.value }) { it == "a" })
        assertEquals(listOf(entries[1]), StoredSecretLookup.matching(entries, { it.value }) { it == "b" })
    }
}
