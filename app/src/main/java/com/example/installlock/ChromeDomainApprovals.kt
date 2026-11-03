package com.example.installlock

/** Load-time policy is a superset; the ordered rules still decide the native URL.
 * Chrome cannot express a domain-contains match. Add only verified observed hosts
 * for those rules, never a wildcard that would open unrelated websites. */
internal class ChromeDomainApprovals(private val rules: List<DomainRule>) {
    private val engine = DomainRules(rules)
    private val allows = rules.filter { it.action == DomainAction.ALLOW }

    fun policyPatterns(observedHosts: Set<String> = emptySet()): List<String> =
        (allows.mapNotNull { rule ->
            when (rule.type) {
                DomainMatchType.SUFFIX -> rule.value
                DomainMatchType.EXACT -> ".${rule.value}"
                DomainMatchType.CONTAINS -> null
            }
        } + observedHosts.mapNotNull { DomainRules.normalizeHost(it) }
            .filter { needsObservedHost(it) }.map { ".$it" }).distinct()

    fun needsObservedHost(host: String): Boolean =
        engine.match(host)?.rule?.let { it.action == DomainAction.ALLOW && it.type == DomainMatchType.CONTAINS } == true &&
            allows.none { rule -> when (rule.type) {
                DomainMatchType.SUFFIX -> host == rule.value || host.endsWith(".${rule.value}")
                DomainMatchType.EXACT -> host == rule.value
                DomainMatchType.CONTAINS -> false
            } }
}
