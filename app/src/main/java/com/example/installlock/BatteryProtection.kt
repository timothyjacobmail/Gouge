package com.example.installlock

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

internal object BatteryProtection {
    fun exempt(c: Context) = c.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(c.packageName)

    fun request(activity: Activity) {
        if (exempt(activity)) return
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${activity.packageName}")))
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
