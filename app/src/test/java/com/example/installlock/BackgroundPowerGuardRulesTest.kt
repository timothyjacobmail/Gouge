package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class BackgroundPowerGuardRulesTest {
    private fun vivo(label: String) = PowerSettingScreen("com.iqoo.powersaving", listOf(
        PowerSettingNode("com.iqoo.powersaving:id/app_name", label),
        PowerSettingNode("com.iqoo.powersaving:id/all_opt", ""),
        PowerSettingNode("com.iqoo.powersaving:id/all_vos_button_opt_restrict", "")))

    @Test fun installLockVivoEditorIsProtected() {
        assertTrue(BackgroundPowerGuardRules.protects(vivo("Install Lock"), "Install Lock"))
    }
    @Test fun anotherAppsVivoEditorRemainsAvailable() {
        assertFalse(BackgroundPowerGuardRules.protects(vivo("Calculator"), "Install Lock"))
        assertFalse(BackgroundPowerGuardRules.protects(vivo("Install Lock Helper"), "Install Lock"))
    }
    @Test fun mentioningInstallLockInAListDoesNotLockThePage() {
        val screen = vivo("Calculator").let { it.copy(nodes = it.nodes +
            PowerSettingNode("android:id/title", "Install Lock")) }
        assertFalse(BackgroundPowerGuardRules.protects(screen, "Install Lock"))
    }
    @Test fun ownBatteryUsageHistoryAndAppInfoStayAvailable() {
        assertFalse(BackgroundPowerGuardRules.protects(PowerSettingScreen("com.iqoo.powersaving",
            listOf(PowerSettingNode("com.iqoo.powersaving:id/app_name", "Install Lock"),
                PowerSettingNode("com.iqoo.powersaving:id/title", "Background power control"))), "Install Lock"))
        assertFalse(BackgroundPowerGuardRules.protects(PowerSettingScreen("com.android.settings",
            listOf(PowerSettingNode("com.android.settings:id/entity_header_title", "Install Lock"))), "Install Lock"))
    }
    @Test fun androidOptimizationDialogIsProtectedOnlyForInstallLock() {
        fun dialog(label: String) = PowerSettingScreen("com.android.settings", listOf(
            PowerSettingNode("android:id/alertTitle", label), PowerSettingNode("android:id/text1", "Optimize"),
            PowerSettingNode("android:id/text1", "Don't optimize")))
        assertTrue(BackgroundPowerGuardRules.protects(dialog("Install Lock"), "Install Lock"))
        assertFalse(BackgroundPowerGuardRules.protects(dialog("Calculator"), "Install Lock"))
    }
    @Test fun otherPackagesCannotTriggerTheGuardWithCopiedLabels() {
        assertFalse(BackgroundPowerGuardRules.protects(vivo("Install Lock").copy(packageName = "com.example.app"),
            "Install Lock"))
    }
    @Test fun displayNameMatchingIgnoresOnlyWhitespaceAndCase() {
        assertTrue(BackgroundPowerGuardRules.protects(vivo("  INSTALL   LOCK "), "Install Lock"))
        assertFalse(BackgroundPowerGuardRules.protects(vivo("InstallLock"), "Install Lock"))
    }
}
