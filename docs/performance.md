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
