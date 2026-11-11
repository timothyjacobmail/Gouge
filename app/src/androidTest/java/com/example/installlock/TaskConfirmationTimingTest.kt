package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.delayedsecrect.SecretStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic credentials and isolated files; never schedules a task or changes device policy. */
class TaskConfirmationTimingTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "confirmation_timing_${System.nanoTime()}_"
    private val names = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(name)
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    private val scope = TaskScope(TaskKind.APP_LIMIT, "example.video")
    private fun <T> timed(label: String, operation: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        return operation().also {
            Log.i("TaskConfirmationTiming", "$label: ${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0} ms")
        }
    }
    @Test fun nativeMetadataAndAuthentication() {
        assertNull(TaskPasswords.vault(context).install(listOf(NewTaskPassword(scope, "SyntheticTiming1")), false))
        timed("native metadata cold") { assertEquals(1, ScheduledTasks.choices(context, scope).size) }
        timed("native metadata 20 repeats") { repeat(20) { assertEquals(1, ScheduledTasks.choices(context, scope).size) } }
        timed("native authentication cold") { assertEquals("SyntheticTiming1", NativeTaskPasswords.password(context, scope)) }
        timed("native authentication 10 repeats") { repeat(10) { assertEquals("SyntheticTiming1", NativeTaskPasswords.password(context, scope)) } }
    }
    @Test fun platformHashCompatibilityAndTiming() {
        assertTrue("Native password library was not loaded", NativePasswordKdf.available)
        val salt = ByteArray(16) { it.toByte() }
        for (password in listOf("CompatibilityPassword1", "pässவணக்கம்1🔐", "nul\u0000password1", "Long1".repeat(30))) {
            val expected = timed("platform PBKDF2") {
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).encoded
            }
            val actual = timed("native PBKDF2 including JNI") { PasswordKdf.derive(password, salt) }
            assertArrayEquals(expected, actual)
        }
    }
    @Test fun legacyVaultDiscovery() {
        val prefs = context.getSharedPreferences("install_lock", 0)
        val data = object : TaskData {
            override fun read() = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            override fun write(changes: Map<String, String>) = prefs.edit().also { edit ->
                changes.forEach { (k, v) -> edit.putString(k, v) }
            }.commit()
        }
        assertNull(TaskVault(data).install(listOf(NewTaskPassword(scope, "SyntheticTiming1")), false))
        val secrets = SecretStore(context)
        repeat(24) { secrets.add("Unrelated $it", "UnrelatedPassword$it", 1) }
        secrets.add("Matching credential", "SyntheticTiming1", 30)
        assertEquals(25, secrets.getAll().size)
        if (InstrumentationRegistry.getArguments().getString("profileBaseline") == "true") {
            val salt = java.util.Base64.getDecoder().decode(prefs.getString("task.${scope.id}.salt", ""))
            val expected = java.util.Base64.getDecoder().decode(prefs.getString("task.${scope.id}.hash", ""))
            timed("platform legacy verification 25 entries") {
                val matches = secrets.getAll().filter {
                    val actual = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(javax.crypto.spec.PBEKeySpec(it.value.toCharArray(), salt, 120_000, 256)).encoded
                    java.security.MessageDigest.isEqual(expected, actual)
                }
                assertEquals(1, matches.size)
            }
        }
        timed("legacy discovery 25 entries") {
            assertEquals(30, ScheduledTasks.choices(context, scope).single().delayMinutes)
        }
        timed("metadata after migration") { assertEquals(1, ScheduledTasks.choices(context, scope).size) }
    }
    @Test fun liveMetadataReadOnlyWhenExplicitlyRequested() {
        val app = InstrumentationRegistry.getArguments().getString("profileNativeApp")
        assumeTrue(app != null)
        val liveScope = TaskScope(TaskKind.APP_LIMIT, app!!)
        assumeTrue(NativeTaskPasswords.hasSavedPassword(base, liveScope))
        // Already-native metadata has no decryption, migration, authentication or writes.
        timed("live already-native metadata 20 repeats") {
            repeat(20) { assertEquals(1, ScheduledTasks.choices(base, liveScope).size) }
        }
        timed("live existing native credential read and verification") {
            assertTrue("Existing credential must still authenticate", NativeTaskPasswords.password(base, liveScope) != null)
        }
    }
    @After fun clean() {
        names.forEach { base.deleteSharedPreferences(prefix + it) }
    }
}
