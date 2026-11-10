# Install Lock

Install Lock is an Android self-control app that uses **device-owner management** to delay new apps, lock distracting apps, and restrict browsing. It also includes task-specific delayed approvals, a personal secret vault, and a GitHub updater.

**Start here:** [install the app](#installation), complete [first-run setup](#first-run-setup), then use the dashboard search to find any feature described below.

> **Before enrolling your phone:** this app can suspend apps, prevent uninstall/force-stop, and uninstall apps matching the never-install list. The shipped default never-install rules include **`grok` and `webtoon`**. Matches can be removed automatically, including their data. A focus session cannot be cancelled early. Back up important data and understand these controls before making Install Lock device owner.

## Installation

Requires **Android 11 or later**. Download the current debug APK from [version 1.7.1](https://github.com/timothyjacobmail/Install_Lock/releases/tag/v1.7.1-debug-20261110). Installing the APK and enrolling it as device owner are separate steps: the current app requires device-owner setup before its dashboard becomes available.

### Without resetting: a computer and USB debugging

This is the simplest development setup for an existing phone, when Android allows it.

1. Download Google's [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) on a Windows, Mac, or Linux computer. Download `install-lock-debug.apk` to that computer.
2. On the phone, enable **Developer options → USB debugging**, connect by USB, and approve the computer's debugging request.
3. Open a terminal in the Platform Tools directory. On Windows PowerShell, with the APK in that directory, run:

   ```powershell
   .\adb devices
   .\adb install -r "install-lock-debug.apk"
   ```

4. For **first-time device-owner enrollment**, sync/back up account data, confirm you can sign back in, and temporarily remove accounts listed in Android's **Accounts / Passwords & accounts / Manage accounts** settings. This means Android system accounts, not every login inside a game or website. Run:

   ```powershell
   .\adb shell dpm set-device-owner com.example.installlock/.AdminReceiver
   ```

5. After successful enrollment, add your accounts back and open Install Lock. Complete the first-run checklist below.

Google documents this [no-reset development setup](https://developer.android.com/work/dpc/dedicated-devices/cookbook#development-setup). Existing device management, additional users/work profiles, or manufacturer restrictions may prevent enrollment. If it fails, use the exact error to diagnose the cause; APK installation alone does not grant device-owner powers. On macOS/Linux, use `./adb` instead of `.\adb`.

**Updating an already-provisioned phone:** install the newer APK over the existing app, or use **App updates**. Do not remove accounts, repeat enrollment, uninstall, or factory-reset just to update. Updates must use the same signing key.

### QR setup on a new or factory-reset phone

The QR installs version 1.7.1 as device owner. It is for initial Android setup, not for enrolling a phone after completing setup.

[![Install Lock setup QR](docs/installation/install-lock-setup-qr.png)](docs/installation/install-lock-setup-qr.png)

1. Display the QR on another screen; click it to open it at full size.
2. Back up and factory-reset the receiving phone, or start with a new phone.
3. At the first welcome screen, tap the same blank spot **six times**, before continuing setup or adding accounts.
4. Follow the Wi-Fi prompts, scan the QR, and accept device management.
5. Finish Android setup, then open Install Lock and enable the permissions below.

QR availability depends on the manufacturer's setup wizard. The APK URL and checksum match this exact release; older release QR codes remain valid for their original APKs. Complete factory-reset enrollment has not yet been verified on a physical phone.

### Play Protect, wireless debugging, and remote help

A sensitive-permissions warning can apply to internet-downloaded apps declaring accessibility services. Install Lock uses accessibility for browser and feed monitoring. ADB is the supported development installation route used on the test phone, but acceptance on every device is not guaranteed. Changing signing keys or choosing a release build alone does not remove a permission-based block. See [Google's explanation and developer appeal guidance](https://developers.google.com/android/play-protect/warning-dev-guidance).

Android 11+ supports [wireless ADB pairing](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi), normally with the phone and computer on the same local network. It does not remove enrollment/account requirements. For long-distance help, remotely control a consenting recipient's computer while their phone connects to it by USB or local Wi-Fi. Do not expose ADB directly to the public internet.

## First-run setup

1. **Check device-owner enrollment.** If the app shows “Not set up as device owner yet,” finish enrollment before configuring features.
2. **Set up task passwords.** Follow the setup screens, then open **Settings & passwords → Task passwords**. Different operations have separate credentials; protected apps have their own access, removal, and limit credentials. Generated credentials are saved encrypted inside Install Lock. The personal secret vault is optional.
3. **Enable browser accessibility.** In Android accessibility settings, enable **Install Lock browser and Google keyword filter**. It supports native Chrome URL checks, visible-page filtering, Reddit protection, feed monitoring, and Judge enforcement. Keep it connected when using monitored apps.
4. **Configure the Google VPN if you use the Google app.** Open **Browser & Google → Google VPN filter → Turn on** and accept Android's VPN prompt. Google stays locked when its required filter is unavailable.
5. **Allow notifications, exact alarms, and background operation** when prompted. Disable battery optimization for Install Lock and enable the manufacturer's equivalent of **Allow background power usage**. Exact alarms support scheduled tasks and secret timers.
6. **Review your rules.** Check **Never-install list**, **App locks**, Chrome approvals, and timing settings before enabling more restrictions. Add only the access you intend to permit.

For automatic maintenance of the separate background-power settings guard and manual accessibility restoration after banking, grant this optional administrator permission once through ADB:

```powershell
.\adb shell pm grant com.example.installlock android.permission.WRITE_SECURE_SETTINGS
```

The guard protects Install Lock's own background-power settings, not every app's settings. Without this grant, accessibility services may need manual restoration. Floating secret windows separately require Android's **Display over other apps** permission.

## How approvals and locks work

**Restrictions accumulate.** An app opens only when every applicable lock has cleared. Ending an installation wait or granting temporary app access does not override curfew, focus lock, a cooldown, required monitoring/VPN, or another active restriction. Open **Why apps are locked → Refresh** to see the reasons and Android's actual suspension status.

For a protected change, choose the exact operation and confirm its saved task credential. The app starts that task's countdown and applies that exact change automatically when the wait ends, even after you leave the screen. Manage or cancel it under **Pending tasks**. A device reboot restarts pending task waits. Changing a credential invalidates authorizations tied to its previous version; it does not unlock unrelated tasks.

For ordinary new Chrome approvals, confirmation starts **one approval wait**, not a task wait followed by another website wait. Existing imported credentials can retain longer waits. The countdown displayed for the operation is authoritative.

### Starting defaults

These are defaults for new configurations, not promises that an existing installation uses them. Check its settings and task countdowns.

| Control | Starting behavior |
| --- | --- |
| New-app installation wait | 15 minutes |
| Native task confirmation delay | 15 minutes |
| Ordinary Chrome site approval | One delayed confirmation; normally 15 minutes |
| Keyword removal requests | 15 minutes |
| Default temporary app access | 30 minutes, subject to the app's own limit |
| Global maximum temporary access | 120 minutes, also subject to the app's limit |
| Play Store opening wait | 2 minutes; changing this setting has its own protected task |
| New Banking/Finance list entry | Fixed 15-minute eligibility wait |
| Extra locked-site removal | Fixed 45-minute wait |
| Night curfew | 11 pm–4 am in the phone's current time zone |
| Watchdog cooldown | 30 minutes |
| Failed-password lockout | Starts after 5 failures; doubles from 1 minute, capped at 60 minutes |
| Personal secret vault | Default 15-minute reveal delay; visible for 60 seconds |

## App controls

### New-app installation delay and category exemptions

Newly installed non-system apps normally remain suspended until their installation wait ends. This applies to recent installation timestamps, so an app installed shortly before setup can still be waiting.

Under **Apps → Play Store immediate access**, select categories whose verified Google Play installations may skip that installation wait. Category verification needs internet; unknown/unverified categories retain the wait. A sideloaded APK does not gain the exemption just because a similar Play listing has an allowed category. An exemption does not override app locks, curfew, focus, or other rules.

### App locks and temporary access

Open **Apps → App locks** and switch on the apps you want permanently protected. Adding a lock is immediate and ends that app's existing temporary access. Removing a lock uses that app's removal-task delay.

Use **Apps → Temporarily unlock one app** to request timed access for a particular app. Initial task-password setup suggests a 15-minute per-app access limit; review each app's own limit. Its own credential, per-app access limit, global limit, and other protections still apply. Access ends when its timer runs out. **Lock protected apps now** ends temporary access immediately. Changing an app's access limit is a separate protected operation.

### Play Store opening delay

Open the Play Store using **Apps → Open Play Store**. The app waits before granting a Play Store session; cancelling the request means the next request starts afresh. Reopening an existing countdown preserves that countdown. After a session ends, a later opening can require another wait.

**Play Store opening delay** controls this wait separately from the delay imposed on a newly installed app. Changing or disabling the opening wait uses its own saved task credential and delay. Curfew, focus and cooldowns can still prevent Play Store access after its countdown finishes.

### Never-install list

Rules match installed non-system app names or package identities. Matches are suspended and scheduled for uninstallation, including matching apps already installed when a rule is added. Uninstallation deletes that app's data. Adding a rule is immediate; removing one is delayed.

**Default rules include `grok` and `webtoon`.** Review this list before enrolling a phone with apps/data you want to keep. This feature blocks installations from both Play Store and APK files; it is stronger than an ordinary app lock.

### Prevent uninstall and force-stop

Tick apps under **Apps → Prevent uninstall & force stop** to protect them from uninstall, force-stop, and data clearing through supported Android controls. Install Lock always protects itself. Removing another app from this list uses its saved task delay. Device-owner management is intentionally harder to remove than an ordinary installed app.

## Time and distraction controls

### Focus lock

Open **Focus lock → Start or view focus lock**, select apps, and choose **1–1440 minutes**. Review the selection before confirming.

**Once started, the session cannot be cancelled, shortened, or bypassed with a password.** App installations and updates are blocked too. Restarts and reboots do not cancel it. When it ends, other app restrictions continue.

### Night curfew

Open **Night curfew → Curfew status, apps & appeal**. The regular curfew is **11 pm–4 am**, using the phone's current time zone. The screen lists affected apps. Permanent targets include Chrome, the Google app, Play Store, YouTube, supported YouTube Whitelist variants, and Vivo Browser; other downloaded apps join for their installation night's curfew and leave that temporary enrollment at 4 am.

An appeal submitted **before 9 pm** moves that night's start to midnight, ending at 4 am as usual. It is a one-night appeal, not a permanent schedule change. App-access passwords do not bypass active curfew.

### Browsing and scrolling watchdog

Open **Browsing & scrolling watchdog → Watchdog status & website categories**.

- **Manga/comics browsing:** the bundled offline category list and your additions identify sites such as MangaDex and Webtoons. A warning appears on entry, a final warning at 5 seconds, and a shared 30-minute cooldown starts at 10 seconds of accumulated foreground exposure. Leaving pauses accumulation; returning to a categorized site within 10 minutes resumes it.
- **Rapid feeds:** six vertical scroll bursts within 15 seconds on recognized YouTube Shorts, Instagram Reels/home feeds, Reddit feeds, or supported website routes start the warning process. Continued rapid activity reaches the cooldown at 10 accumulated seconds. A continuous fling counts as one burst; watching one video without scrolling does not count. Identifiable comments, messages, search, and settings screens are excluded.
- **Cooldown:** monitored browsers/feeds and Play Store remain locked, and installations/updates are blocked. An app password does not clear it. An active cooldown survives restarts and reboots.

Use **Check category** to inspect a domain, or **Add website to manga/comics category** to classify it immediately, including subdomains. Unknown domains are not automatically categorized. Detection is best effort: hidden URLs, translated/changed app layouts, missing scroll events, and unsupported clients can prevent recognition.

## Browser and Google controls

### Chrome whitelist

Open **Browser & Google → Chrome whitelist**. Turn the whitelist on to restrict Chrome to active approvals. Other detected browsers are suspended while this mode is active. With no active approvals, Chrome can block all ordinary websites.

Choose **Add site**, or share a URL to Install Lock from Chrome or the Android share sheet. You can request a whole domain or a narrower path. Domain entries also cover subdomains; path entries cover matching path prefixes. Confirm the request and wait for its single approval countdown. Active entries show a check mark. Removing a manual approval is immediate; turning the whitelist off is a delayed task.

The blocked-page screen also provides **Whitelist this site**, so Chrome's Share menu is not required. Linked Reddit community paths and first-matching domain Allow rules can independently permit access. Approved pages can use narrowly scoped embedded reCAPTCHA resources without approving all Google pages. See [Chrome whitelist details](docs/chrome-whitelist.md).

### Ordered domain rules

Choose **Ordered domain rules** to add an **Exact domain**, **Domain or suffix**, or **Domain contains text** rule. Rules are checked from top to bottom: the **first matching rule wins**.

Block rules restrict access immediately. Adding Allow rules, reordering rules, or removing Block rules uses the relevant delayed task. An Allow rule can satisfy Chrome's whitelist requirement, but cannot remove extra-site locks, app locks, or keyword restrictions. Contains rules add only verified observed hosts to Chrome's managed policy; the first blocked load may need a refresh after the approval toast.

For example, an Allow suffix rule for `gov.in` covers matching government domains. A broad Allow suffix such as `org` also includes leisure sites using that suffix: it is your explicit rule, not a Judge decision.

### Extra locked sites

Use **Extra locked sites** for domains that should remain blocked even when a Chrome/domain approval exists. Adding one is immediate and includes subdomains. Requesting removal starts a fixed **45-minute** wait. The lock remains active until that removal completes.

### Browser and Google keyword filter

Enable the browser accessibility service, then open **Browser & Google keyword filter** to configure blocked words/phrases. The filter checks exposed visible browser pages and Google app content, including results and Discover. It can block or leave a matching page even if the website is otherwise approved.

Adding a blocked word is immediate. Removing one starts a delayed removal request that can be cancelled. Turning filtering off is a separate delayed task. **Per-word page exceptions** allow a selected blocked word when its configured exception is present on the same visible page. **Generic page exceptions** exempt a matching page from keyword filtering generally. Adding either kind is delayed; removing an exception is immediate. These exceptions do not disable domain, app, curfew, or other locks.

If Google results are covered, use **Start a new Google search** to submit another query without reopening the same blocked results. Visible-screen detection is not a guarantee against every brief content flash or unsupported page layout.

### Google VPN filter and DNS tuning

**Google VPN filter → Turn on** starts the per-app DNS-filtering VPN for the Google app. It restricts image/video-serving domains and applies relevant domain rules. It does not route all phone traffic or decrypt HTTPS pages. Other apps are outside this per-app VPN.

Use **DNS log / tune blocklist** to inspect DNS activity and refine blocked domains. Unblocking domains or turning off the VPN is delayed. Google remains locked while its required VPN is unavailable, even if keyword filtering is off or temporary app access was granted. Android generally allows one active VPN per user, so another VPN can conflict with this feature.

### Reddit subreddit allowlist

Open **Reddit subreddit allowlist**, set up any missing Reddit task credentials, and sign in to Reddit **before** enabling protection. Add `community`, `r/community`, or a canonical community URL. Additions are delayed; removing a community is immediate. An empty allowlist restricts all Reddit content.

The monitor requires verifiable native community identity. Home, Popular, All, search, profiles, mixed feeds, login, and unidentified screens are covered when they cannot establish an approved community. Use **Open r/name** to navigate directly to an approved community. Official Reddit is supported; detected modified clients are remembered, and **Register a custom Reddit client** can enroll another client. Unknown layouts remain restricted; arbitrary modified clients cannot be guaranteed compatible.

Approved communities generate linked Chrome path permissions with no second wait. Removing a community removes its linked permission; independently saved website/domain approvals can still apply. Client reinstall or data clearing does not erase rules stored by Install Lock. Other app locks remain in effect, and missing accessibility suspends monitored clients.

### Judge

Open **Judge** from Browser & Google, the Chrome whitelist, or a blocked Chrome page. Enter a public HTTPS URL and tap **Judge this website**. It checks redirects, readable HTML, and outgoing links without executing JavaScript or sending page text to an AI provider.

**Immediate approval currently supports only the reviewed Emirates Driving Company hosts:** `edcad.ae`, `www.edcad.ae`, `pay.edcad.ae`, and `elearning.edcad.ae`. Each host must pass its own content check. A positive result grants separate exact-host HTTPS access; it does not grant arbitrary subdomains or follow-on links.

Known leisure platforms—including YouTube, Reddit, Instagram, Facebook, X/Twitter, MangaDex, Weeb Central, DeviantArt, and Chess.com—cannot receive Judge approvals, even for educational-looking pages. Unknown, mixed-purpose, login-only, unreadable, or anti-bot-blocked pages keep the normal delay. Useful but unreviewed sites must still use the delayed whitelist.

Judge is **not a foolproof classifier of the entire web**. Other protections remain active; Judge approvals require connected browser monitoring. Remove a grant under **Judge approvals**. An existing independent Allow rule or manual whitelist approval is separate from Judge and can still permit a site. See [coverage, enforcement, and limitations](docs/judge.md).

## Banking mode

Use **Banking mode → Turn accessibility off for banking** before opening a bank that rejects accessibility. The app first verifies that monitored browsers and feeds are locked, then pauses its accessibility services. **Opening or closing a bank never switches accessibility automatically.** When finished, explicitly choose **Turn accessibility back on**, or restore the services in Android settings if administrator restoration is unavailable.

The Banking/Finance list can discover apps from their Google Play Finance category, with manual additions for others. Each newly enrolled installation has a fixed **15-minute eligibility wait**. Removing/re-adding that same installation does not cancel its pending wait; reinstalling creates a new installation. Finance includes more than banking, and a category is not proof that an app requires accessibility to be off.

Banking mode does not bypass installation waits, app locks, focus, curfew, or other device-owner restrictions. A paused session remains paused after reboot until you finish it manually. Judge grants are inactive while browser monitoring is paused.

## Additional protections and background operation

- **Block screenshots:** blocks screenshots and screen recording through device-owner policy across apps. Allowing capture again is a delayed task.
- **Curbox guard:** if you separately use Curbox, enable its accessibility service first, then turn on this guard. It makes access to YouTube and Instagram depend on Curbox being available, with a default 15-second grace period after it stops. Turning the guard off is delayed. Curbox is a separate app, not included in this APK.
- **Background-power settings guard:** a separate accessibility service protects Install Lock's supported background-power controls. Manufacturer-specific coverage is limited, particularly outside the tested Vivo settings. Keep the actual battery exemption enabled as well; the guard is not an immutable system policy.
- **Recovery:** boot/package-replacement handling and scheduled recovery try to restart enforcement without opening the dashboard. They do not cancel waits or grant app access. Reboots are not a general unlock mechanism, but different timers have different recovery behavior: pending task waits restart, while focus/curfew/active cooldown restrictions remain in effect.

## Personal secret vault: Delayed Secrets

Choose **Personal secret vault (optional)** on the dashboard. Add a named secret with **+**, or import a supported existing secret-list file. Search by name, request **Reveal**, and wait for its timer. The value becomes visible for its configured visibility window, then hides again. You can cancel a reveal, add time, close it, or use **Lock 1h** where shown.

For **Import**, use a plain-text file containing one `name<TAB>secret` entry per line, with a real tab between the two fields. Blank lines and lines starting with `#` are ignored; names must be unique in the file. Review the preview before confirming. Do not share this plaintext file as if it were encrypted.

The vault supports per-secret reveal delays, protected delay changes, copying, and floating password windows. Floating windows require overlay permission; direct entry into a focused password field also needs the app's accessibility integration. Clipboard cleanup and the reveal window limit availability, but copying or displaying a secret still exposes it to the receiving app or viewer.

This vault is separate from the app's encrypted native task credentials. You do not need to create a personal secret for every normal Install Lock task. Vault settings control its own default reveal delay and visibility window; reductions to delays and relevant visibility changes can have their own 30-minute pending-setting wait.

## Settings, passwords, and app updates

**Settings** exposes timing, unlock-preset, and password-security controls. Each Save button is protected by its own task credential/delay. Filling default values only fills the fields; complete the protected save to apply a change. Existing countdowns retain their saved duration, and per-app limits still apply after increasing a global limit. Fixed waits such as extra-site removal do not become shorter through general timing settings.

**Task passwords** changes one task's credential at a time and requires its current password. Sharing a chosen password is explicit. Repeated failures lock out that task; changing one task does not authorize another. Older hash-only credentials may require their original password once to save an encrypted native copy. There is no general reset-all-passwords shortcut.

**App updates** checks public GitHub releases, including debug prereleases. Tap **Check for updates**, then **Download and install** and confirm. The app verifies size, SHA-256, package ID, actual version, Android compatibility, and signing identity before asking Android to replace the existing package. Existing settings and device-owner enrollment are retained. An active installation restriction still prevents updating.

Updates are user-initiated, not silently scheduled. Version 1.5 needs a one-time manual upgrade to a version with the updater. Future releases must include a higher version code and matching `update.json`; see [update and publishing instructions](docs/app-updates.md). A self-update can close the app briefly; reopen it afterward.

## Troubleshooting

| What you see | What to check |
| --- | --- |
| “Not set up as device owner” | Finish enrollment; ordinary Device administrator activation is not equivalent. |
| An app is still locked after a timer ends | Open **Why apps are locked → Refresh**. Clear every applicable reason, not just one. |
| Browsers, Google, Reddit or feeds remain suspended | Check browser accessibility is enabled **and connected**, banking mode is finished, and other restrictions have cleared. Google also requires its VPN. |
| Chrome blocks an approved page | Check its exact host/path, extra locks, first-matching domain rule, keyword filters, app suspension, and the approval countdown. Refresh after a new observed-host Allow approval. |
| Judge refuses a useful site | Check its limited reviewed-host catalog and readable content. Unknown sites keep their delay by design; use the ordinary whitelist. |
| A bank rejects the phone's accessibility state | Use manual banking mode, wait for confirmed shutdown, and check the banking-list wait and other app locks. |
| A task is waiting or did not apply | Check **Pending tasks**, its credential, and whether reboot restarted the wait. Changed credentials can invalidate old confirmations. |
| A recent app disappears | Check the never-install list, including the shipped default name rules. |
| Updates fail | Check internet/GitHub rate limits, free storage, Android compatibility, active installation restrictions, and that the update uses the same signing key. |
| Enforcement stops in the background | Check battery optimization, manufacturer background/autostart controls, exact-alarm permission, accessibility, and the optional guard setup. |

Accessibility-based filtering depends on what apps expose and how Android/manufacturer controls behave. It is not universal or tamper-proof. The APK disables Android's ordinary app backup; do not assume settings or Keystore-protected credentials will migrate automatically to another phone. Preserve important data before uninstalling, clearing data, or resetting a device.

## Building and publishing

This project builds `com.example.installlock`. The `secretvault` module is the embedded Delayed Secrets library. Build on a computer with **Java 21**, the Android SDK required by the Gradle configuration, and `sdk.dir` configured in your untracked `local.properties`:

```powershell
.\gradlew.bat :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Run JVM tests and compile device tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

For release assets, install `qrcode[pil]` and run:

```powershell
python tools/prepare-update-release.py --tag YOUR_NEW_RELEASE_TAG
```

This writes the APK, update manifest, provisioning JSON, QR and hashes under `build/update-release/YOUR_NEW_RELEASE_TAG`. Upload them to that exact new GitHub release tag. Never overwrite an old QR's APK with different bytes. Keep the original private debug keystore for compatible updates; do not commit or upload it. The current Gradle release variant also uses debug signing.

This guide describes the current distributed APK's features. Release notes document build provenance: shipped APKs can include local working-tree features beyond the tagged committed source, so GitHub's automatic source archive may not reproduce every feature in the binary. Current 1.7.1 validation includes 332 passing JVM tests, successful app/instrumentation APK builds, public release checksum checks, and a phone check where EDC received a Judge approval and YouTube was rejected. These checks do not establish universal classification accuracy or complete factory-reset enrollment coverage.
