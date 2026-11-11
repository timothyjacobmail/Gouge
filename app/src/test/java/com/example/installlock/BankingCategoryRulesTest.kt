package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class BankingCategoryRulesTest {
    @Test fun onlyFinanceIsAutoEnrolledAcrossEveryPlayCategory() {
        val categories = PlayInstallCategory.entries.associate { it.storeId to it }
        assertEquals(setOf("FINANCE"), BankingCategoryRules.financeApps(categories, emptySet()))
        assertTrue(BankingCategoryRules.financeApps(mapOf("unknown.bank" to null), emptySet()).isEmpty())
    }

    @Test fun excludedMonitoredAndEssentialAppsCannotJoinViaCategoryMetadata() {
        val categories = mapOf("real.bank" to PlayInstallCategory.FINANCE,
            "browser" to PlayInstallCategory.FINANCE, "installlock" to PlayInstallCategory.FINANCE)
        assertEquals(setOf("real.bank"), BankingCategoryRules.financeApps(categories, setOf("browser", "installlock")))
    }

    @Test fun discoveryNeedsNoManualSelectionsAndManualFallbackSurvivesDisablingDiscovery() {
        val finance = setOf("bank", "wallet", "investing")
        assertEquals(finance, BankingCategoryRules.targets(emptySet(), finance, true))
        assertEquals(finance + "unlisted.bank", BankingCategoryRules.targets(setOf("unlisted.bank"), finance, true))
        assertEquals(setOf("unlisted.bank"), BankingCategoryRules.targets(setOf("unlisted.bank"), finance, false))
    }

    @Test fun cachedFinanceCategoryDoesNotCarryAcrossReinstallOrUpdate() {
        val saved = PlayCategoryRecord(100, 200, "FINANCE", 1000)
        assertEquals(PlayInstallCategory.FINANCE, PlayCategoryRules.cachedCategory(saved, 100, 200))
        assertNull(PlayCategoryRules.cachedCategory(saved, 300, 300))
        assertNull(PlayCategoryRules.cachedCategory(saved, 100, 400))
        assertNull(PlayCategoryRules.cachedCategory(null, 100, 200))
    }

    @Test fun oldRefreshDeadlineDoesNotDiscardVerifiedCategoryWhenOffline() {
        val expiredRefresh = PlayCategoryRecord(100, 200, "FINANCE", 0)
        assertEquals(PlayInstallCategory.FINANCE, PlayCategoryRules.cachedCategory(expiredRefresh, 100, 200))
        for (category in listOf("", "Finance", "FAKE_CATEGORY")) {
            assertNull(PlayCategoryRules.cachedCategory(expiredRefresh.copy(category = category), 100, 200))
        }
    }

    @Test fun categoryChangesRemoveFutureFinanceEnrollment() {
        val refreshed = PlayCategoryRecord(100, 200, "BUSINESS", 1000)
        val detected = BankingCategoryRules.financeApps(mapOf("bank" to
            PlayCategoryRules.cachedCategory(refreshed, 100, 200)), emptySet())
        assertTrue(BankingCategoryRules.targets(emptySet(), detected, true).isEmpty())
    }

    @Test fun bankingListingMatchDoesNotGrantSideloadedAppAnInstallWaitExemption() {
        val category = PlayCategoryRules.cachedCategory(PlayCategoryRecord(100, 200, "FINANCE", 1000), 100, 200)
        assertEquals(setOf("bank"), BankingCategoryRules.financeApps(mapOf("bank" to category), emptySet()))
        assertNull(PlayCategoryRules.exemption("com.android.shell", category?.storeId))
        assertNotNull(PlayCategoryRules.installWaitUntil(false, 100, 200, 900_000))
    }
}
