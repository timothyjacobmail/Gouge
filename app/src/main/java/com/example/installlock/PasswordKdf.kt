package com.example.installlock

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Same UTF-8 PBKDF2-HMAC-SHA256, salt, 120,000 rounds and 256-bit result as existing passwords.
 * RustCrypto performs all rounds within one native call. The platform implementation remains
 * available for a JVM, a missing native ABI, or malformed UTF-16 requiring provider semantics. */
internal object PasswordKdf {
    fun derive(password: String, salt: ByteArray): ByteArray {
        require(salt.isNotEmpty()) { "Salt must not be empty" }
        if (NativePasswordKdf.available && validUtf16(password)) {
            val bytes = password.toByteArray(Charsets.UTF_8)
            try { return NativePasswordKdf.derive(bytes, salt) }
            finally { bytes.fill(0) }
        }
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun validUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isHighSurrogate(char)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
            } else if (Character.isLowSurrogate(char)) return false
        }
        return true
    }
}
