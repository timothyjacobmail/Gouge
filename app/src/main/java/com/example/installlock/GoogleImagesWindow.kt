package com.example.installlock

/** Elapsed time prevents wall-clock edits extending access. Reboot ends the window. */
internal data class GoogleImagesWindow(val until: Long = 0, val boot: Int = -1, val duration: Long = 0) {
    fun remaining(now: WatchdogClock): Long =
        if (now.boot == boot) (until - now.elapsed).coerceIn(0, duration.coerceAtLeast(0)) else 0

    fun start(minutes: Int, cap: Int, now: WatchdogClock): GoogleImagesWindow? {
        if (minutes !in 1..cap.coerceIn(1, 10080) || remaining(now) > 0) return null
        val length = minutes * 60_000L
        return GoogleImagesWindow(now.elapsed + length, now.boot, length)
    }

    companion object {
        private val imageHosts = setOf("encrypted-tbn0.gstatic.com", "encrypted-tbn1.gstatic.com",
            "encrypted-tbn2.gstatic.com", "encrypted-tbn3.gstatic.com", "ytimg.com", "ggpht.com", "googleusercontent.com")

        fun isImageHost(host: String): Boolean = imageHosts.any { host == it || host.endsWith(".$it") }

        fun isBlocked(host: String, blockedDomains: Set<String>, temporaryAccess: Boolean,
                      rules: DomainRules): Boolean = blockedDomains.any { domain ->
            (host == domain || host.endsWith(".$domain")) && !(temporaryAccess && domain in imageHosts)
        } || rules.match(host)?.rule?.action == DomainAction.BLOCK
    }
}
