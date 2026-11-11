package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BankingAppStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "banking_list_test_${System.nanoTime()}_"
    private fun freshContext() = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(prefix + name, mode)
    }
    private fun pkg(name: String, installed: Long = 100) = PackageInfo().apply {
        packageName = name; firstInstallTime = installed; lastUpdateTime = installed
    }
    @After fun cleanup() {
        for (name in listOf("banking_list", "automatic_banking", "play_categories"))
            freshContext().getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun legacySelectionStaysReadyButNewSelectionPersistsItsWaitAfterRemoval() {
        val c = freshContext()
        val settings = c.getSharedPreferences("automatic_banking", Context.MODE_PRIVATE)
        val apps = listOf(pkg("test.old.bank"), pkg("test.new.bank"))
        settings.edit().putStringSet("banks", setOf("test.old.bank")).putBoolean("detectFinance", false).commit()
        BankingAppStore.reconcile(c, apps)
        assertTrue(BankingAppStore.waits(c).isEmpty())
        settings.edit().putStringSet("banks", setOf("test.old.bank", "test.new.bank")).commit()
        BankingAppStore.reconcile(c, apps)
        val saved = c.getSharedPreferences("banking_list", Context.MODE_PRIVATE).getString("entry.test.new.bank", null)
        assertNotNull(saved)
        assertTrue(BankingAppStore.waits(freshContext()).getValue("test.new.bank") > System.currentTimeMillis() + 14 * 60_000)
        settings.edit().putStringSet("banks", setOf("test.old.bank")).commit()
        BankingAppStore.reconcile(freshContext(), apps)
        assertFalse("test.new.bank" in BankingAppStore.targets(c))
        assertTrue("test.new.bank" in BankingAppStore.waits(c))
        assertEquals(saved, c.getSharedPreferences("banking_list", Context.MODE_PRIVATE).getString("entry.test.new.bank", null))
    }

    @Test fun firstAdditionAfterEmptyMigrationGetsTheFullWait() {
        val c = freshContext()
        val settings = c.getSharedPreferences("automatic_banking", Context.MODE_PRIVATE)
        settings.edit().putBoolean("detectFinance", false).commit()
        val app = pkg("test.first.bank")
        BankingAppStore.reconcile(c, listOf(app))
        settings.edit().putStringSet("banks", setOf(app.packageName)).commit()
        BankingAppStore.reconcile(c, listOf(app))
        assertTrue(BankingAppStore.waits(c).getValue(app.packageName) > System.currentTimeMillis() + 14 * 60_000)
    }

    @Test fun existingFinanceEligibilitySurvivesAnOrdinaryAppUpdateDuringMigration() {
        val c = freshContext()
        val app = pkg("test.known.finance").apply { lastUpdateTime = 200 }
        c.getSharedPreferences("play_categories", Context.MODE_PRIVATE).edit()
            .putString(app.packageName, PlayCategoryRecord(100, 100, "FINANCE", 1000).encode()).commit()
        BankingAppStore.reconcile(c, listOf(app))
        c.getSharedPreferences("automatic_banking", Context.MODE_PRIVATE).edit()
            .putStringSet("banks", setOf(app.packageName)).commit()
        BankingAppStore.reconcile(c, listOf(app))
        assertFalse(app.packageName in BankingAppStore.waits(c))
    }
}
