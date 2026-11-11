package com.example.installlock

/** Verify each distinct saved value once per lookup. Retain every matching entry: duplicates
 * and distinct HMAC-equivalent passwords can carry longer delays or hard locks. */
internal object StoredSecretLookup {
    fun <T> matching(items: List<T>, value: (T) -> String, verify: (String) -> Boolean): List<T> {
        val results = HashMap<String, Boolean>()
        return items.filter { item -> results.getOrPut(value(item)) { verify(value(item)) } }
    }
}
