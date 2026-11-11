package com.example.installlock

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.*
import org.junit.Test

class PasswordKdfTest {
    @Test fun preservesExistingJcaHashesForAsciiUnicodeNulAndLongPasswords() {
        val salt = ByteArray(16) { it.toByte() }
        for (password in listOf("Password123", "pässவணக்கம்1🔐", "nul\u0000password1", "LongPassword1".repeat(20), "")) {
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).encoded
            assertArrayEquals(expected, PasswordKdf.derive(password, salt))
        }
        assertArrayEquals(ByteArray(16) { it.toByte() }, salt)
    }
    @Test fun saltAndPasswordChangesProduceDifferentHashes() {
        val original = PasswordKdf.derive("Password123", ByteArray(16))
        assertEquals(32, original.size)
        assertFalse(original.contentEquals(PasswordKdf.derive("Password124", ByteArray(16))))
        assertFalse(original.contentEquals(PasswordKdf.derive("Password123", ByteArray(16) { 1 })))
    }
    @Test fun malformedUtf16KeepsThePlatformsExistingEncodingBehavior() {
        for (password in listOf("prefix\uD800tail1", "prefix\uDC00tail1")) {
            val salt = ByteArray(16)
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).encoded
            assertArrayEquals(expected, PasswordKdf.derive(password, salt))
        }
    }
}
