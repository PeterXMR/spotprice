# SpotPrice (Rust core + Kotlin Multiplatform UI)

A client-only sats price ticker and Sats ↔ fiat converter for Android and iOS.
No backend — all logic runs on the device.

## Architecture

- `core/` — Rust workspace, the "compiled core":
  - `price-sources/` — exchange/aggregator adapters (Coinbase, Kraken, Coingecko, Bitstamp)
  - `aggregator/` — median + σ-rejection across sources
  - `convert/` — sats ↔ fiat math (`rust_decimal`)
  - `cache/` — in-memory + persisted last-known price
  - `ffi/` — single UniFFI surface exposed to mobile
- `app/` — KMP + Compose Multiplatform app, single `composeApp` module:
  - `composeApp/src/commonMain/` — shared Kotlin business logic +
    Compose Multiplatform UI (target-agnostic)
  - `composeApp/src/androidMain/` — Android shell: one Activity hosting
    the Compose UI and loading the UniFFI-generated bindings
  - iOS host: not yet wired up — see [docs/PLAN-ios.md](docs/PLAN-ios.md)
    for the roadmap

Native libraries are produced by `cargo-ndk` (Android `.so`) today, and will
be produced by `cargo-swift` (iOS `.xcframework`) once the iOS host lands.
Both are wired to Kotlin/Swift through UniFFI-generated bindings.

## Build prerequisites

| Tool | Required version | Install |
|---|---|---|
| Rust | 1.85+ | rustup, pinned via `rust-toolchain.toml` |
| Android NDK | r28+ (for 16 KB pages) | Android Studio SDK Manager |
| JDK | 21 (LTS) | Temurin / `brew install --cask temurin@21` |
| Xcode | 16+ (full app, not CLT) | Mac App Store (~10 GB) |
| `cargo-ndk` | 4.1+ | `cargo install cargo-ndk` |
| `cargo-swift` | 0.11+ | `cargo install cargo-swift` |
| `just` | any | `cargo install just` |

## Quick start

```sh
just install-tools     # one-time toolchain bootstrap
just test              # run all Rust tests
just build-android     # cross-compile core for all 4 Android ABIs
just build-ios         # build .xcframework (Mac + full Xcode required)
just run-android       # install + launch on connected device/emulator
```

## Installing

Three install paths, all directly from this repo's GitHub Releases — no
intermediate store, no account, no telemetry:

- **[Obtainium](https://obtainium.imranr.dev/)** (auto-updating sideload):
  add the app by pasting `https://github.com/PeterXMR/spotprice` into
  Obtainium's "Add app" field. It will track new releases as they're tagged
  and offer updates.
- **F-Droid**: planned. The repo ships [Fastlane
  metadata](fastlane/metadata/android/en-US/) and is set up for
  [reproducible builds](docs/REPRODUCIBLE-BUILDS.md); the inclusion request
  will be filed once a signed release lands.
- **Manual sideload**: download the latest `SpotPrice-vX.Y.Z.apk` from
  [Releases](https://github.com/PeterXMR/spotprice/releases) and install it
  with your file manager (you may need to enable "Install unknown apps" for
  the source app).

## Releasing

Tag pushes (`vX.Y.Z` or `vX.Y.Z-suffix`) auto-publish a GitHub Release with
the APK attached. See [docs/RELEASING.md](docs/RELEASING.md) for the full
release flow, pre-release vs. release semantics, and the roadmap to
signed/minified production builds.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
