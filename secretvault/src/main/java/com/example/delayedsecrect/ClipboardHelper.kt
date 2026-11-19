package com.example.delayedsecrect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

fun copySecretToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("secret", value)

    clip.description.extras = PersistableBundle().apply {
        // The literal key also flags sensitive clips on Android versions before API 33.
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }

    clipboard.setPrimaryClip(clip)
}
