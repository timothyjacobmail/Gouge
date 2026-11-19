package com.example.installlock

internal object BrowserHandlerRules {
    private const val AUTOMATE = "com.llamalab.automate"

    // Automate exposes URLs to flows through this activity, including when it is disabled.
    fun isBrowserHandler(pkg: String, activity: String): Boolean =
        pkg != AUTOMATE || activity != "$AUTOMATE.ProcessTextActivity"

    // An older build may have remembered the text handler as a browser during disconnection.
    // Newly discovered browser activities and explicit client rules are evaluated separately.
    fun rememberedBrowsers(packages: Set<String>): Set<String> = packages - AUTOMATE
}
