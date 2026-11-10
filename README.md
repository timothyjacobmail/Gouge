# Install Lock

This Android project builds Install Lock (`com.example.installlock`). The `secretvault` module is the Delayed Secrets library embedded in the app.

## Install using the QR code

Requires Android 11 or later. This installs the debug version of Install Lock as device owner.

[![Install Lock setup QR](docs/installation/install-lock-setup-qr.png)](docs/installation/install-lock-setup-qr.png)

1. Display this QR code on another screen. Click the image to open it at full size.
2. Back up and factory-reset the receiving phone.
3. At the first welcome screen, tap the same blank spot six times, before continuing setup or adding accounts.
4. Follow the Wi-Fi prompts, scan the QR code, and accept the device-management setup.
5. Finish Android setup and open Install Lock to enable its feature permissions.

[Download the debug APK and read the full setup instructions](https://github.com/timothyjacobmail/Install_Lock/releases/tag/v1.7.1-debug-20261110). The optional permission for automatic accessibility-guard maintenance still needs the one-time ADB command in those instructions. Complete factory-reset enrollment has not yet been tested on a physical phone.

## Judge

Open **Browser & Google → Judge** to inspect a public website. Known leisure platforms and uncertain pages keep the existing whitelist delay. Immediate access requires a reviewed service host plus readable supporting content. The initial reviewed service is Emirates Driving Company; unfamiliar useful sites still use the normal delay. This is a conservative reviewed-domain check, not a foolproof classifier of the whole web. See [Judge coverage and limitations](docs/judge.md).

## App updates

Install [version 1.7.1](https://github.com/timothyjacobmail/Install_Lock/releases/tag/v1.7.1-debug-20261110) once to get the updater. Then open **Settings & passwords → App updates** to check for newer GitHub releases and download and install them. Existing settings and device-owner setup are preserved. See [app update instructions](docs/app-updates.md).

The [Chrome whitelist](docs/chrome-whitelist.md) also honors ordered domain Allow rules and supports embedded reCAPTCHA on approved websites.

Build Install Lock on Windows with Java 21 and the Android SDK configured in `local.properties`:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

Run JVM tests and compile device tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Install Lock's scheduled unlocks, curfew timers, and Delayed Secrets timers remain in this project.
