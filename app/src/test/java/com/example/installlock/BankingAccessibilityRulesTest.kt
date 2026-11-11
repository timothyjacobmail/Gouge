package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class BankingAccessibilityRulesTest {
    private val ownPackage = "com.example.installlock"
    private val browser = "$ownPackage/.BrowserKeywordService"
    private val guard = "$ownPackage/com.example.installlock.BackgroundPowerGuardService"
    private val other = "other.reader/.ScreenReader"

    @Test fun pauseRemovesBothOwnServicesAndPreservesOtherAccessibilityApps() {
        assertEquals(other, BankingAccessibilityRules.pause("$browser:$other:$guard", ownPackage))
        assertEquals("", BankingAccessibilityRules.pause("$browser:$guard", ownPackage))
        assertEquals(other, BankingAccessibilityRules.pause(other, ownPackage))
    }

    @Test fun ownershipRequiresExactPackageAndAComponent() {
        val similarlyNamed = "$ownPackage.helper/.Reader"
        assertEquals(similarlyNamed, BankingAccessibilityRules.pause("$browser:$similarlyNamed", ownPackage))
        assertFalse(BankingAccessibilityRules.owned(ownPackage, ownPackage))
        assertFalse(BankingAccessibilityRules.owned("$ownPackage/", ownPackage))
        assertTrue(BankingAccessibilityRules.owned(guard, ownPackage))
    }

    @Test fun restoreKeepsChangesToOtherServicesMadeDuringBanking() {
        val newlyEnabled = "new.reader/.Reader"
        val restored = BankingAccessibilityRules.restore(newlyEnabled, setOf(browser, guard, other), ownPackage)
        assertEquals("$newlyEnabled:$browser:$guard", restored)
        assertEquals(restored, BankingAccessibilityRules.restore(restored, setOf(browser, guard), ownPackage))
    }

    @Test fun restoreDoesNotEnableBrowserWhenItWasOffBeforeBanking() {
        assertEquals("$other:$guard", BankingAccessibilityRules.restore(other, setOf(guard), ownPackage))
        assertEquals(other, BankingAccessibilityRules.restore(other, emptySet(), ownPackage))
    }

    @Test fun bankingMarksMonitoringUnavailableEvenBeforeAndroidDisconnects() {
        assertFalse(BankingAccessibilityRules.monitoringReady(true, true, true))
        assertFalse(BankingAccessibilityRules.monitoringReady(false, false, true))
        assertFalse(BankingAccessibilityRules.monitoringReady(false, true, false))
        assertTrue(BankingAccessibilityRules.monitoringReady(false, true, true))
    }

    @Test fun bankingLockExplanationDoesNotAskToEnableServicesWhileBankIsOpen() {
        val reasons = AppLockReasons(categoryServiceMissing = true, keywordServiceMissing = true, bankingMode = true)
        assertTrue(reasons.shouldLock)
        assertEquals(1, reasons.descriptions().size)
        assertTrue(reasons.descriptions().single().contains("finish banking"))
        assertFalse(reasons.descriptions().single().contains("Android Accessibility settings"))
        // Ending banking alone cannot bypass other independently active locks.
        assertTrue(reasons.copy(bankingMode = false, categoryServiceMissing = false,
            keywordServiceMissing = false, appLock = true).shouldLock)
    }
}
