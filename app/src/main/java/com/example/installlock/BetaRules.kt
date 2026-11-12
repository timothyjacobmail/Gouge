package com.example.installlock

import java.net.URI
import java.time.Instant
import java.time.ZoneId

/** Pure beta policy. Zero means unlimited, never an implicit zero-minute allowance. */
internal data class BetaWindow(val days: Int = 127, val start: Int = 0, val end: Int = 1440) {
    init { require(days in 1..127 && start in 0..1439 && end in 0..1440 && start != end) }
    fun active(now: Long, zone: String): Boolean {
        val t = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone))
        val minute = t.hour * 60 + t.minute
        fun selected(day: Int) = days and (1 shl (day - 1)) != 0
        return if (end > start) selected(t.dayOfWeek.value) && minute in start until end
        else if (minute >= start) selected(t.dayOfWeek.value)
        else minute < end && selected(t.minusDays(1).dayOfWeek.value)
    }
}

internal data class BetaProfile(
    val id: String, val name: String, val apps: Set<String> = emptySet(),
    val sites: Set<String> = emptySet(), val enabled: Boolean = false,
    val zone: String = ZoneId.systemDefault().id, val windows: List<BetaWindow> = emptyList(),
    val block: Boolean = false, val dailyMinutes: Int = 0, val groupMinutes: Int = 0,
    val sessionMinutes: Int = 0, val breakMinutes: Int = 5, val launches: Int = 0,
    val shortMinutes: Int = 0, val shortCount: Int = 0, val gray: Boolean = false,
    val stretchMinutes: Int = 0, val commitment: Long = 0, val permanent: Boolean = false,
    val focusStart: Long = 0, val focusEnd: Long = 0, val pomodoroMinutes: Int = 25,
    val pomodoroBreak: Int = 5, val bonusDay: String = "", val bonusMinutes: Int = 0,
    val overrideUntil: Long = 0
) {
    init {
        require(Regex("[a-zA-Z0-9-]{1,64}").matches(id) && name.isNotBlank() && name.length <= 80)
        require(apps.size <= 200 && sites.size <= 200 && windows.size <= 28)
        require(apps.all { Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(it) })
        require(listOf(dailyMinutes, groupMinutes, sessionMinutes, shortMinutes, stretchMinutes, bonusMinutes).all { it in 0..10080 })
        require(breakMinutes in 1..1440 && launches in 0..100000 && shortCount in 0..100000)
        require(pomodoroMinutes in 1..240 && pomodoroBreak in 1..120)
        require(commitment >= 0 && focusStart >= 0 && focusEnd >= focusStart && overrideUntil >= 0)
        ZoneId.of(zone)
        require(sites.all { BetaPaths.valid(it) })
    }
    fun committed(now: Long) = permanent || commitment > now || focusEnd > now
    fun scheduled(now: Long) = enabled && (windows.isEmpty() || windows.any { it.active(now, zone) })
    fun focus(now: Long): Boolean = enabled && now in focusStart until focusEnd &&
        (now - focusStart) % ((pomodoroMinutes + pomodoroBreak) * 60_000L) < pomodoroMinutes * 60_000L
    fun day(now: Long) = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone)).toLocalDate().toString()
    /** A committed profile may gain targets or extend its deadline, never relax existing rules. */
    fun accepts(next: BetaProfile, now: Long): Boolean = !committed(now) ||
        next.apps.containsAll(apps) && next.sites.containsAll(sites) &&
        next.copy(name = name, apps = apps, sites = sites, commitment = commitment, permanent = permanent) == this &&
        next.commitment >= commitment && (!permanent || next.permanent)
}

internal data class BetaUsage(val used: Long = 0, val shorts: Long = 0, val count: Int = 0,
    val launches: Int = 0, val session: Long = 0, val pauseUntil: Long = 0)

internal object BetaAccounting {
    fun advance(p: BetaProfile, old: BetaUsage, last: Long, now: Long, elapsed: Long, short: Boolean, count: Boolean, launch: Boolean): BetaUsage {
        val delta = elapsed.coerceIn(0, 2000)
        val reset = old.pauseUntil in 1..now || now - last >= p.breakMinutes * 60_000L
        val session = (if (reset) 0 else old.session) + if (old.pauseUntil > now) 0 else delta
        val cap = listOf(p.sessionMinutes, p.stretchMinutes).filter { it > 0 }.minOrNull()
        val pause = if (old.pauseUntil > now) old.pauseUntil else if (cap != null && session >= cap * 60_000L) now + p.breakMinutes * 60_000L else 0
        return BetaUsage(old.used + delta, old.shorts + if (short) delta else 0,
            (old.count.toLong() + if (count) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            (old.launches.toLong() + if (launch) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), session, pause)
    }
}

internal object BetaRules {
    fun reasons(p: BetaProfile, pkg: String, now: Long, app: BetaUsage, group: BetaUsage): List<String> {
        if (pkg !in p.apps || !p.enabled) return emptyList()
        val focus = p.focus(now)
        if (!p.scheduled(now) && !focus) return emptyList()
        if (p.overrideUntil > now && !p.committed(now)) return emptyList()
        val bonus = if (p.bonusDay == p.day(now)) p.bonusMinutes * 60_000L else 0
        return buildList {
            if (focus) add("${p.name}: Pomodoro focus phase")
            if (!p.scheduled(now)) return@buildList
            if (p.block) add("${p.name}: scheduled beta lock")
            if (p.dailyMinutes > 0 && app.used >= p.dailyMinutes * 60_000L + bonus) add("${p.name}: daily app allowance used")
            if (p.groupMinutes > 0 && group.used >= p.groupMinutes * 60_000L + bonus) add("${p.name}: shared allowance used")
            if (p.launches > 0 && app.launches > p.launches) add("${p.name}: daily opening limit")
            if (group.pauseUntil > now) add("${p.name}: ${if (p.stretchMinutes > 0) "sip & stretch — " else ""}take a ${p.breakMinutes}-minute break")
        }
    }
    fun shortReason(p: BetaProfile, now: Long, group: BetaUsage): String? {
        if (!p.scheduled(now) || p.overrideUntil > now && !p.committed(now)) return null
        return when {
            p.shortMinutes > 0 && group.shorts >= p.shortMinutes * 60_000L -> "${p.name}: short-video time used"
            p.shortCount > 0 && group.count > p.shortCount -> "${p.name}: short-video count used"
            else -> null
        }
    }
}

internal object BetaPaths {
    fun valid(rule: String): Boolean = rule.length in 1..512 && !rule.contains('*') &&
        !rule.any { it.isWhitespace() } && parse(rule)?.let { it.userInfo == null && it.port == -1 && it.query == null && it.fragment == null } == true
    private fun parse(value: String): URI? = try {
        URI(if (value.contains("://")) value else "https://$value").normalize().takeIf {
            it.scheme in setOf("https", "http") && it.host?.contains('.') == true && it.port in -1..65535
        }
    } catch (_: Exception) { null }
    fun matches(rule: String, address: String): Boolean {
        val a = parse(rule) ?: return false
        val b = parse(address) ?: return false
        val host = a.host.lowercase()
        if (b.host.lowercase() != host && !b.host.lowercase().endsWith(".$host")) return false
        fun normalizedPath(uri: URI) = URI(null,null,uri.path.orEmpty(),null).normalize().path.orEmpty()
        val path = normalizedPath(a).trimEnd('/')
        val candidate = normalizedPath(b)
        return path.isEmpty() || candidate == path || candidate.startsWith("$path/")
    }
    val proxies = setOf("translate.google.com", "translate.googleusercontent.com", "translate.goog", "web.archive.org", "archive.ph", "archive.is")
    fun proxy(address: String): Boolean = address.trim().substringBefore(':').lowercase() in setOf("data", "blob", "file") ||
        proxies.any { matches(it, address) }
}

internal data class BetaUiRule(val pkg: String, val resource: String, val text: String = "", val hide: Boolean = true) {
    init {
        require(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(pkg))
        require(resource.startsWith("$pkg:id/") && resource.length <= 240 && resource.substringAfter(":id/").isNotEmpty())
        require(text.length <= 100)
    }
}

internal data class BetaConfig(val enabled: Boolean = false, val flags: Set<String> = emptySet(),
    val profiles: List<BetaProfile> = emptyList(), val ui: List<BetaUiRule> = emptyList(),
    val dnsApps: Set<String> = emptySet(), val lists: Map<String, Set<String>> = emptyMap(),
    val sentence: String = "I choose to use this time intentionally.", val revision: Long = 0) {
    init { require(profiles.size <= 40 && profiles.map { it.id }.distinct().size == profiles.size && ui.size <= 100)
        require(sentence.length in 8..240 && lists.size <= 20 && lists.values.sumOf { it.size } <= 50000)
        require(revision in 0 until Long.MAX_VALUE && dnsApps.size <= 200 && dnsApps.all { Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(it) }) }
    fun on(flag: String) = enabled && flag in flags
    fun accepts(next: BetaConfig, now: Long): Boolean = profiles.filter { it.committed(now) }.all { old ->
        next.enabled && next.profiles.find { it.id == old.id }?.let { old.accepts(it, now) } == true
    }
}
