package com.example.installlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class DnsFilterService : VpnService() {

    companion object {
        const val TARGET_PKG = BrowserPackages.GOOGLE_APP
        private const val CLIENT_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"
        private val DNS_BYTES = byteArrayOf(10, 111, 222.toByte(), 2)
        @Volatile private var activeService: DnsFilterService? = null
        private val stateListeners = CopyOnWriteArraySet<() -> Unit>()
        private val policyWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "Gouge-VPN-policy") }

        internal fun addStateListener(listener: () -> Unit) { stateListeners.add(listener) }
        internal fun removeStateListener(listener: () -> Unit) { stateListeners.remove(listener) }

        /** Permission alone or another VPN is insufficient: our DNS reader must be alive. */
        fun isReady(context: Context): Boolean = try {
            activeService?.hasLiveTunnel() == true && prepare(context) == null
        } catch (_: RuntimeException) { false }

        internal fun disconnect() { activeService?.closeTunnel() }
        internal fun refreshBetaScope() { activeService?.mainHandler?.post { activeService?.applyBetaScope() } }
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var reader: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val work = DnsWorkQueue()
    private val ruleCache = ParsedValueCache<List<DomainRule>, DomainRules>(::DomainRules)
    private val upstreamSockets = mutableSetOf<DatagramSocket>()
    @Volatile private var running = false
    private var appliedScope = emptySet<String>()
    private fun betaScope(): Set<String> = if (BetaStore.enabled(this) && BetaStore.config(this).on("dnsAll")) setOf("*") else
        setOf(TARGET_PKG) + if (BetaStore.enabled(this) && BetaStore.config(this).on("dnsScope")) BetaStore.config(this).dnsApps else emptySet()
    private fun applyBetaScope() { if (hasLiveTunnel() && betaScope() != appliedScope) { closeTunnel(); if (!startFilter()) stopSelf() } }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "google_vpn", "Google VPN filter", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "google_vpn")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Gouge Google VPN")
            .setContentText("Required for Google access. Keyword protection also applies.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(2, notification)
        EnforcementRecovery.arm(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tun != null && !hasLiveTunnel()) closeTunnel()
        if (tun == null && !startFilter()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Android may call this off the main thread, including when another VPN takes over.
        closeTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        work.shutdownNow()
        super.onDestroy()
    }

    @Synchronized
    private fun hasLiveTunnel(): Boolean = running && tun?.fileDescriptor?.valid() == true && reader?.isAlive == true

    /** Ignore an old reader finishing after a replacement connection has started. */
    @Synchronized
    private fun closeTunnel(expected: ParcelFileDescriptor? = null): Boolean {
        if (expected != null && tun !== expected) return false
        running = false
        val old = tun
        tun = null
        reader?.interrupt()
        reader = null
        work.clear()
        upstreamSockets.forEach { it.close() }
        upstreamSockets.clear()
        if (activeService === this) activeService = null
        try { old?.close() } catch (_: Exception) { }
        notifyPolicy()
        return true
    }

    private fun notifyPolicy() {
        mainHandler.post { stateListeners.forEach { it() } }
        val app = applicationContext
        policyWorker.execute {
            try { Policy.sweep(app) }
            catch (error: RuntimeException) {
                android.util.Log.e("Gouge", "VPN policy refresh will retry through protection service", error)
            }
        }
    }

    @Synchronized
    private fun startFilter(): Boolean {
        return try {
            val scope = betaScope()
            val builder = Builder()
                .setSession("Gouge DNS filter")
                .setMtu(1500)
                .addAddress(CLIENT_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32)               // only DNS goes through us
                .setBlocking(true)
            // Google is always retained; missing optional packages must not tear down its filter.
            if ("*" in scope) builder.addDisallowedApplication(packageName)
            else scope.forEach { pkg -> try { builder.addAllowedApplication(pkg) } catch (e: android.content.pm.PackageManager.NameNotFoundException) { if (pkg == TARGET_PKG) throw e } }
            val fd = builder.establish() ?: run { notifyPolicy(); return false }
            appliedScope = scope
            tun = fd
            running = true
            reader = Thread({ loop(fd) }, "InstallLock-DNS").also { it.start() }
            activeService = this
            notifyPolicy()
            LockService.ensureStarted(this)
            true
        } catch (_: Exception) {
            closeTunnel()
            false
        }
    }

    private fun loop(fd: ParcelFileDescriptor) {
        try {
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val buf = ByteArray(32767)
            while (running && tun === fd) {
                val n = input.read(buf)
                if (!running || tun !== fd) break
                if (n < 0) break
                val request = DnsPacket.request(buf, n) ?: continue
                val parsed = parseQuestion(request.query)
                val blocked = parsed != null && isBlocked(parsed.first)
                work.execute(blocked) {
                    if (running && tun === fd) handle(fd, output, request.query, request.clientIp,
                        request.clientPort, parsed, blocked)
                }
            }
        } catch (_: Exception) { }
        finally {
            if (closeTunnel(fd)) mainHandler.post { if (tun == null) stopSelf() }
        }
    }

    private fun handle(fd: ParcelFileDescriptor, out: FileOutputStream, query: ByteArray, clientIp: ByteArray,
                       clientPort: Int, parsed: Pair<String, Int>?, blocked: Boolean) {
        try {
            // Queued lookups must also see rules changed while they were waiting.
            val deny = blocked || (parsed != null && isBlocked(parsed.first))
            if (deny && parsed != null) {
                reply(fd, out, clientIp, clientPort, nxdomain(query, parsed.second))
                DnsLog.add(parsed.first, true)
                return
            }
            if (parsed != null) DnsLog.add(parsed.first, false)
            val answer = forward(fd, query) ?: return
            // A temporary window or a rule can change while the upstream socket is waiting.
            if (parsed != null && isBlocked(parsed.first)) {
                reply(fd, out, clientIp, clientPort, nxdomain(query, parsed.second))
                DnsLog.add(parsed.first, true)
            } else reply(fd, out, clientIp, clientPort, answer)
        } catch (_: Exception) { }
    }

    private fun isBlocked(host: String): Boolean =
        BetaStore.blocks(this, host) || GoogleImagesWindow.isBlocked(host, Store.blockedDomains(this),
            GoogleImagesAccess.bypassesImageBlock(this, host), ruleCache.get(Store.domainRules(this)))

    /** Returns (lowercase name, offset just past the question) or null if malformed. */
    private fun parseQuestion(q: ByteArray): Pair<String, Int>? {
        var i = 12
        val sb = StringBuilder()
        while (i < q.size) {
            val len = q[i].toInt() and 0xFF
            if (len == 0) {
                val end = i + 1 + 4
                return if (end <= q.size) sb.toString().lowercase() to end else null
            }
            if (len and 0xC0 != 0 || i + 1 + len > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(q, i + 1, len, Charsets.US_ASCII))
            i += len + 1
        }
        return null
    }

    /** "That name doesn't exist." */
    private fun nxdomain(query: ByteArray, qEnd: Int): ByteArray {
        val r = query.copyOfRange(0, qEnd)
        r[2] = 0x81.toByte()                 // response, recursion desired
        r[3] = 0x83.toByte()                 // recursion available, NXDOMAIN
        for (i in 6..11) r[i] = 0            // no answer/authority/additional records
        return r
    }

    /** Ask the network's own DNS server, bypassing our VPN. */
    private fun forward(fd: ParcelFileDescriptor, query: ByteArray): ByteArray? {
        val server = upstream() ?: return null
        DatagramSocket().use { s ->
            synchronized(this) {
                if (!running || tun !== fd) return null
                upstreamSockets.add(s)
            }
            try {
                protect(s)
                s.soTimeout = Store.timingConfig(this).milliseconds(TimingOption.DNS_TIMEOUT).toInt()
                s.connect(InetSocketAddress(server, 53))
                s.send(DatagramPacket(query, query.size))
                val buf = ByteArray(4096)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                return buf.copyOf(p.length)
            } finally { synchronized(this) { upstreamSockets.remove(s) } }
        }
    }

    private fun upstream(): InetAddress? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val servers = cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
            .filter { it.hostAddress != DNS_ADDR }
        return servers.firstOrNull { it is Inet4Address } ?: servers.firstOrNull()
    }

    /** Wrap a DNS answer in an IPv4+UDP packet and write it back to the app. */
    private fun reply(fd: ParcelFileDescriptor, out: FileOutputStream, clientIp: ByteArray, clientPort: Int, dns: ByteArray) {
        val total = 20 + 8 + dns.size
        val p = ByteArray(total)
        p[0] = 0x45                                   // IPv4, 20-byte header
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40                                   // don't fragment
        p[8] = 64                                     // TTL
        p[9] = 17                                     // UDP
        DNS_BYTES.copyInto(p, 12)                     // from: our fake DNS server
        clientIp.copyInto(p, 16)                      // to: the app
        val ck = ipChecksum(p, 20)
        p[10] = (ck shr 8).toByte(); p[11] = ck.toByte()
        p[20] = 0; p[21] = 53                         // UDP source port 53
        p[22] = (clientPort shr 8).toByte(); p[23] = clientPort.toByte()
        val udpLen = 8 + dns.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        dns.copyInto(p, 28)                           // UDP checksum left 0 (allowed on IPv4)
        // Never hold the service lifecycle lock during blocking I/O: closeTunnel must
        // remain able to close the descriptor and unblock this write.
        synchronized(out) { if (running && tun === fd) out.write(p) }
    }

    private fun ipChecksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
