# Install Lock

This Android project builds Install Lock (`com.example.installlock`). The `secretvault` module is the Delayed Secrets library embedded in the app.

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
