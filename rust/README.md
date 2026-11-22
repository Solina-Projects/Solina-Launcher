# Rust ports (Wave 3)

Kotlin code in the launcher is ported to Rust with
[rust-anything](https://github.com/erkamyaman/rust-anything)
(record → port → diff → bench → gate). The Kotlin implementations stay
in place and are the fallback whenever a native library is absent — the
app works exactly as before without the `.so` files.

## Crates

| Crate | Ports | Diff | Bench |
| --- | --- | --- | --- |
| `calculator-rs/` | `calculateEquation` (calc/) | 531/531 | 24.5x faster |
| `search-rs/` | `normalizeSearchText` + `fuzzyScore` (search/) | 540/540 | normalize 10.6x, fuzzyScore 23.9x |

`search-rs` also ships a batched scorer (`FuzzyScoreIndex`): build it
once per app list, then score every query in one call
(`FuzzyScoreBatch` in Java, `RustSearch.rankChoices` in Kotlin).

## Building the native libraries

On a machine with cargo, rust-anything and the Android NDK, Gradle
builds them automatically before every build (`buildRustNativeLibs`,
wired to `preBuild`) into `rust/jniLibs/<abi>/libcalc_rs.so` and
`libsearch_rs.so`, which are packaged into the APK. Without the
toolchain the task logs a skip and the Kotlin fallback runs.

Build them by hand:

```sh
rust-anything build --profile android --abi arm64-v8a --abi armeabi-v7a --api 26 --jni-libs "$PWD/rust/jniLibs" "$PWD/rust/calculator-rs"
rust-anything build --profile android --abi arm64-v8a --abi armeabi-v7a --api 26 --jni-libs "$PWD/rust/jniLibs" "$PWD/rust/search-rs"
```

(The crate path must be absolute: a relative path fails because the
tool invokes cargo from another working directory.)

## CI

CI (`.github/workflows/ci.yml`) does NOT install the Android NDK or a
Rust toolchain, so CI builds run the Kotlin fallback only and never
produce a native library. To build the `.so` files in CI too, add these
steps to the `build` job, after the `gradle/actions/setup-gradle` step
and before `Build APK` (BLOCKED: workflow edits return 403 via the
API — apply in the web UI):

```yaml
- name: Set up Rust
  uses: dtolnay/rust-toolchain@stable
  with:
    targets: aarch64-linux-android,armv7-linux-androideabi
- name: Set up Android NDK
  uses: android-actions/setup-android@v3
  with:
    packages: 'ndk;28.0.13004108'
- name: Install rust-anything
  shell: bash
  run: |
    curl -sL -o /tmp/rust-anything.tar.gz https://github.com/erkamyaman/rust-anything/releases/download/v2.1.0/rust-anything-v2.1.0-x86_64-unknown-linux-gnu.tar.gz
    tar -xzf /tmp/rust-anything.tar.gz -C /tmp
    sudo install -m 755 /tmp/rust-anything-v2.1.0-x86_64-unknown-linux-gnu/rust-anything /usr/local/bin/rust-anything
    rust-anything --version
```

The Gradle task then finds the toolchain and builds both libraries.
(`cargo install rust-anything` does NOT work — the crate is not on
crates.io; the release tarball is the install route. Pin v2.1.0: the
generated bridge code was produced by that version.)
