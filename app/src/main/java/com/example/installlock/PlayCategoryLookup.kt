package com.example.installlock

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Shared listing metadata for install waits and banking discovery; network work stays off policy/UI threads. */
internal object PlayCategoryLookup {
    private class Request(var priority: Int, val sequence: Long, val work: (Request) -> Unit) : Runnable {
        @Volatile var notifyPolicy = false
        override fun run() = work(this)
    }
    private val sequence = AtomicLong()
    private val executor = ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(11, compareBy<Runnable> { (it as Request).priority }
            .thenBy { (it as Request).sequence }))
    private val pending = mutableMapOf<String, Request>()
    private val generation = AtomicLong()
    fun revision(): Long = generation.get()
    private val handler = Handler(Looper.getMainLooper())
    private const val RETRY_MS = 60_000L
    private const val REFRESH_MS = 24 * 60 * 60_000L
    private const val MAX_PAGE_BYTES = 4 * 1024 * 1024

    fun exemption(context: Context, pkg: PackageInfo,
                  selected: Set<String> = Store.immediatePlayCategories(context)): PlayInstallCategory? {
        val c = context.applicationContext
        if (selected.isEmpty()) return null
        val installer = installer(c, pkg.packageName)
        if (installer != PlayCategoryRules.PLAY_STORE) return null
        return PlayCategoryRules.exemption(installer, category(c, pkg, notifyPolicy = true)?.storeId, selected)
    }

    /** Any installed package can use its own Play listing for banking; install exemptions still
     * require the Play installer above. A listing match is not proof that an app rejects accessibility. */
    fun category(context: Context, pkg: PackageInfo, background: Boolean = false,
                 notifyPolicy: Boolean = false): PlayInstallCategory? {
        val c = context.applicationContext
        val prefs = c.getSharedPreferences("play_categories", Context.MODE_PRIVATE)
        val record = PlayCategoryRecord.decode(prefs.getString(pkg.packageName, null))
            ?.takeIf { it.matches(pkg.firstInstallTime, pkg.lastUpdateTime) }
        if (record == null || System.currentTimeMillis() >= record.retryAt) {
            synchronized(pending) {
                val priority = if (background) 1 else 0
                val existing = pending[pkg.packageName]
                if (existing != null) {
                    if (notifyPolicy) existing.notifyPolicy = true
                    // A foreground bank/new-install check jumps ahead of queued bulk discovery.
                    if (priority < existing.priority && executor.queue.remove(existing)) {
                        existing.priority = priority
                        executor.execute(existing)
                    }
                } else {
                    val request = Request(priority, sequence.getAndIncrement()) { request ->
                        try {
                            val category = fetchCategory(pkg.packageName)
                            val current = c.packageManager.getPackageInfo(pkg.packageName, 0)
                            // An uninstall/reinstall/update while the request was in flight invalidates it.
                            if (current.firstInstallTime == pkg.firstInstallTime && current.lastUpdateTime == pkg.lastUpdateTime) {
                                prefs.edit().putString(pkg.packageName, PlayCategoryRecord(pkg.firstInstallTime,
                                    pkg.lastUpdateTime, category ?: record?.category.orEmpty(), System.currentTimeMillis() +
                                            if (category == null) RETRY_MS else REFRESH_MS).encode()).apply()
                                generation.incrementAndGet()
                                // Bulk banking results do not each trigger an unrelated full policy sweep.
                                if (request.notifyPolicy) handler.post { Policy.sweep(c) }
                            }
                        } catch (_: Exception) {
                            // Package removed or unavailable. The next sweep will re-evaluate it.
                        } finally { synchronized(pending) { pending.remove(pkg.packageName) } }
                    }
                    request.notifyPolicy = notifyPolicy
                    pending[pkg.packageName] = request
                    executor.execute(request)
                }
            }
        }
        return PlayCategoryRules.cachedCategory(record, pkg.firstInstallTime, pkg.lastUpdateTime)
    }

    private fun installer(c: Context, packageName: String): String? =
        try { c.packageManager.getInstallSourceInfo(packageName).installingPackageName }
        catch (_: Exception) { null }

    private fun fetchCategory(packageName: String): String? {
        // Package names are the only information sent. No account, device ID or usage data is sent.
        if (!Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName)) return null
        // Let Google use the device's network region first; a US-only lookup can miss local banks.
        for (region in listOf("", "&gl=US")) {
            fetchListingCategory(packageName, region)?.let { return it }
        }
        return null
    }

    private fun fetchListingCategory(packageName: String, region: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("https://play.google.com/store/apps/details?id=$packageName&hl=en$region")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_PAGE_BYTES) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, MAX_PAGE_BYTES + 1 - output.size()))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            if (bytes.size > MAX_PAGE_BYTES) return null
            PlayStoreListing.category(bytes.toString(Charsets.UTF_8), packageName)
        } catch (_: Exception) {
            null
        } finally { connection?.disconnect() }
    }
}
