# Background enforcement and recovery

Gouge 1.4 fixes a gap where new installs were only watched by a receiver owned by the running foreground service. If the process was killed, that receiver and its in-process timers disappeared. Opening the dashboard restarted the service and finally suspended newly installed apps.

The connected Vivo phone's exit history on 2026-10-27 contained both low-memory termination and task-removal termination. Its app was not on Android's user battery-exemption list. These observations explain possible ways to reach the gap; the available history does not prove the exact cause of every missed install.

## Recovery paths

- The foreground service retains its sticky restart behavior and is explicitly kept when its activity task is removed. It posts a persistent, low-importance protection notification.
- An explicit `PendingIntent` recovery alarm lives in `AlarmManager`, outside the app process. A healthy service renews it at most every 30 seconds, with a nominal 60-second recovery deadline. Task removal and normal service destruction request an earlier retry. Destroying the process cannot delete this alarm as an in-process listener would.
- A persisted 15-minute `JobScheduler` job supplies an independent recovery path. It requires no network, charging, or battery-not-low condition. The interval is Android's periodic-job minimum, not a guaranteed delivery deadline.
- Manifest package-change receivers can wake an exempt device owner after installs or removals. They are an additional path; modern Android restricts implicit package broadcasts in other circumstances, so they do not replace the alarm and job.
- Boot and app-update receivers arm recovery before attempting the service. Locked boot only arms recovery and does not read credential-protected settings or passwords. Full enforcement starts after unlock. Granting exact-alarm access also refreshes scheduling.
- Reconnecting the accessibility service or the always-on VPN restarts enforcement without requiring the dashboard.
- A runtime exception during a protection check is logged and retried rather than permanently stopping the repeating check loop. New installs are checked before optional clipboard and scheduled-task maintenance.
- Service startup refreshes device-owner protection, retaining every app configured in the prevent-uninstall list rather than replacing that list with only Gouge.

All restart paths are conditional on Gouge actually being the device owner. Scheduled recovery does not grant app access or alter saved passwords, install delays, browser restrictions, or unlock windows.

## Battery settings

On the connected phone, the following targeted ADB settings were applied:

```powershell
adb -s 10AF3412G80021R shell dumpsys deviceidle whitelist +com.example.installlock
adb -s 10AF3412G80021R shell cmd appops set com.example.installlock RUN_IN_BACKGROUND allow
adb -s 10AF3412G80021R shell cmd appops set com.example.installlock RUN_ANY_IN_BACKGROUND allow
adb -s 10AF3412G80021R shell am set-inactive com.example.installlock false
```

The user whitelist entry persists across app updates and reboot until it is explicitly removed or the device is reset. The app checks Android's `isIgnoringBatteryOptimizations()` state and requests an exemption on launch if missing; its dashboard also shows a recovery button while optimization is enabled. Android exposes a user-consent request, not a public API allowing an ordinary app or device owner to silently make this setting irrevocable.

The phone also had a separate Vivo setting enabled: **App info > Gouge > Battery usage > Background power control > Restrict background power usage**. This remained restricted even after applying Android's whitelist. It was changed to **Allow background power usage** and verified after leaving and reopening the screen. Android's battery-exemption API alone does not reveal this manufacturer-specific restriction.

Device-owner user-control protection separately prevents ordinary force-stop/data clearing and exempts protected packages from App Standby Buckets. Manufacturer power-management policies can still affect delivery. Android may delay allow-while-idle alarms and jobs during Doze, especially when exact-alarm access is absent. No third-party app can promise that the operating system will never kill it or that every broadcast will run before another app can launch.

References: [Android Doze and battery exemptions](https://developer.android.com/training/monitoring-device-state/doze-standby), [implicit broadcast exceptions](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions), and [device-owner user-control protection](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setUserControlDisabledPackages(android.content.ComponentName,%20java.util.List)).

## Locking only Gouge's background-power editor (1.5)

The user explicitly declined blocking Vivo's shared power editor for all apps. Version 1.5 therefore adds a separate accessibility guard restricted to `com.android.settings`, `com.iqoo.powersaving`, and `com.vivo.abe`. It checks for Gouge's exact app heading together with the editor's radio controls. Merely mentioning Gouge in a list, viewing its app information or usage history, or opening another app's power editor does not trigger the guard.

On the protected editor, a touch-blocking accessibility overlay covers the controls, the guard restores Vivo's **Allow background power usage** option if necessary, and Back navigation closes the editor. Known Android optimization dialogs for Gouge are also recognized. The shared editor component remains enabled and no device-wide app-control restriction is added.

The guard has its own service; enabling it does not enable the separate browser-content accessibility service. The connected phone was provisioned with `android.permission.WRITE_SECURE_SETTINGS` through ADB so the device-owner app can maintain this guard's entry in the enabled-accessibility-services list. Existing accessibility entries are preserved. The foreground service checks this entry and observes changes to it. This permission is a one-time administrator setup step on each installation/device; it cannot be granted to the app by an ordinary runtime-permission prompt.

This is a targeted UI guard, not an immutable Vivo system policy. Manufacturer UI changes, inaccessible windows, privileged ADB changes, a disconnected accessibility service, or a process-recovery window can affect protection. Keep the actual battery exemption and Vivo Allow setting in place in addition to this guard.

The debug APK and instrumentation APK build, and all 247 JVM tests pass, including seven page-matching tests that cover the protected page, other apps, app lists, app information, optimization dialogs, and package/name matching.

On the connected Vivo phone, version 1.5 and its dedicated guard were installed and enabled. Opening Gouge's **Background power control** was intercepted and returned to the read-only battery-usage page; the guard logged the block. Arise O Sleeper!'s equivalent editor remained open with all three controls available, and its selected option was left unchanged. Removing only the guard's enabled-service entry caused it to be restored automatically by Gouge's monitoring service. The separate browser-content accessibility service was not enabled as part of this setup. These checks confirm the scoped behavior on this phone's current UI; they do not establish immunity to privileged system changes or every possible UI race.

## Earlier recovery validation (1.4)

The debug app and instrumentation APK build, and all 240 JVM tests pass. `EnforcementRecoveryDeviceTest` is restricted to a device-owner SDK emulator and verifies that the explicit recovery receiver restores a stopped foreground service without opening an activity, with a persisted backup job present.

The connected phone was updated without launching the dashboard. Version 1.4 started foreground enforcement automatically after replacement, retained its battery-exemption entry, and registered both its exact recovery alarm and backup job. No physical-phone reboot or saved-rule changes were needed.

The isolated device-owner emulator regression passed: the recovery receiver restored a stopped foreground service, with a persisted job registered. Android force-stops an instrumentation target at the end of a test session and cancels its scheduling; external lifecycle checks therefore perform initial app setup separately before leaving the dashboard closed.

The original scenario was also reproduced on the connected Vivo phone after the fixes: its app process was killed with SIGKILL, then a temporary ordinary app and a temporary browser were installed while Gouge's dashboard remained closed. Enforcement automatically returned in a new process. Suspension was verified 1.8 seconds and 0.9 seconds after the respective installations completed, and both temporary packages were removed afterward. The battery exemption and backup job remained present. The physical-phone transcript is in `build/phone-recovery-validation.txt`.

The broader emulator stress run was interrupted by emulator disconnects on the host. Forced-Doze, exact-alarm-revocation, and actual reboot recovery were not completed in that run and must not be reported as passed. The app's nominal recovery alarm deadline is not a zero-gap guarantee; process death, platform throttling, and manufacturer behavior can still introduce a recovery window.
