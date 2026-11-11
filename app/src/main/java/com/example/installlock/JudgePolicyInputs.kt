package com.example.installlock

/** Cheap value key: compare current rule inputs before constructing derived policy. */
internal data class JudgePolicyInputs(
    val approvedPatterns: List<String> = emptyList(),
    val blockedWords: Set<String> = emptySet(),
    val wordExceptions: Map<String, Set<String>> = emptyMap(),
    val pageExceptions: Set<String> = emptySet(),
    val extraLocks: Set<String> = emptySet(),
    val dnsBlocks: Set<String> = emptySet(),
    val domainRules: List<DomainRule> = emptyList(),
    val keywordFilterEnabled: Boolean = true,
    val leisureCatalog: Set<String> = emptySet(),
    val engineRevision: String = "personalized-v1"
) {
    fun compile() = JudgePreferences(approvedPatterns, blockedWords, wordExceptions, pageExceptions,
        extraLocks, dnsBlocks, domainRules, keywordFilterEnabled, leisureCatalog, engineRevision)
}
