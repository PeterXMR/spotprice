# Reproducible builds

## Why this matters for SpotPrice

SpotPrice is positioned as a privacy-first, client-only Bitcoin tool: no
backend, no analytics, no third-party SDKs. That positioning is only
meaningful if a user (or an F-Droid reviewer, or anyone else) can
independently confirm that the APK they install was actually built from the
published GPL-3.0 source — that no extra code was injected between
`git push` and the release asset.

Reproducible builds are the mechanism for that. A build is *reproducible*
when anyone, on a different machine, can rebuild the same source at the
same commit and get a **byte-for-byte identical** artifact (modulo the
signing block). Bitcoin wallets like
[Sparrow](https://sparrowwallet.com/download/#reproducible-builds) and
[Wasabi](https://github.com/zkSNACKs/WalletWasabi/blob/master/WalletWasabi.Documentation/Guides/DeterministicBuildGuide.md)
ship this way; it's the bar this project is aiming at.

## Current state

What this PR makes deterministic:

- **`SOURCE_DATE_EPOCH`**: set per-job in CI from the head commit's
  timestamp (`git log -1 --format=%ct`). Honored by many build tools
  (rustc debuginfo, archivers, etc.) to replace wall-clock time. Re-runs
  of the same SHA see the same epoch.
- **`--remap-path-prefix`**: passed via `RUSTFLAGS` to the cargo-ndk build
  so that absolute paths from the GitHub runner (`$HOME`,
  `$GITHUB_WORKSPACE`) don't leak into the `.so` files' debuginfo or panic
  strings. Without this, two machines building the same source produce
  binaries that differ in their embedded paths.

What is **not yet** deterministic (explicit gaps; tracked for follow-up):

- **Gradle / AGP**: the Android Gradle Plugin defaults are not fully
  deterministic. Notable knobs we have not yet set include
  `android.useUniquePackageNames=false`,
  `org.gradle.parallel=false` for release builds (parallelism can perturb
  some ordering), explicit pinning of AGP and Kotlin versions in the
  release context, and sorted ordering of resources/asset entries. These
  live in `app/composeApp/build.gradle.kts` and friends and are
  intentionally deferred to a follow-up PR after the signing config from
  PR4 lands, to keep the diff reviewable.
- **Release signing**: the current pipeline ships a debug-signed APK
  (debug keystore is per-machine, so even a perfectly deterministic
  payload would differ in the signing block). Reproducibility is only
  fully verifiable once a stable release keystore is in place and the
  workflow strips the signing block before diffing (see verification
  below).
- **NDK / clang version pin**: `cargo-ndk` picks up whatever NDK is
  installed on the runner. We need to pin a specific NDK version in CI
  (and document it) so the linker output matches across rebuilds.
- **R8 / minification**: still off (`isMinifyEnabled = false`). When it
  flips on, R8's output ordering will need to be checked for determinism.

## Verifying a published APK

Once a tag is published, to confirm the release asset matches the source:

```sh
# 1. Clone at the exact tag
git clone https://github.com/PeterXMR/spotprice
cd spotprice
git checkout v0.X.Y

# 2. Reproduce the CI build locally. Either via `act` (runs the workflow
#    in a container that approximates the GitHub runner):
act -j build

#    ...or by following the workflow's steps manually (see
#    .github/workflows/android.yml — the SOURCE_DATE_EPOCH step and the
#    RUSTFLAGS export are the load-bearing ones).

# 3. Download the published APK
curl -LO https://github.com/PeterXMR/spotprice/releases/download/v0.X.Y/SpotPrice-v0.X.Y.apk

# 4. Strip signing blocks from both APKs (the signing block legitimately
#    differs between builds; everything else should match) and diff.
#    apksigner ships with the Android build-tools.
apksigner verify --print-certs SpotPrice-v0.X.Y.apk
unzip -p SpotPrice-v0.X.Y.apk > /tmp/published.zip
unzip -p app/composeApp/build/outputs/apk/release/composeApp-release.apk > /tmp/local.zip
diff <(unzip -l /tmp/published.zip) <(unzip -l /tmp/local.zip)
```

Once the gaps above are closed, the final `diff` should be empty.

## References

- [reproducible-builds.org](https://reproducible-builds.org/) — canonical
  project and the `SOURCE_DATE_EPOCH` spec.
- [F-Droid: Reproducible Builds](https://f-droid.org/en/docs/Reproducible_Builds/)
  — what F-Droid checks before granting the "Reproducible Build" badge.
- [Bitcoin Core's guix-based reproducible builds](https://github.com/bitcoin/bitcoin/blob/master/contrib/guix/README.md)
  — the gold standard for a Bitcoin project; uses Guix for full
  toolchain determinism.
- [Sparrow Wallet reproducible builds](https://sparrowwallet.com/download/#reproducible-builds)
  — a closer comparable (Bitcoin wallet, sideloaded, signed releases).
