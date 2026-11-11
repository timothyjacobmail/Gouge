# Banking mode

Accessibility is controlled manually from **Banking mode**. Tap **Turn accessibility off for banking** before using your bank, then **Turn accessibility back on** when finished. Opening, leaving or closing an app never switches accessibility. The app no longer requests Usage Access or polls foreground usage events. Existing paused sessions stay paused until manually finished, including after an upgrade or reboot.

The off button first verifies Android has locked the monitored browsers and feeds, then switches off Install Lock's two accessibility services. The background-power guard respects this manual pause. Normal app locks, focus lock, curfew, installation waits and the banking-list wait continue. The notification returns to the manual controls.

## Banking/Finance list and the 15-minute wait

Finance apps are still detected from their own Google Play category metadata. Missing listings and other apps can be added manually. Every newly enrolled installation receives a fixed **15-minute wait**, starting when it joins the list. Pending apps show a countdown and are suspended by device-owner policy until their wait ends, even if accessibility is manually off or an app-access password is supplied.

Waits are saved synchronously, use monotonic time during a boot, and retain their deadline across reboot. Each app has its own timer. Category refreshes, removing/re-adding the same installation, and ordinary app updates do not shorten or restart an existing wait. Removing an entry cannot cancel its pending wait. Reinstalling an app starts a new wait when that installation joins the list. After expiry, normal app protections still apply.

Existing manual selections and verified Finance category records are migrated as already eligible on the first run of this version. New manual selections are saved after that migration and receive the wait. The UI's **Open** buttons open only eligible list entries and never change accessibility; turn accessibility off manually first. Android's actual suspension/release status remains visible under **Why apps are locked**.

## Category discovery

The Finance category is broader than banking and includes payment, insurance and investing apps. A category does not establish whether an app rejects accessibility. Discovery reads only the listing's own structured metadata matched to its package ID; unknown categories and unavailable listings do not automatically join. Protected browsers and monitored feeds remain excluded.

Checks run on a worker for existing installed apps and after package changes. Cached metadata is bound to installation and update timestamps, refreshes daily, and remains usable offline for the same installed version. Failed lookups retry after a minute. First-time discovery needs internet. Discovery and category refresh do not switch accessibility.

Banking discovery can use a package's public Play listing regardless of installer. Installation-delay category exemptions still independently require installation through Google Play and a selected immediate-access category.

## Manual service restoration

The existing administrator-granted WRITE_SECURE_SETTINGS permission allows manual controls to remove and restore only Install Lock's enabled-service entries. Other apps' accessibility services and changes made during banking are preserved. Without this permission, the off button uses each connected service's disableSelf API; after finishing banking, enable the desired Install Lock services manually in Android Accessibility settings.

The administrator setup command, if needed, is:

~~~powershell
adb shell pm grant com.example.installlock android.permission.WRITE_SECURE_SETTINGS
~~~

Turning accessibility back on restores the services enabled before the manual pause. No timer or bank launch/exit restores them. Direct password entry and the accessibility-based background-power settings guard are unavailable during the pause. Other apps' services are never switched off by banking mode.

References: [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#disableSelf()), [Google Play categories](https://support.google.com/googleplay/android-developer/answer/9859673).
