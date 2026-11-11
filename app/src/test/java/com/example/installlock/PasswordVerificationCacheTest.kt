package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class PasswordVerificationCacheTest {
    @Test fun repeatSuccessAvoidsExpensiveVerificationButEveryCredentialComponentIsBound() {
        val cache = PasswordVerificationCache()
        var calls = 0
        fun check(scope: String = "one", salt: String = "salt", hash: String = "hash", password: String = "password") =
            cache.matches(scope, salt, hash, password) { calls++; true }
        repeat(20) { assertTrue(check()) }
        assertEquals(1, calls)
        check(scope = "two"); check(salt = "new"); check(hash = "new"); check(password = "new")
        assertEquals(5, calls)
    }
    @Test fun failuresAreNeverCachedAndOldEntriesAreBounded() {
        val cache = PasswordVerificationCache(2)
        var calls = 0
        repeat(3) { assertFalse(cache.matches("one", "salt", "hash", "wrong") { calls++; false }) }
        assertEquals(3, calls)
        for (scope in listOf("a", "b", "c", "a"))
            assertTrue(cache.matches(scope, "salt", "hash", "right") { calls++; true })
        assertEquals(7, calls)
    }
}
