# App updates

Open **Settings & passwords → App updates**, or search for **App updates** on the dashboard. Tap **Check for updates**, then **Download and install** and confirm the version. A network connection is required. Checks and downloads run off the main thread, and remain active when the screen rotates or you navigate away while the process is running.

The updater checks the newest 20 public GitHub releases in `timothyjacobmail/Install_Lock`, including prereleases because this project distributes debug builds. It reads up to 10 `update.json` assets and chooses the highest compatible version code greater than the installed version. Older releases without metadata are skipped. It never installs automatically just because an update check found a newer version.

Downloads use HTTPS and a bounded GitHub redirect allowlist. The updater verifies the exact APK size and SHA-256 hash, package ID, actual APK version code, Android requirement, and the installed app's signing certificates. Android's package installer then verifies the APK and updates the existing package. App data and device-owner enrollment are preserved. The original signing key must be retained; an APK signed with another debug keystore is rejected.

Installation requires Install Lock to be device owner. Active installation restrictions are respected and never temporarily cleared. Android normally installs device-owner updates without a second system prompt; if confirmation is required, the updates screen offers **Continue Android installation**. Android may close the app during replacement; reopen it afterward. The existing package-replacement receiver restores enforcement. Interrupted network downloads can be retried, and temporary APKs are deleted after each attempt. This is an explicit, in-app update flow, not a periodic background updater.

## Publishing compatible updates

After building, install the QR library with `python -m pip install "qrcode[pil]"`, then run `python tools/prepare-update-release.py --tag YOUR_NEW_RELEASE_TAG`. This prepares the APK, `update.json`, provisioning JSON, QR image, and hashes under `build/update-release/YOUR_NEW_RELEASE_TAG`. Upload those files to that exact GitHub release tag.

1. Increment `versionCode` in `app/build.gradle.kts` for every update. Set the visible `versionName` as desired.
2. Build `:app:assembleDebug` using the original private debug keystore.
3. Create a new GitHub release with `install-lock-debug.apk` and an `update.json` asset with these fields:

```json
{
  "packageName": "com.example.installlock",
  "versionCode": 6,
  "versionName": "1.6",
  "minSdk": 30,
  "apkName": "install-lock-debug.apk",
  "sha256": "LOWERCASE_HEX_SHA256_OF_THE_APK"
}
```

The updater takes the APK download URL and byte count from the same release's GitHub asset metadata. It checks GitHub's asset digest against `update.json` when a digest is provided. The `sha256` field uses hexadecimal rather than the URL-safe Base64 used for provisioning QR codes. Do not overwrite an existing release APK; publish a new version and leave older provisioning QR links working.

Version 1.5 does not contain the updater. Install version 1.6 over it once using the same signing key to gain this feature. Subsequent newer releases with update metadata can be installed from within the app.
