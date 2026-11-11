package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.example.delayedsecrect.RevealTimer
import com.example.delayedsecrect.SecretStore
import com.example.delayedsecrect.TimerStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LegacySecretLookupTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "legacy_lookup_${System.nanoTime()}_"
    private val names = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(name)
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    private val scope = TaskScope(TaskKind.APP_LIMIT, "example.video")
    private val password = "LegacyLookupPassword1"
    private fun setup() {
        val prefs = context.getSharedPreferences("install_lock", 0)
        val data = object : TaskData {
            override fun read() = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            override fun write(changes: Map<String, String>) = prefs.edit().also { edit ->
                changes.forEach { (k, v) -> edit.putString(k, v) }
            }.commit()
        }
        assertNull(TaskVault(data).install(listOf(NewTaskPassword(scope, password)), false))
        assertNull(ScheduledTasks.nativeChoice(context, scope))
        SecretStore(context).add("First match", password, 20)
        SecretStore(context).add("Unrelated", "DifferentPassword1", 1)
        SecretStore(context).add("Later duplicate", password, 60)
    }
    @Test fun migrationPreservesLongestDuplicateDelayAndAuthenticatesCurrentPassword() {
        setup()
        assertEquals(60, ScheduledTasks.choices(context, scope).single().delayMinutes)
        assertEquals(password, NativeTaskPasswords.password(context, scope))
        assertEquals(60, ScheduledTasks.nativeChoice(context, scope)!!.delayMinutes)
    }
    @Test fun laterHardLockedDuplicatePreventsAutomaticAndManualAttachment() {
        setup()
        val duplicate = SecretStore(context).getAll().last()
        RevealTimer(duplicate.id, 0, 0, TimerStore(context)).hardLock(60_000)
        assertTrue(ScheduledTasks.choices(context, scope).isEmpty())
        assertFalse(ScheduledTasks.attachExisting(context, scope, password))
        assertFalse(NativeTaskPasswords.hasSavedPassword(context, scope))
    }
    @Test fun manualAttachmentAuthenticatesTheSuppliedCandidateAndKeepsItsDelay() {
        setup()
        assertFalse(ScheduledTasks.attachExisting(context, scope, "WrongPassword1"))
        assertFalse(NativeTaskPasswords.hasSavedPassword(context, scope))
        assertTrue(ScheduledTasks.attachExisting(context, scope, password))
        assertEquals(60, NativeTaskPasswords.delay(context, scope))
    }
    @After fun clean() { names.forEach { base.deleteSharedPreferences(prefix + it) } }
}
