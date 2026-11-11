package com.example.installlock

import java.util.Locale

internal data class PowerSettingNode(val id: String, val text: String)
internal data class PowerSettingScreen(val packageName: String, val nodes: List<PowerSettingNode>)

/** The app heading must identify Gouge; a list row mentioning it is insufficient. */
internal object BackgroundPowerGuardRules {
    val settingsPackages = setOf("com.iqoo.powersaving", "com.vivo.abe", "com.android.settings")
    private fun normalized(text: String) = text.trim().replace('’', '\'')
        .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun protects(screen: PowerSettingScreen, appLabel: String): Boolean {
        if (screen.packageName !in settingsPackages) return false
        val ownHeading = screen.nodes.any {
            it.id.substringAfterLast('/') in setOf("app_name", "entity_header_title", "alertTitle") &&
                normalized(it.text) == normalized(appLabel)
        }
        if (!ownHeading) return false
        val ids = screen.nodes.map { it.id.substringAfterLast('/') }.toSet()
        if (screen.packageName != "com.android.settings") {
            return "all_opt" in ids && "all_vos_button_opt_restrict" in ids
        }
        val texts = screen.nodes.map { normalized(it.text) }.toSet()
        return ("optimize" in texts && "don't optimize" in texts) ||
            ("unrestricted" in texts && "restricted" in texts)
    }
}
