# Go DNS backend evaluation

12 November 2026. Selection I5 was an evaluation, with adoption conditional on improvement.

An original, dependency-free Go prototype under `tools/perf/go-dns` parses bounded DNS questions, performs suffix-set blocking and produces local NXDOMAIN replies. Its optional UDP server binds loopback only; it is not a production resolver and does not forward permitted traffic. No downloaded repository code was executed or linked into Gouge.

The equivalent standalone Java harness is `tools/perf/DnsBackendBenchmark.java`. Both use the same blocked query, a 20,000-operation warmup and 50 batches of 20,000 operations. Batching avoids the coarse Windows Go clock resolution that made individual-operation timing unsuitable. Runs alternate Go/Java on the same host. This measures parsing, lookup and reply construction; it does **not** measure Android runtime, JNI, VPN establishment, upstream latency or battery consumption.

| Run | Go mean ns/query | Java mean ns/query |
|---|---:|---:|
| 1 | 731.30 | 598.20 |
| 2 | 549.65 | 625.70 |
| 3 | 562.99 | 547.60 |

Go was faster in one of three runs. The differences are fractions of a microsecond, without a consistent advantage. Retain the existing Java local-denial dispatch and Kotlin VPN lifecycle. A separate Go runtime, Android protected-socket bridge and production protocol implementation are not justified by these measurements. This is not a finding that Go is generally slow; [Intra](https://github.com/Jigsaw-Code/Intra) demonstrates its usefulness for a different DNS-over-HTTPS workload. Encrypted upstream DNS and broader routing would require a new end-to-end evaluation.

Reproduction (Go 1.27.4 Windows amd64, verified official archive SHA-256 `178ab98950ac69ab25069307dc6ade79f3816e414cce95b4da166a115cde1e7e`; Java from Android Studio JBR):

```powershell
go -C tools/perf/go-dns test .
go -C tools/perf/go-dns build -o ../../../build/go-dns-evaluation.exe .
& build/go-dns-evaluation.exe
javac -d build/perf tools/perf/DnsBackendBenchmark.java
java -cp build/perf DnsBackendBenchmark
```

Toolchains, binaries and caches live under ignored `build/`; the APK has no Go dependency. Earlier Android measurements for the existing dispatch/startup fixes remain in [performance.md](performance.md).
