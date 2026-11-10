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

[Download the debug APK and read the full setup instructions](https://github.com/timothyjacobmail/Install_Lock/releases/tag/v1.5-debug-20261110). The optional permission for automatic accessibility-guard maintenance still needs the one-time ADB command in those instructions. Complete factory-reset enrollment has not yet been tested on a physical phone.

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
