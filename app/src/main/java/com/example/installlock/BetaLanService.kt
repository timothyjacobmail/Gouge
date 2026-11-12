package com.example.installlock

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.*
import java.util.Date
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

internal object BetaKeys {
    private const val ALIAS = "Gouge.beta.secrets.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
    }
    @Synchronized fun seal(id: String, value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(id.toByteArray())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    fun open(id: String, value: String): String {
        val raw = Base64.decode(value, Base64.NO_WRAP); require(raw.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, raw.copyOfRange(0,12))); cipher.updateAAD(id.toByteArray())
        return String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }
    fun random() = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
}

/** Explicitly started, TLS-pinned local LAN only. No cloud or Internet listener is provisioned. */
class BetaLanService : Service() {
    companion object {
        @Volatile private var active: BetaLanService? = null
        @Volatile internal var status = "Companion listener stopped"
        @Volatile internal var fingerprint = ""
        @Volatile internal var pairingCode = ""
        @Volatile internal var pairingUntil = 0L
        internal fun addresses(): String = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address && BetaLanProtocol.privateAddress(it) }.joinToString("\n") { it.hostAddress.orEmpty() }
        internal fun start(c: Context, pair: Boolean = false) { c.startForegroundService(Intent(c, BetaLanService::class.java).putExtra("pair", pair)) }
    }
    private var server: SSLServerSocket? = null
    private val clients = ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4))
    private val sockets = java.util.concurrent.CopyOnWriteArraySet<SSLSocket>()
    private var attempts = 0
    private var retryAt = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val n = getSystemService(NotificationManager::class.java)
        n.createNotificationChannel(NotificationChannel("beta_companion", "Beta local companion", NotificationManager.IMPORTANCE_LOW))
        startForeground(31, Notification.Builder(this, "beta_companion").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Gouge beta companion listening").setContentText("Authenticated local-network connections only")
            .setContentIntent(PendingIntent.getActivity(this, 8131, Intent(this, BetaCompanionActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Policy.isOwner(this) || !BetaStore.enabled(this) || !BetaStore.config(this).on("companion")) { stopSelf(); return START_NOT_STICKY }
        if (intent?.getBooleanExtra("pair", false) == true) {
            pairingCode = "%08d".format(SecureRandom().nextInt(100_000_000)); pairingUntil = SystemClock.elapsedRealtime() + 120_000; attempts = 0
        }
        if (active === this) return START_NOT_STICKY
        active = this
        Thread({ listen() }, "Gouge-beta-LAN").start()
        return START_NOT_STICKY
    }
    internal fun tlsContext(): SSLContext {
        val alias = "Gouge.beta.tls.ec.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                // Conscrypt supplies the TLS transcript digest to the Keystore signing bridge.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=Gouge local beta companion")).setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(0)).setCertificateNotAfter(Date(4_102_444_800_000L)).build())
        }.generateKeyPair()
        fingerprint = BetaLanProtocol.fingerprint(store.getCertificate(alias))
        val ownKey = object : X509ExtendedKeyManager() {
            override fun getClientAliases(type: String?, issuers: Array<out java.security.Principal>?) = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = null
            override fun getServerAliases(type: String?, issuers: Array<out java.security.Principal>?) = if (type == "EC") arrayOf(alias) else null
            override fun chooseServerAlias(type: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = if (type == "EC") alias else null
            override fun chooseEngineServerAlias(type: String?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) = if (type == "EC") alias else null
            override fun getCertificateChain(name: String?) = if (name == alias) arrayOf(store.getCertificate(alias) as java.security.cert.X509Certificate) else null
            override fun getPrivateKey(name: String?) = if (name == alias) store.getKey(alias, null) as PrivateKey else null
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(ownKey), null, SecureRandom()) }
    }
    private fun listen() {
        try {
            val socket = tlsContext().serverSocketFactory.createServerSocket() as SSLServerSocket
            socket.reuseAddress = true; socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            socket.bind(InetSocketAddress(BetaLanProtocol.PORT)); server = socket
            status = "Listening on local LAN port ${BetaLanProtocol.PORT}"
            while (!socket.isClosed) {
                val client = socket.accept() as SSLSocket
                if (!BetaLanProtocol.privateAddress(client.inetAddress)) { client.close(); continue }
                sockets.add(client)
                try { clients.execute { handle(client) } } catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(client); client.close() }
            }
        } catch (_: Exception) { status = "Companion listener stopped or unavailable" }
        finally { if (active === this) stopSelf() }
    }
    private fun handle(socket: SSLSocket) {
        socket.use { client ->
            try {
                client.soTimeout = 5000; client.startHandshake()
                val input = java.io.DataInputStream(client.inputStream); val output = java.io.DataOutputStream(client.outputStream)
                val request = JSONObject(BetaLanProtocol.read(input))
                val response = if (request.optString("op") == "pair") pair(request) else command(request)
                BetaLanProtocol.write(output, response.toString())
            } catch (_: Exception) { /* Authentication failures and keys never enter logs. */ }
            finally { sockets.remove(client) }
        }
    }
    @Synchronized private fun pair(r: JSONObject): JSONObject {
        val now = SystemClock.elapsedRealtime()
        check(now >= retryAt && now < pairingUntil && pairingCode.isNotEmpty()) { "Pairing closed" }
        attempts++
        if (attempts > 5) { retryAt = now + 300_000; pairingCode = ""; error("Pairing closed") }
        check(MessageDigest.isEqual(pairingCode.toByteArray(), r.optString("code").toByteArray())) { "Pairing rejected" }
        val id = UUID.randomUUID().toString(); val secret = BetaKeys.random()
        BetaStore.addPeer(this, id, BetaKeys.seal("peer:$id", secret))
        pairingUntil = 0; pairingCode = ""
        return JSONObject().put("id", id).put("secret", secret)
    }
    private fun command(r: JSONObject): JSONObject {
        check(BetaStore.enabled(this) && BetaStore.config(this).on("companion"))
        val id = r.getString("id"); val seq = r.getLong("sequence"); val time = r.getLong("time"); val body = r.getString("body")
        val peer = BetaStore.peer(this, id) ?: error("Unknown peer")
        val key = Base64.decode(BetaKeys.open("peer:$id", peer.first), Base64.NO_WRAP)
        check(BetaLanProtocol.verify(key, id, seq, time, body, Base64.decode(r.getString("mac"), Base64.NO_WRAP), System.currentTimeMillis()))
        check(BetaStore.consume(this, id, seq)) { "Replayed command" }
        val command = JSONObject(body)
        val c = BetaStore.config(this); val now = System.currentTimeMillis()
        when (command.getString("op")) {
            "status" -> Unit
            "lock", "unlock" -> {
                val profile = c.profiles.single { it.id == command.getString("profile") }
                check(!profile.committed(now)) { "A commitment or focus session is active" }
                val minutes = command.getInt("minutes")
                require(minutes in 1..minOf(120, Store.timingConfig(this)[TimingOption.MAX_UNLOCK]))
                val p = if (command.getString("op") == "unlock") profile.copy(overrideUntil = now + minutes * 60_000L)
                else profile.copy(enabled = true, focusStart = now, focusEnd = now + minutes * 60_000L, pomodoroMinutes = minutes, overrideUntil = 0)
                check(BetaStore.save(this, c.copy(profiles = c.profiles.map { if (it.id == p.id) p else it }))) { "Profile changed" }
                BetaActions.changed(this)
            }
            else -> error("Unsupported command")
        }
        return JSONObject().put("ok", true).put("profiles", JSONArray().apply { BetaStore.config(this@BetaLanService).profiles.forEach { p ->
            put(JSONObject().put("id", p.id).put("name", p.name).put("committed", p.committed(now)).put("minutes", BetaStore.usage(this@BetaLanService, p, "*", now).used / 60000))
        } })
    }
    override fun onDestroy() {
        active = null; pairingCode = ""; pairingUntil = 0; status = "Companion listener stopped"
        try { server?.close() } catch (_: Exception) {}; sockets.forEach { try { it.close() } catch (_: Exception) {} }; clients.shutdownNow()
        super.onDestroy()
    }
}

internal object BetaLanClient {
    fun send(host: String, pin: String, request: JSONObject, port: Int = BetaLanProtocol.PORT): JSONObject {
        val address = java.net.InetAddress.getByName(host)
        require(BetaLanProtocol.privateAddress(address)) { "Use the protected phone's local LAN address" }
        val expected = pin.replace(":", "").replace(" ", "").lowercase()
        require(Regex("[a-f0-9]{64}").matches(expected)) { "Enter its complete SHA-256 certificate fingerprint" }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty() || !MessageDigest.isEqual(BetaLanProtocol.fingerprint(chain[0]).toByteArray(), expected.toByteArray())) throw java.security.cert.CertificateException("Fingerprint mismatch")
            }
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return (ctx.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = 5000; socket.connect(InetSocketAddress(address, port), 5000); socket.startHandshake()
            BetaLanProtocol.write(java.io.DataOutputStream(socket.outputStream), request.toString())
            JSONObject(BetaLanProtocol.read(java.io.DataInputStream(socket.inputStream)))
        }
    }
}
