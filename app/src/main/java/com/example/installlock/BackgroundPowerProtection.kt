package com.example.installlock

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

internal object BackgroundPowerProtection {
    fun canMaintainGuard(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED

    /** Preserve other accessibility services; protect only our own settings guard. */
    fun ensureEnabled(c: Context) {
        if (!Policy.isOwner(c) || !canMaintainGuard(c) || BankingMode.active(c)) return
        try {
            val component = ComponentName(c, BackgroundPowerGuardService::class.java)
            val before = Settings.Secure.getString(c.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter { it.isNotBlank() }
            if (before.none { ComponentName.unflattenFromString(it) == component }) {
                check(Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (before + component.flattenToString()).joinToString(":")))
            }
            if (Settings.Secure.getInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1)
                check(Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
        } catch (e: RuntimeException) { Log.e("InstallLock", "Could not maintain the background-power guard", e) }
    }

    fun summary(c: Context) = when {
        BankingMode.active(c) -> "Banking mode: accessibility and the background-power settings guard are paused."
        canMaintainGuard(c) && BackgroundPowerGuardService.connected ->
            "Install Lock's background-power setting is locked. Other apps' power settings remain available."
        else -> "Background-power setting lock needs administrator setup."
    }
}
