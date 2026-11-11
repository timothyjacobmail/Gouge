package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NativeTaskPasswordsTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("native_task_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == "install_lock") { "New native passwords must not read the separate secret holder" }
            return prefs
        }
    }
    private val scope = TaskScope(TaskKind.REDDIT_ALLOW)
    @Test fun repeatedNativeChecksRemainAuthenticatedAndReportTiming() {
        assertNull(TaskPasswords.vault(context).install(listOf(NewTaskPassword(scope, "TimingPassword1")), false))
        val start = android.os.SystemClock.elapsedRealtime()
        assertEquals("TimingPassword1", NativeTaskPasswords.password(context, scope))
        val cold = android.os.SystemClock.elapsedRealtime() - start
        val warmStart = android.os.SystemClock.elapsedRealtime()
        repeat(10) { assertEquals("TimingPassword1", NativeTaskPasswords.password(context, scope)) }
        android.util.Log.i("NativePasswordTiming", "First verification: $cold ms; 10 repeated decrypt/checks: ${android.os.SystemClock.elapsedRealtime() - warmStart} ms")
        assertFalse(TaskPasswords.check(context, scope, "WrongPassword1"))
    }
    @Test fun taskListingReadsOnlyMetadataEvenWhenCiphertextIsUnavailable() {
        assertNull(TaskPasswords.vault(context).install(listOf(NewTaskPassword(scope, "NativePassword1")), false))
        prefs.edit().putString("task.${scope.id}.secret", "unreadable ciphertext").commit()
        val start = android.os.SystemClock.elapsedRealtime()
        repeat(10) { assertEquals(1, ScheduledTasks.choices(context, scope).size) }
        android.util.Log.i("NativePasswordTiming", "10 metadata listings: ${android.os.SystemClock.elapsedRealtime() - start} ms")
        assertNull(NativeTaskPasswords.password(context, scope))
    }
    @After fun clean() { prefs.edit().clear().commit() }

    @Test fun setupStoresEncryptedPasswordAndTaskIsImmediatelyAvailableWithoutASecretHolder() {
        assertNull(TaskPasswords.vault(context).install(listOf(NewTaskPassword(scope, "NativePassword1")), false))
        assertEquals("NativePassword1", NativeTaskPasswords.password(context, scope))
        assertFalse(prefs.getString("task.${scope.id}.secret", "")!!.contains("NativePassword1"))
        assertEquals(1, ScheduledTasks.choices(context, scope).size)
        assertEquals(15, ScheduledTasks.choices(context, scope).single().delayMinutes)
    }

    @Test fun oldHashNeedsItsCurrentPasswordAndChangingPasswordInvalidatesPendingReferences() {
        val raw = object : TaskData {
            override fun read() = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            override fun write(changes: Map<String, String>): Boolean = prefs.edit().also { edit ->
                changes.forEach { (k, v) -> edit.putString(k, v) }
            }.commit()
        }
        assertNull(TaskVault(raw).install(listOf(NewTaskPassword(scope, "LegacyPassword1")), false))
        assertNull(NativeTaskPasswords.password(context, scope))
        assertFalse(NativeTaskPasswords.attach(context, scope, "WrongPassword1"))
        assertTrue(NativeTaskPasswords.attach(context, scope, "LegacyPassword1", 30))
        val reference = NativeTaskPasswords.reference(context, scope)
        assertEquals(30, NativeTaskPasswords.delay(context, scope))
        assertNull(TaskPasswords.change(context, scope, "LegacyPassword1", "ChangedPassword2", true))
        assertNotEquals(reference, NativeTaskPasswords.reference(context, scope))
        assertEquals("ChangedPassword2", NativeTaskPasswords.password(context, scope))
        assertEquals(30, NativeTaskPasswords.delay(context, scope))
    }
}
