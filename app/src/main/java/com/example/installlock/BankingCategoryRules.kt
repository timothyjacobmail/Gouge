package com.example.installlock

internal object BankingCategoryRules {
    fun financeApps(categories: Map<String, PlayInstallCategory?>, excluded: Set<String>): Set<String> =
        categories.filterValues { it == PlayInstallCategory.FINANCE }.keys - excluded

    fun targets(manual: Set<String>, finance: Set<String>, detectFinance: Boolean): Set<String> =
        manual + (if (detectFinance) finance else emptySet())
}
