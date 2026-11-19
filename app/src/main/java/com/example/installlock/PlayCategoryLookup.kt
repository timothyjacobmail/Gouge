package com.example.installlock

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import android.net.TrafficStats

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
    internal const val TRAFFIC_TAG = 0x475043

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
        val before = PlayCategoryRecord.decode(prefs.getString(pkg.packageName, null))
            ?.takeIf { it.matches(pkg.firstInstallTime, pkg.lastUpdateTime) }
        val record = before?.let { PlayLookupPolicy.migrate(it,System.currentTimeMillis()) }
        if (record != before) prefs.edit().putString(pkg.packageName,record!!.encode()).apply()
        if (PlayLookupPolicy.due(record,System.currentTimeMillis()) && PlayLookupNetwork.connected(c,background) &&
            PlayLookupNetwork.available(c,background)) {
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
                    val request = Request(priority, sequence.getAndIncrement()) work@{ request ->
                        try {
                            // Recheck at execution: queued bulk scans must not outlive their network allowance.
                            if (!PlayLookupNetwork.connected(c,request.priority > 0)) return@work
                            val result = fetchCategory(c,pkg.packageName,request.priority > 0) ?: return@work
                            val current = c.packageManager.getPackageInfo(pkg.packageName, 0)
                            // An uninstall/reinstall/update while the request was in flight invalidates it.
                            if (current.firstInstallTime == pkg.firstInstallTime && current.lastUpdateTime == pkg.lastUpdateTime) {
                                prefs.edit().putString(pkg.packageName, PlayLookupPolicy.completed(pkg.firstInstallTime,
                                    pkg.lastUpdateTime,record,result.category,System.currentTimeMillis(),result.unavailable).encode()).commit()
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

    private data class FetchResult(val category: String? = null, val unavailable: Boolean = false)
    private fun fetchCategory(c: Context, packageName: String, background: Boolean): FetchResult? {
        // Package names are the only information sent. No account, device ID or usage data is sent.
        if (!Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName)) return null
        // Try the device region first. Only a missing regional listing warrants one US fallback;
        // network/parse failures must not double every request.
        for (region in listOf("", "&gl=US")) {
            val result=fetchListingCategory(c,packageName,region,background) ?: return null
            if (!result.unavailable || region.isNotEmpty()) return result
        }
        return FetchResult(unavailable=true)
    }

    private fun fetchListingCategory(c: Context, packageName: String, region: String, background: Boolean): FetchResult? {
        val reservation=PlayLookupNetwork.reserve(c,background) ?: return null
        var connection: HttpURLConnection? = null
        var received=0L
        val previousTag=TrafficStats.getThreadStatsTag()
        var input: java.io.InputStream? = null
        return try {
            TrafficStats.setThreadStatsTag(TRAFFIC_TAG)
            connection = URL("https://play.google.com/store/apps/details?id=$packageName&hl=en$region")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Accept-Encoding","gzip")
            val status=connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) return FetchResult(unavailable=status in setOf(404,410))
            val wire=LimitedNetworkInput(connection.inputStream,reservation.limit) { received+=it }
            input=if (connection.contentEncoding.equals("gzip",true)) GZIPInputStream(wire) else wire
            val category=PlayListingReader.category(input) { html -> PlayStoreListing.category(html,packageName) }
            FetchResult(category)
        } catch (_: Exception) {
            FetchResult()
        } finally {
            // Disconnect before closing an incomplete body: HTTP keep-alive cleanup must not
            // silently drain the rest of the listing after we have found the metadata.
            connection?.disconnect()
            try { input?.close() } catch (_: Exception) {}
            TrafficStats.setThreadStatsTag(previousTag)
            PlayLookupNetwork.received(c,received,reservation)
        }
    }
}
