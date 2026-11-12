package com.example.installlock

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

internal object BetaSafety {
    @Volatile private var cached: Set<String>? = null
    fun essentials(c: Context): Set<String> = cached ?: buildSet {
        add(c.packageName); add("com.android.settings"); add("com.android.systemui")
        add("com.android.permissioncontroller"); add("com.google.android.permissioncontroller")
        c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName?.let { add(it) }
        c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { add(it) }
        c.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { add(it.packageName) }
    }.also { cached = it }
}
