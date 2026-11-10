package com.example.installlock

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

internal data class UpdateState(val message: String, val busy: Boolean = false,
                                val update: AppUpdate? = null, val confirmation: Intent? = null)

internal object GitHubUpdates {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(UpdateState) -> Unit>()
    @Volatile var state = UpdateState("Check GitHub for a newer version of Install Lock.")
        private set
    private fun prefs(c: Context) = c.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    fun observe(listener: (UpdateState) -> Unit) { listeners.add(listener); listener(state) }
    fun remove(listener: (UpdateState) -> Unit) { listeners.remove(listener) }
    private fun publish(next: UpdateState) {
        state = next
        main.post { listeners.forEach { it(state) } }
    }

    @Suppress("DEPRECATION")
    fun installed(c: Context) = c.packageManager.getPackageInfo(c.packageName, 0)

    fun restore(c: Context) {
        if (state.busy || state.update != null || state.confirmation != null) return
        val saved = prefs(c)
        val target = saved.getLong("target", 0)
        if (target > 0 && installed(c).longVersionCode >= target) {
            saved.edit().remove("session").remove("target").putString("result", "Update installed successfully.").apply()
        }
        val sessionId = saved.getInt("session", -1)
        if (sessionId != -1) {
            if (c.packageManager.packageInstaller.getSessionInfo(sessionId) != null) {
                val confirmation = saved.getString("confirmation", null)?.let {
                    runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull()
                }
                publish(UpdateState(if (confirmation == null) "Android is finishing the update. Reopen this screen after installation."
                    else "Android needs your confirmation to finish installing.", busy = true, confirmation = confirmation))
                return
            }
            saved.edit().remove("session").remove("target").putString("result", "The previous installation did not finish. Check for updates to retry.").apply()
        }
        saved.getString("result", null)?.let { publish(UpdateState(it)) }
    }

    @Synchronized fun check(context: Context) {
        if (state.busy) return
        val c = context.applicationContext
        publish(UpdateState("Checking GitHub…", busy = true))
        executor.execute {
            try {
                val releases = JSONArray(fetchText("https://api.github.com/repos/${UpdateRules.REPOSITORY}/releases?per_page=20"))
                val candidates = mutableListOf<AppUpdate>()
                var manifests = 0
                var failures = 0
                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    if (release.optBoolean("draft")) continue
                    val assets = release.getJSONArray("assets")
                    val manifest = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name") == "update.json" } ?: continue
                    if (++manifests > 10) break
                    try {
                        val manifestUrl = manifest.getString("browser_download_url")
                        require(UpdateRules.validAssetUrl(manifestUrl))
                        val info = JSONObject(fetchText(manifestUrl, 64 * 1024))
                        require(info.getString("packageName") == c.packageName)
                        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                            .first { it.optString("name") == info.getString("apkName") }
                        val url = asset.getString("browser_download_url")
                        require(url.substringBeforeLast('/') == manifestUrl.substringBeforeLast('/'))
                        val update = AppUpdate(info.getLong("versionCode"), info.getString("versionName"),
                            info.getInt("minSdk"), url, info.getString("sha256"), asset.getLong("size"))
                        val githubDigest = asset.optString("digest")
                        require(githubDigest.isEmpty() || githubDigest == "null" || githubDigest.equals("sha256:${update.sha256}", true))
                        if (UpdateRules.eligible(update, installed(c).longVersionCode, Build.VERSION.SDK_INT)) candidates.add(update)
                    } catch (_: Exception) { failures++ }
                }
                val newest = candidates.maxByOrNull { it.versionCode }
                publish(when {
                    newest != null -> UpdateState("Version ${newest.versionName} is available (${newest.size / 1024 / 1024} MB).", update = newest)
                    failures > 0 -> UpdateState("Some release information could not be verified. Please check again later.")
                    else -> UpdateState("You’re up to date. No newer compatible update is published.")
                })
            } catch (e: Exception) { publish(UpdateState("Could not check for updates: ${e.message ?: "network error"}. Try again.")) }
        }
    }

    @Synchronized fun install(context: Context, update: AppUpdate) {
        if (state.busy || state.update != update) return
        val c = context.applicationContext
        publish(UpdateState("Downloading version ${update.versionName}…", busy = true))
        executor.execute {
            val file = File(c.cacheDir, "install-lock-update.apk")
            var sessionId = -1
            try {
                require(Policy.isOwner(c)) { "Device-owner setup is required to install updates." }
                require(!installsBlocked(c)) { "Wait until the active installation block ends, then retry." }
                require(UpdateRules.eligible(update, installed(c).longVersionCode, Build.VERSION.SDK_INT)) { "This update is no longer eligible." }
                file.outputStream().use { transfer(update.url, it, update.size) }
                require(file.length() == update.size) { "The download was incomplete." }
                require(file.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    hex(digest.digest()).equals(update.sha256, true)
                }) { "The download checksum does not match. Please retry." }
                publish(UpdateState("Verifying the update…", busy = true))
                verifyApk(c, file, update)
                require(!installsBlocked(c)) { "An installation block is active. Retry after it ends." }
                val installer = c.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(c.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                    val callback = Intent(c, UpdateInstallReceiver::class.java).setAction("${c.packageName}.UPDATE_RESULT")
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(c, sessionId, callback, flags)
                    check(prefs(c).edit().putInt("session", sessionId).putLong("target", update.versionCode).remove("result").remove("confirmation").commit())
                    publish(UpdateState("Installing version ${update.versionName}… The app may close briefly.", busy = true))
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                if (sessionId != -1) runCatching { c.packageManager.packageInstaller.abandonSession(sessionId) }
                prefs(c).edit().remove("session").remove("target").apply()
                publish(UpdateState("Could not install: ${e.message ?: "installation failed"}", update = update))
            } finally { file.delete() }
        }
    }

    fun result(c: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id == -1 || id != prefs(c).getInt("session", -1)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation == null) {
                runCatching { c.packageManager.packageInstaller.abandonSession(id) }
                prefs(c).edit().remove("session").remove("target").remove("confirmation").apply()
                publish(UpdateState("Android did not provide an installation confirmation. Check for updates to retry."))
                return
            }
            prefs(c).edit().putString("confirmation", confirmation.toUri(Intent.URI_INTENT_SCHEME)).commit()
            publish(UpdateState("Android needs your confirmation to finish installing.", busy = true, confirmation = confirmation))
            return
        }
        val message = if (status == PackageInstaller.STATUS_SUCCESS) "Update installed successfully."
            else "Android could not install the update: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"}. Check for updates to retry."
        prefs(c).edit().remove("session").remove("target").remove("confirmation").putString("result", message).commit()
        publish(UpdateState(message))
    }

    private fun installsBlocked(c: Context) = c.getSystemService(UserManager::class.java).hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)

    @Suppress("DEPRECATION")
    private fun verifyApk(c: Context, file: File, update: AppUpdate) {
        val pm = c.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Android could not read the downloaded APK.")
        val current = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners
            ?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
        UpdateRules.verifyIdentity(update, archive.packageName, c.packageName, archive.longVersionCode,
            current.longVersionCode, archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, Build.VERSION.SDK_INT,
            signers(archive), signers(current))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun fetchText(url: String, limit: Int = 2 * 1024 * 1024): String = ByteArrayOutputStream().use {
        transfer(url, it, limit.toLong()); it.toString(Charsets.UTF_8.name())
    }

    /** HTTPS only, bounded redirects, response size and time. No GitHub credentials are used. */
    private fun transfer(address: String, output: OutputStream, limit: Long) {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && (url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))) { "Untrusted download destination." }
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "InstallLock-Updater")
                connection.setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("Missing download redirect."))
                } else {
                    require(status == 200) { if (status == 403 || status == 429) "GitHub rate limit reached. Try again later." else "GitHub returned HTTP $status" }
                    require(connection.contentLengthLong <= limit) { "The download is too large." }
                    val deadline = System.nanoTime() + 10L * 60 * 1_000_000_000
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= limit) { "The download is too large." }
                            check(System.nanoTime() < deadline) { "The download timed out. Please retry." }
                            output.write(buffer, 0, n)
                        }
                    }
                    return
                }
            } finally { connection.disconnect() }
        }
        error("Too many download redirects.")
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { GitHubUpdates.result(context, intent) }
}
