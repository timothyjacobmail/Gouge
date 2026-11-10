package com.example.installlock

import java.net.URI

internal data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val url: String,
    val sha256: String,
    val size: Long
)

/** Fail closed before downloading or submitting an APK to Android. */
internal object UpdateRules {
    const val REPOSITORY = "timothyjacobmail/Install_Lock"
    const val MAX_APK_BYTES = 150L * 1024 * 1024

    fun validAssetUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.rawQuery == null && uri.fragment == null &&
            uri.rawPath.startsWith("/$REPOSITORY/releases/download/") &&
            uri.rawPath.removePrefix("/$REPOSITORY/releases/download/").split('/').let {
                it.size == 2 && it.all { part -> part.isNotBlank() && part !in setOf(".", "..") }
            }
    }.getOrDefault(false)

    fun eligible(update: AppUpdate, installed: Long, sdk: Int): Boolean =
        update.versionCode > installed && update.versionName.isNotBlank() &&
            update.minSdk in 1..sdk && update.size in 1..MAX_APK_BYTES &&
            update.sha256.matches(Regex("[a-fA-F0-9]{64}")) && validAssetUrl(update.url)

    fun verifyIdentity(update: AppUpdate, packageName: String, expectedPackage: String,
                       versionCode: Long, installed: Long, minSdk: Int, sdk: Int,
                       signers: Set<String>, installedSigners: Set<String>) {
        require(packageName == expectedPackage) { "The download is for a different app." }
        require(versionCode == update.versionCode && versionCode > installed) { "The APK is not the advertised newer version." }
        require(minSdk <= sdk) { "This update requires a newer Android version." }
        require(signers.isNotEmpty() && signers == installedSigners) { "The update was signed with a different key." }
    }
}
