# Gouge password derivation

This component performs existing PBKDF2-HMAC-SHA256 password derivation using
RustCrypto (`pbkdf2` and `sha2`). It keeps UTF-8 input, each stored salt, 120,000
rounds and a 32-byte result. It does not create credentials, authorize tasks,
compare passwords, store secrets or change lockout/delay decisions.

Only the derived bytes cross back through JNI. Rust-owned password/output buffers
use `Zeroizing`; the Java input copy is cleared in `finally`. The library has no
mutable global state. SHA-256 uses runtime CPU-feature detection with a software
fallback. Android Keystore encryption remains in Android.

The app uses its existing JCA implementation if native loading is unavailable or
if malformed UTF-16 requires the platform's original encoding behavior. Android
compatibility tests assert that the native library actually loaded and compare
its results against the platform provider.

Gradle builds release libraries from this source and `Cargo.lock` for arm64-v8a,
armeabi-v7a, x86_64 and x86, using API 30 and 16 KB ELF page alignment. Install
Rust 1.99.1 (the toolchain file installs its Android targets) and Android NDK
30.0.16248370. Set `ANDROID_NDK_HOME` for another NDK location and `GOUGE_CARGO`
for a custom Cargo executable. Standard SDK NDK installs and this workspace's
ignored `build/toolchains` paths are detected automatically.

`cargo test --locked` checks a published PBKDF2-HMAC-SHA256 vector. Device timings,
hash-compatibility checks and isolated migration tests are documented in
[`docs/performance.md`](../../docs/performance.md). No prebuilt binaries or local
toolchains are committed.

Third-party licenses are shipped in
[`Rust-password-kdf-NOTICES.txt`](../../app/src/main/assets/licenses/Rust-password-kdf-NOTICES.txt).
The pinned Rust standard library notices are shipped alongside it as
`Rust-1.99.1-runtime-COPYRIGHT.html`, copied from the compiler distribution's
`share/doc/rust/COPYRIGHT-library.html` when updating the toolchain.
To regenerate them after a dependency update:

```sh
cargo metadata --manifest-path native/password-kdf/Cargo.toml --locked --format-version 1 > build/rust-password-metadata.json
python tools/collect-rust-notices.py build/rust-password-metadata.json app/src/main/assets/licenses/Rust-password-kdf-NOTICES.txt
```
