# Opt-in beta features

Open the dashboard's hamburger menu → **Beta features**. The master switch, optional flags and new profiles all start off. Installing this update does not activate a profile, filter another app's DNS, start a listener or change an existing task password.

These additions are original implementations informed by the [repository catalogue](research/gouge-feature-catalogue.md), rather than copied third-party applications. Existing hard Focus, curfew, keyword rules, temporary-link rules and app passwords remain authoritative.

## Selected features

| IDs | Where to use them | Behaviour |
|---|---|---|
| A1, A2 | Profiles, schedules & limits | Named app/site profiles; several weekly windows, including overnight windows, in the profile's saved time zone. |
| A3, A4 | Profile editor | Daily minutes per app and a shared group allowance. Zero means unlimited. |
| A5, A6 | Profile editor | Continuous group session cap, mandatory break and daily opening limit. Switching between group apps does not reset the session. |
| A8 | Saved profile → Pomodoro | Focus/break cycles independent of stable Focus. Starting a session commits its parameters until it ends. Other locks remain active during breaks. |
| A9 | Saved profile → Commit | Commit by duration, calendar date or permanently. Committed profiles accept extra targets, renaming and longer commitments, not rule removal or early overrides. Permanent mode requires a separate explicit confirmation. |
| A10 | Saved profile → Request bonus minutes | Delayed extra allowance for the displayed calendar day; does not erase counters or increase short-video limits. |
| B1 | Feed, browser & display options | Block detected native YouTube Shorts and Instagram Reels. |
| B2 | Feed options and custom UI rules | Selectively cover Home/feed navigation, YouTube Home/suggestions, Instagram Home/Explore, or short-video tabs. |
| B3, B4 | Profile editor | Group short-video minutes and estimated video-count quotas. |
| B5 | Profile website/path rules | Deny a domain or path with host/path boundary matching, alongside stable Chrome protection. |
| B6, B7 | Feed, browser & display options | Chrome Google SafeSearch and strict YouTube **web** Restricted Mode. Does not claim to configure native YouTube Restricted Mode. |
| B10 | Feed, browser & display options | Additional translation/archive proxy domains and data/blob/file page rules. |
| B11 | Custom UI rules & node picker | Bounded package/resource-ID selectors, optional exact text and hide/block actions. No arbitrary scripts. Picker stores resource IDs only and excludes password nodes. |
| C3 | Feed, browser & display options | Retype a chosen sentence before requesting an optional beta relaxation. This adds friction; authentication/delay remains separate. |
| C6 | Profile grayscale + grayscale option | Grayscale only while a scheduled profile app is foreground. Saves/restores previous system colour settings; requires secure-settings permission. |
| C9 | Profile → Sip & stretch | Usage-triggered break prompt and required break, using the profile's break duration. |
| D3 | Feed options → live counter | Small accessibility overlay with today's estimated short-video count. |
| D11 | Home-screen widget picker → Beta usage | Themed usage/progress widget for the first enabled profile; tap for all profiles. Refreshes while monitoring is active and periodically through Android. |
| E2 | DNS app scope & category lists | Google-only by default; explicitly select extra packages or all apps except Gouge. Google stays covered. Applies to the existing VPN, not a competing VPN. |
| E5 | DNS app scope & category lists | Named category lists imported/updated from local hosts/domain text files. Removing domains or a category is a delayed relaxation. No automatic external downloads. |
| F1 | Local accountability companion | Install the same APK on a second phone and select companion mode without Device Owner provisioning. Pair on the same LAN using the protected phone's fingerprint and one-use code. Pairing requires a delayed approval on the protected phone. |
| F3 | Companion → profiles / send command | Authenticated remote profile lock or bounded timed override. Cannot override stable protections, committed profiles or active Pomodoro sessions. |
| I1 | Feed options → background worker | Coalescing current-state policy sweeps on one worker; policy reconciliation and Chrome writes are serialized. |
| I3 | Feed options → independent recovery | A failing maintenance step does not skip subsequent protection checks. |
| I5 | [Networking evaluation](performance-go-dns.md) | Tested an isolated Go prototype; retained Java/Kotlin because the measured local workload showed no consistent Go advantage. No Go runtime added to the APK. |
| I7 | Automatic beta storage | Separate SQLite/WAL database, transactional revision checks, usage counters, encrypted peer keys, replay counters and bounded configuration history. No stable data migration. |

## Limits and recovery

- Foreground usage is observed by Gouge's accessibility service, not reconstructed from historical Android usage records. Tracking pauses on the lock screen and on essential system screens. Apps with active beta profiles require the monitor to reconnect before resuming. Several profiles can overlap; their totals must not be summed as device-wide usage.
- Node inspection is bounded and runs at most once per second while beta is active. Resource IDs and UI structure change between app versions. UI masks and short-video detection are best-effort; content may draw before an accessibility update. Short counts estimate vertical scroll bursts, not completed videos. Test the specific apps you use before committing a profile.
- Counter updates persist in batches of at most roughly five seconds, with immediate writes for app transitions and newly required breaks. An unexpected process kill can lose a few seconds of unflushed usage. Daily totals persist across ordinary restarts. Usage rows expire after 14 days; configuration history retains 20 revisions.
- Home, Settings, the active keyboard and the default phone app cannot be beta targets. Stable Gouge protections are unchanged by this beta exclusion.
- Relaxations use a separate **Relax beta restrictions** task credential. Pending changes carry a database revision; if another edit occurs first, resubmit the stale change. Commitment rules are rechecked at execution time.
- A profile commitment protects that profile and prevents switching off the beta master. It does not permanently freeze unrelated global DNS categories, display options or companion controls; those retain their separate delayed approval.
- The companion listener is manual and off by default, with an ongoing notification. It accepts private/link-local clients only, uses pinned TLS with an Android Keystore EC certificate, an expiring eight-digit pairing code, bounded connection handling, HMAC-authenticated commands and durable replay counters. Paired secrets are encrypted with Keystore AES-GCM and bound to their identity. There is no cloud service, router port forwarding, discovery broadcast or automatic listener restart after reboot. At most eight peers can be paired. Revoke peers from the protected phone through a delayed task.
- DNS scope/list changes do not decrypt HTTPS or block every image/connection. Encrypted DNS and cached connections can bypass DNS-only filtering. Existing temporary Google Images access does not bypass an additional beta category block.
- Grayscale restores the previous state when leaving the target app or monitoring stops. If settings permission is unavailable, the pending restoration state is retained for a later retry.
- A beta database failure is isolated from stable enforcement; the Beta features screen reports storage trouble. It is not a substitute for Android's device-owner restrictions or a guarantee against privileged filesystem tampering.

## Source references

Primary references and licensing context are recorded in the [catalogue](research/gouge-feature-catalogue.md). Chrome policies: [SafeSearch](https://chromeenterprise.google/policies/#ForceGoogleSafeSearch), [YouTube web restriction](https://chromeenterprise.google/policies/#ForceYouTubeRestrict). Android documents the TLS signing requirements in [KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setDigests(java.lang.String...)). No third-party scripts or blocklists are bundled or executed.

## Release validation

Version **1.10.0-beta.1 (23)**: 418 JVM tests passed; nine isolated Android database, recovery and real Keystore/TLS tests passed on the connected Vivo phone. Compatibility lint completed without errors; existing/advisory warnings remain. Framework `android:tint` is retained in RemoteViews widget layouts with a targeted `UseAppTint` suppression, because those layouts inflate framework ImageViews outside AppCompat.

The beta-off phone check confirmed the new navigation/screen and connected usage monitor. An aggregate fingerprint of all 62 existing credential fields matched before and after the update tests. No real beta profile, permanent commitment, remote override or expanded VPN scope was activated during validation. Version-dependent native feed selectors and every third-party app variant have not all been tested on this phone; keep those options experimental.
