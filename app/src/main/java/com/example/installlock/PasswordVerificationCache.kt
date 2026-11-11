package com.example.installlock

import java.security.MessageDigest

/** Cache only successful verification of the exact scope, credential version and candidate.
 * No plaintext, failed guesses, lockout decisions or task authorization are cached. */
internal class PasswordVerificationCache(private val capacity: Int = 64) {
    private val successes = object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }
    fun matches(scope: String, salt: String, hash: String, password: String, verify: () -> Boolean): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf(scope, salt, hash, password)) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val key = java.util.Base64.getEncoder().encodeToString(digest.digest())
        synchronized(successes) { if (successes[key] != null) return true }
        // Slow unrelated KDF work must not hold the cache monitor and stall existing hits.
        if (!verify()) return false
        synchronized(successes) { successes[key] = Unit }
        return true
    }
}
