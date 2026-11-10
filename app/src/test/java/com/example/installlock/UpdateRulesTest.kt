package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class UpdateRulesTest {
    private val update = AppUpdate(6, "1.6", 30,
        "https://github.com/timothyjacobmail/Install_Lock/releases/download/v1.6-debug/install-lock-debug.apk",
        "ab".repeat(32), 41_000_000)

    @Test fun allowsNewerDebugReleasesOnSupportedAndroid() {
        assertTrue(UpdateRules.eligible(update, 5, 30))
        assertTrue(UpdateRules.eligible(update.copy(sha256 = update.sha256.uppercase()), 5, 36))
    }

    @Test fun rejectsReinstallsDowngradesAndIncompatibleAndroid() {
        assertFalse(UpdateRules.eligible(update, 6, 36))
        assertFalse(UpdateRules.eligible(update, 7, 36))
        assertFalse(UpdateRules.eligible(update, 5, 29))
        assertFalse(UpdateRules.eligible(update.copy(minSdk = 0), 5, 36))
    }

    @Test fun rejectsMissingOrMalformedIntegrityAndSizeMetadata() {
        for (hash in listOf("", "ab".repeat(31), "zz".repeat(32)))
            assertFalse(UpdateRules.eligible(update.copy(sha256 = hash), 5, 36))
        for (size in listOf(0L, -1L, UpdateRules.MAX_APK_BYTES + 1))
            assertFalse(UpdateRules.eligible(update.copy(size = size), 5, 36))
    }

    @Test fun onlyDownloadsReleaseAssetsFromThisRepository() {
        for (url in listOf(
            update.url.replace("https:", "http:"),
            update.url.replace("github.com", "github.com.evil.example"),
            update.url.replace("Install_Lock", "another-app"),
            update.url.replace("github.com/", "github.com:443/"),
            update.url.replace("github.com/", "name@github.com/"),
            update.url + "?download=elsewhere", update.url + "#fragment",
            "https://github.com/timothyjacobmail/Install_Lock/releases/download/../evil/app.apk",
            "not a URL")) {
            assertFalse(url, UpdateRules.validAssetUrl(url))
        }
    }

    private fun verify(packageName: String = "com.example.installlock", version: Long = 6,
                       installed: Long = 5, minSdk: Int = 30,
                       signers: Set<String> = setOf("original-key"),
                       installedSigners: Set<String> = setOf("original-key")) {
        UpdateRules.verifyIdentity(update, packageName, "com.example.installlock", version,
            installed, minSdk, 36, signers, installedSigners)
    }

    @Test fun acceptsOnlyTheAdvertisedUpgradeWithTheExistingSigningKey() { verify() }
    @Test(expected = IllegalArgumentException::class) fun rejectsAnotherApp() { verify(packageName = "another.app") }
    @Test(expected = IllegalArgumentException::class) fun rejectsAnApkWithAForgedAdvertisedVersion() { verify(version = 7) }
    @Test(expected = IllegalArgumentException::class) fun rechecksInstalledVersionBeforeInstall() { verify(installed = 6) }
    @Test(expected = IllegalArgumentException::class) fun rejectsChangedSigningKey() { verify(signers = setOf("new-key")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsignedApk() { verify(signers = emptySet()) }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingInstalledSigningIdentity() { verify(installedSigners = emptySet()) }
    @Test(expected = IllegalArgumentException::class) fun checksTheApksActualAndroidRequirement() { verify(minSdk = 37) }
}
