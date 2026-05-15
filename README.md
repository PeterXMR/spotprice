# SatsPrice (Rust core + Kotlin Multiplatform UI)

A client-only Bitcoin price ticker and Sats ↔ BTC ↔ fiat converter for Android and iOS.
No backend — all logic runs on the device.

## Architecture

- `core/` — Rust workspace, the "compiled core":
  - `price-sources/` — exchange/aggregator adapters (Coinbase, Kraken, Coingecko, Bitstamp)
  - `aggregator/` — median + σ-rejection across sources
  - `convert/` — sats ↔ BTC ↔ fiat math (`rust_decimal`)
  - `cache/` — in-memory + persisted last-known price
  - `ffi/` — single UniFFI surface exposed to mobile
- `app/` — KMP + Compose Multiplatform app:
  - `android/` — Android shell (one Activity)
  - `ios/` — Xcode project (SwiftUI host + `ComposeUIViewController`)
  - `domain/` — shared business logic (Kotlin)
  - `ui/` — Compose Multiplatform screens

Native libraries are produced by `cargo-ndk` (Android `.so`) and `cargo-swift`
(iOS `.xcframework`) and wired to the Kotlin/Swift sides through UniFFI-generated bindings.

## Build prerequisites

| Tool | Required version | Install |
|---|---|---|
| Rust | 1.85+ | rustup, pinned via `rust-toolchain.toml` |
| Android NDK | r28+ (for 16 KB pages) | Android Studio SDK Manager |
| JDK | 21 | Temurin / `brew install --cask temurin@21` |
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

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
