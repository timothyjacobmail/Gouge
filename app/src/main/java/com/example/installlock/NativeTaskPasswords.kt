package com.example.installlock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Keystore ciphertext and the authentication hash are committed in the same preference transaction. */
internal object NativeTaskPasswords {
    private fun prefs(c: Context) = c.getSharedPreferences("install_lock", Context.MODE_PRIVATE)
    private fun prefix(scope: TaskScope) = "task.${scope.id}."
    fun hasSavedPassword(c: Context, scope: TaskScope) = prefs(c).contains(prefix(scope) + "secret")
    @Synchronized private fun key(c: Context): SecretKey {
        val alias = "${c.packageName}.native_task_passwords.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(c: Context, scope: TaskScope, password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(c))
        cipher.updateAAD(scope.id.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun password(c: Context, scope: TaskScope): String? = try {
        val raw = prefs(c).getString(prefix(scope) + "secret", null)
        if (raw == null) null else {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(c), javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(scope.id.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
                .takeIf { TaskPasswords.vault(c).matchesStoredSecret(scope, it) }
        }
    } catch (_: Exception) { null }

    fun reference(c: Context, scope: TaskScope): String = "native:${scope.id}:${prefs(c).getString(prefix(scope) + "hash", "")}"
    fun delay(c: Context, scope: TaskScope): Int {
        if (scope.kind == TaskKind.PLAY_STORE_DELAY) return prefs(c).getInt(prefix(scope) + "delayMinutes", 2)
        return maxOf(Store.timingConfig(c)[TimingOption.TASK_WAIT], prefs(c).getInt(prefix(scope) + "delayFloor", 1))
    }

    /** An upgrade can attach only the current password; it cannot replace a credential or skip a wait. */
    fun attach(c: Context, scope: TaskScope, password: String, minimumDelay: Int = 1): Boolean = synchronized(TaskPasswords) {
        if (!TaskPasswords.check(c, scope, password)) return@synchronized false
        prefs(c).edit().putString(prefix(scope) + "secret", encrypt(c, scope, password))
            .putInt(prefix(scope) + "delayFloor", maxOf(minimumDelay, prefs(c).getInt(prefix(scope) + "delayFloor", 1))).commit()
    }
}
