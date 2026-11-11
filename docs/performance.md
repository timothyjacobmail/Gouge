# Performance work in Gouge 1.9.5

The first measured target is browser keyword filtering. The previous matcher searched the full normalized page with a separate Unicode-boundary regex for every keyword. Page checks can repeat every 500 ms; large rule lists made this unnecessarily expensive.

The Kotlin/Java boundary now passes one normalized string to an immutable Java scanner:

- Small lists use literal `String.indexOf` searches followed by boundary checks.
- Lists of 16 or more keywords use an Aho–Corasick automaton to share one traversal. Compact transition arrays replace construction-time hash maps; root ASCII transitions use a small lookup table.
- Matching returns indices in the existing sorted keyword order. Overlapping phrases, generic exceptions, per-word exceptions and lexical first-match priority remain intact.
- ASCII normalization skips Unicode normalization and regex replacement when unnecessary, reusing already-normalized strings. Non-ASCII input retains NFKC, formatting-character removal and root-locale lowercasing.
- Unicode boundaries retain the runtime's regex semantics. Supplementary-character keywords use the original full-pattern matcher because desktop Java and Android ICU have subtle differences. No page text is cached or retained between scans.

The improvement comes from the algorithms and fewer repeated scans, not from Java inherently being faster than Kotlin. Android lifecycle, accessibility and device-policy code remains in Kotlin. C++/Rust would add a native ABI, packaging and JNI data-transfer boundary to this workload without a demonstrated benefit over this implementation. Python would require an additional runtime. Android's [JNI guidance](https://developer.android.com/ndk/guides/jni-tips) specifically recommends minimizing marshalling across that boundary. Native code remains an option for a future measured workload that justifies it.

## Phone measurements

Measured on the connected Vivo V2436 on 2026-11-11. The benchmark runs its own `app_process` ART process, leaving Gouge's protection services running. It uses synthetic public strings, not saved user keywords or browsing data. For each operation: 30 warmup calls, seven batches of ten timed calls, median batch time per call. Regex patterns and scanner construction are outside the timed scan. No fixed CPU frequency or thermal controls were imposed.

| Operation | Original | Optimized | Ratio |
| --- | ---: | ---: | ---: |
| 3 keywords, 131,072 characters, no match | 2.741 ms | 1.088 ms | 2.52× |
| 32 keywords, 131,072 characters, no match | 32.926 ms | 1.591 ms | 20.70× |
| 128 keywords, 131,072 characters, no match | 127.134 ms | 1.714 ms | 74.19× |
| 128 keywords, same page with late match | 125.766 ms | 1.641 ms | 76.63× |
| ASCII normalization, 132,000 characters | 26.174 ms | 2.655 ms | 9.86× |
| Unicode normalization, 132,015 characters | 27.598 ms | 29.263 ms | 0.94× |

The Unicode case deliberately takes the original path; it showed about 6% overhead in this run. These are component microbenchmarks, not whole-app speed, battery, UI latency or memory-use claims. Actual gains depend on page size, keyword list, Unicode content and accessibility-tree costs. Short lists retain their lightweight path; no scan interval, lock timer or enforcement rule was relaxed.

Raw results: [phone CSV](performance/keyword-scanner-vivo-20261111.csv). The same Android run passed 500 randomized comparisons against its original regex engine plus 500 ASCII-normalization comparisons. The JVM suite additionally covers phrases, lexical priority, Unicode boundaries, invalid words, control characters, empty lists, normalization duplicates and cross-page isolation; all 364 tests pass. App and Android test APKs compile.

## Reproduce

On Windows, with a JDK, Android platform/build tools and optionally an ADB-connected phone:

```powershell
./tools/perf/run-keyword-benchmark.ps1 -SdkRoot YOUR_ANDROID_SDK -JavaHome YOUR_JDK
./tools/perf/run-keyword-benchmark.ps1 -SdkRoot YOUR_ANDROID_SDK -JavaHome YOUR_JDK -Phone -Device YOUR_SERIAL
```

The phone run uploads a uniquely named benchmark DEX under `/data/local/tmp` and removes that exact file afterward. It does not install instrumentation, change Gouge preferences or stop its services. Normalization is reported separately from normalized-page matching so the ratios cannot be mistaken for end-to-end speedups.

## Background profiling and Rust evaluation in 1.9.6

The user requested a live profile focused on general speed and battery use, with Rust adopted only if it provides a worthwhile speed improvement. Android's installed Simpleperf profiled the debuggable app without root, new instrumentation or changes to protection intervals. Three 40-second `cpu-clock` recordings at 499 Hz captured call stacks. Kernel addresses were restricted; application stacks were available and no samples were lost. See [Android application profiling](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/android_application_profiling.md).

The profile found two avoidable costs:

1. `JudgeStore` compared complete `JudgePreferences` objects *after* their constructors had derived host approvals and compiled filters. Equal inputs therefore still repeated the expensive work. The cache now compares plain `JudgePolicyInputs` first, compiling only on a changed key. All rule inputs, ordered domain rules, currently active site approvals and engine revision remain part of that key; there is no time-to-live or listener delay.
2. `RedditStore.discover` reloaded every installed app's label through Android/Vivo resources on each discovery pass. A bounded cache now keeps one current label per package, keyed by install/update time, resource/literal label and locale. Package invalidation clears it; missing packages are pruned. Discovery still checks current metadata and link handlers at its existing interval. Cached labels are classification metadata, never authorization.

Leisure-domain matching also stopped copying/scanning the entire catalog for each host. It checks exact DNS-label suffixes using existing sets. A compiled policy reuses its host keyword matcher and ordered domain matcher instead of rebuilding them for each host.

| Inclusive sampled CPU share | Original 1.9.5 | Judge fix only | Both fixes |
| --- | ---: | ---: | ---: |
| `JudgeStore.snapshot` | 15.36% | 1.43% | 0.72% |
| `JudgeRules.leisureHost` | 11.70% | 0.26% | 0.21% |
| `RedditStore.discover` | 23.87% | 34.81% | 0.42% |
| `Policy.reconcile` | 49.20% | 56.06% | 16.23% |
| Play category fetch | 30.13% | Below 0.1% report threshold | 45.36% |

Inclusive rows overlap and must not be summed. Estimated sampled CPU totals for the three 40-second recordings were 4.643, 3.074 and 4.754 seconds. Multiplying those totals by inclusive shares estimates about 2.285 versus 0.772 seconds inside `Policy.reconcile` before/after both fixes, roughly 66% lower within that path. Whole-process sampled CPU did **not** decrease in the final recording because background network work increased. Separate 30-second task-clock observations were 5.072 seconds before and 1.360 seconds after, but those are observations of variable workloads, not a controlled percentage improvement.

The screen was asleep during the captures. Browser accessibility was off initially and enabled later during the session; profiling did not toggle it. The phone was charging, network activity varied, and these are debug-build measurements. Consequently no battery-life, discharge-rate or universal responsiveness claim is made. An active-browsing workload was not profiled in this investigation.

Decision: **do not add Rust for these findings**. Reimplementing repeated work in Rust would still repeat it; caching removes that work entirely on unchanged inputs. Android package/resource/Binder calls and HTTPS connection activity remain prominent, and their platform costs cannot be removed by rewriting the caller. This investigation did not establish a remaining native-compute kernel worth a JNI boundary, so no Rust prototype was integrated and no Rust-vs-Java timing claim is made. A future isolated CPU-heavy hotspot can be compared with Rust using end-to-end marshalling costs. Rust's [Android target support](https://doc.rust-lang.org/rustc/platform-support/android.html) permits such a component; the decision here is based on workload, not language availability.

All 372 JVM tests pass, including immediate invalidation of every Judge policy input, exact domain boundaries, unchanged-input reuse, and package label update/reinstall/locale invalidation. App and Android test APKs compile. The final update was installed in place on the Vivo phone.

Sanitized method-level evidence: [original](performance/background-before-20261111.txt), [Judge cache fix](performance/background-judge-cache-20261111.txt), [both fixes](performance/background-final-20261111.txt). Raw stack recordings stay outside version control and are removed from the phone after reporting.

To repeat a bounded recording (10–60 seconds):

```powershell
./tools/perf/profile-background.ps1 -SdkRoot YOUR_ANDROID_SDK -Device YOUR_SERIAL -Seconds 40 -Label background
```

The script saves the textual report under `build/profiles` and deletes only its uniquely named remote recording afterward.

## Task confirmation latency in 1.9.7

The reported delay occurred before **Confirm this task** became available while changing YouTube's per-app limit. An already-native credential needs only a metadata read. A legacy hash-only credential instead searches saved vault values, deriving PBKDF2-HMAC-SHA256 for each candidate before attaching the matching current password. This shared path affects older credentials for other app, website and settings tasks as well. The exact original UI wait cannot be replayed after a credential has migrated without reverting it; the comparison below uses isolated synthetic credentials instead.

A Simpleperf capture during legacy lookup attributed 62.05% of inclusive sampled CPU to `TaskVault.hash`, with 62.01% under Android's Bouncy Castle PBKDF2 provider and 52.79% under its HMAC finalization. Unlike the background policy work in 1.9.6, this is a self-contained CPU-heavy workload that benefits from a native boundary.

The app now derives the **same** 32-byte PBKDF2-HMAC-SHA256 result using [RustCrypto's PBKDF2 implementation](https://docs.rs/pbkdf2/0.13.0/pbkdf2/fn.pbkdf2_hmac.html) and SHA-256, keeping UTF-8 input, each existing salt and all 120,000 rounds. A single JNI call performs the complete derivation. Constant-time result comparison, credential scopes, failed-attempt accounting, lockouts, confirmation waits and per-app limits retain their existing behavior. Android Keystore still encrypts saved passwords. The original JCA derivation remains a fallback for unavailable native loading and malformed UTF-16, preserving platform encoding behavior.

Two additional changes remove avoidable waiting without caching authorization:

- Saved native metadata enables the confirmation button directly, without queuing behind the single credential executor. Legacy migration stays off the UI thread.
- Legacy lookup verifies each distinct saved value once per invocation. It still checks **all** distinct values and retains every matching entry, including a later duplicate with a longer delay or hard lock. It does not infer equivalent passwords from string equality or remember failed guesses between calls. Successful-verification cache hits no longer wait behind an unrelated expensive verifier holding the cache monitor.

### Isolated phone measurements

Measured on the same Vivo V2436, using the debug app with release-optimized Rust. The fixed fixture contains 25 distinct synthetic saved values and one matching task credential. Both the Android context and its application context redirect preferences to uniquely named test files, which are deleted afterward. Preliminary trials were excluded after detecting and correcting encrypted-preference context isolation; their synthetic entries and timers were removed and the original vault entries verified unchanged. The final fixture asserts its exact size. No actual task was scheduled or protection setting changed by the benchmark.

| Operation | Platform implementation | Rust implementation |
| --- | ---: | ---: |
| PBKDF2, ASCII password | 943.34 ms | 26.73 ms |
| PBKDF2, Unicode password | 893.79 ms | 26.22 ms |
| PBKDF2, embedded NUL | 967.41 ms | 26.63 ms |
| PBKDF2, long password | 880.40 ms | 26.45 ms |
| 25 saved-value verifications / complete migration | 23,370.32 ms | 1,141.55 ms |

These PBKDF2 timings include JNI transfer and compare byte-for-byte equal outputs with the installed Android provider. The four-case median is approximately 919 ms versus 26.5 ms, about 35 times faster for this operation. The platform 25-entry measurement times verification only; the Rust measurement includes the full lookup, hard-lock checks, Keystore attachment and preference commit, so it is a conservative comparison rather than identical whole-screen paths. Metadata lookup after migration took 2.00 ms. Cold native authentication including Keystore decryption took 53.97 ms. Ten warm decrypt/checks took 324.92 ms total; Keystore costs remain even when verification is cached.

CPU frequency and thermal state were not fixed, other app services continued running, and the tests are not battery or universal UI-latency measurements. [Numeric results](performance/password-confirmation-vivo-20261111.csv) contain only synthetic workload labels and elapsed times.

Validation: 380 app JVM tests plus 39 vault JVM tests pass; all 11 selected phone tests pass, covering native hash compatibility, saved-password changes, metadata-only lookup, maximum duplicate delays, hard locks and incorrect manual attachment. A release-optimized Rust known-vector test also passes. All four Android ABI libraries compile with 16 KB ELF alignment. The arm64 library is approximately 260 KB; no prebuilt binaries are stored in Git.

### Build and reproduce

Install Rust 1.99.1 and Android NDK 30.0.16248370, or set `ANDROID_NDK_HOME` and `GOUGE_CARGO` for custom locations. Gradle builds the locked Rust source for arm64-v8a, armeabi-v7a, x86_64 and x86 and packages the resulting libraries. Android API 30 matches the app's existing minimum SDK. Standard SDK NDK installs are detected through `local.properties` or `ANDROID_HOME`. Source, dependency lockfile, JNI contract and notice-generation instructions are in [native/password-kdf](../native/password-kdf/README.md).

```powershell
./gradlew.bat :app:testDebugUnitTest :secretvault:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.example.installlock.TaskConfirmationTimingTest,com.example.installlock.LegacySecretLookupTest,com.example.installlock.NativeTaskPasswordsTest -e profileBaseline true com.example.installlock.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s TaskConfirmationTiming:I '*:S'
adb shell am start -n com.example.installlock/.MainActivity
```

Run only the listed isolated classes on a provisioned personal phone; unrelated historical integration tests can exercise policy APIs. The optional `profileNativeApp` argument also reads metadata and verifies the existing native limit credential for that package, without resetting failures, scheduling a task or printing its value.
