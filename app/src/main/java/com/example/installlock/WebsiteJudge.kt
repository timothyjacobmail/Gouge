package com.example.installlock

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Fetches inert public HTML: no JavaScript, cookies, credentials, forms or WebView. */
internal class WebsiteJudge(private val fetch: (URI) -> Page = ::download,
                            private val leisureCatalog: Set<String> = emptySet(),
                            private val preferences: JudgePreferences = JudgePreferences(),
                            private val timeoutMillis: Long = 10_000) {
    data class Page(val status: Int, val html: String = "", val redirect: String? = null)

    fun judge(input: String): Judgement = BoundedWebCheck.run(timeoutMillis) { inspect(input) }
        ?: Judgement(JudgeDecision.UNVERIFIED, "The check timed out or could not finish. No approval was added. Try the site's public home page or use the normal delay.")

    private fun inspect(input: String): Judgement {
        val raw = runCatching { URI(input.trim()) }.getOrNull()
        val target = if (raw != null && GoogleShareResolver.isWrapper(raw)) GoogleShareResolver().resolve(input)
            ?: return Judgement(JudgeDecision.UNVERIFIED, "The Google link could not be resolved. Paste the destination website instead.")
            else input
        val original = JudgeRules.webUri(target)
            ?: return Judgement(JudgeDecision.UNVERIFIED, "Enter a public HTTPS website on its normal port.")
        if (JudgeRules.leisureHost(original.host, leisureCatalog))
            return Judgement(JudgeDecision.DELAY, "This leisure site keeps the normal whitelist delay.")
        if (JudgeRules.unsafeRoute(original.toASCIIString()))
            return Judgement(JudgeDecision.DELAY, "This leisure or unchecked redirect route keeps the normal delay.")
        preferences.veto(original.toASCIIString())?.let { return Judgement(JudgeDecision.DELAY, it) }
        // Fragments and one-time authentication parameters stay in memory for opening only.
        // Never send an authentication link to the classifier, including via redirect Location.
        val openAddress = runCatching { URI(if (target.contains("://")) target.trim() else "https://${target.trim()}").toASCIIString() }.getOrDefault("")
        JudgeRules.knownSignIn(openAddress, preferences)?.let { return it }
        var current = if (sensitive(original, target)) URI("https://${original.host}/") else original
        val visited = linkedSetOf<String>()
        val deadline = System.nanoTime() + 30_000_000_000L
        repeat(5) {
            if (JudgeRules.leisureHost(current.host, leisureCatalog) || JudgeRules.unsafeRoute(current.toASCIIString()))
                return Judgement(JudgeDecision.DELAY, "This leisure site or redirect route must use the normal whitelist delay.")
            preferences.veto(current.toASCIIString())?.let { return Judgement(JudgeDecision.DELAY, it) }
            if (!visited.add(current.toASCIIString()) || Thread.currentThread().isInterrupted || System.nanoTime() > deadline)
                return Judgement(JudgeDecision.UNVERIFIED, "The website could not be verified. Its delay is unchanged.")
            val page = try { fetch(current) } catch (_: Exception) {
                return Judgement(JudgeDecision.UNVERIFIED, "The public page could not be read (network, TLS, login or anti-bot restriction). Its delay is unchanged.")
            }
            when {
                page.status in setOf(301, 302, 303, 307, 308) -> {
                    val destination = runCatching { current.resolve(page.redirect ?: error("Missing redirect")).toString() }.getOrNull()
                    current = destination?.let { JudgeRules.webUri(it) }
                        ?: return Judgement(JudgeDecision.UNVERIFIED, "The redirect destination is not eligible for immediate access.")
                    if (sensitive(current, destination)) return Judgement(JudgeDecision.UNVERIFIED, "This destination requires sign-in. Judge did not follow its authentication link. Try its public home page.")
                }
                page.status == 200 -> {
                    val decision = JudgeRules.inspect(current.toASCIIString(), page.html, preferences)
                    // Only grant the final inspected host, never a shortener or intermediate host.
                    return if (decision.decision == JudgeDecision.IMMEDIATE && current.host == original.host)
                        decision.copy(address = openAddress) else decision
                }
                else -> return Judgement(JudgeDecision.UNVERIFIED, "The website returned HTTP ${page.status}. Its delay is unchanged.")
            }
        }
        return Judgement(JudgeDecision.UNVERIFIED, "Too many redirects. The normal delay still applies.")
    }

    companion object {
        private fun sensitive(uri: URI, original: String): Boolean =
            '#' in original || Regex("(?i)(?:^|/)(magic-link|login|signin|sign-in|oauth|callback|verify|reset-password)(?:/|$)").containsMatchIn(uri.path) ||
                uri.rawQuery.orEmpty().split('&').any {
                    val key = runCatching { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") }.getOrDefault("")
                    Regex("(?i)(.*token.*|code|state|ticket|password|email|otp|signature)").matches(key)
                }

        internal fun publicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address.map { it.toInt() and 255 }
            return if (bytes.size == 4) bytes[0] != 0 &&
                !(bytes[0] == 100 && bytes[1] in 64..127) &&
                !(bytes[0] == 192 && (bytes[1] == 0 || bytes[1] == 88 && bytes[2] == 99)) &&
                !(bytes[0] == 198 && (bytes[1] in 18..19 || bytes[1] == 51 && bytes[2] == 100)) &&
                !(bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113) && bytes[0] < 224
            else bytes.size == 16 && bytes[0] in 0x20..0x3f &&
                !(bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)) // Global IPv6, excluding documentation.
        }

        private fun download(uri: URI): Page {
            require(InetAddress.getAllByName(uri.host).let { it.isNotEmpty() && it.all(::publicAddress) })
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                connection.setRequestProperty("User-Agent", "InstallLock-Judge/1.0")
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) return Page(status, redirect = connection.getHeaderField("Location"))
                if (status != 200) return Page(status)
                val type = connection.contentType.orEmpty().lowercase(Locale.ROOT)
                require(type.startsWith("text/html") || type.startsWith("application/xhtml+xml"))
                require(connection.contentLengthLong <= 512 * 1024)
                val deadline = System.nanoTime() + 10_000_000_000L
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 512 * 1024 && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted)
                        output.write(buffer, 0, count)
                    }
                }
                val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1)
                    ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
                return Page(200, output.toString(charset.name()))
            } finally { connection.disconnect() }
        }
    }
}
