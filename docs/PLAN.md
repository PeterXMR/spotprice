# SatsPrice — Implementation Plan

> **Engineer onboarding:** zero codebase context assumed. Each task is bite-sized
> (2–5 min). Follow in order. Run the test commands; expected output is shown.

**Goal:** A client-only Bitcoin price ticker and Sats ↔ BTC ↔ fiat converter for
Android and iOS. No backend. All logic on-device.

**Architecture:** Rust workspace under `core/` produces a single shared library
exposed via UniFFI to a Kotlin Multiplatform + Compose Multiplatform app under
`app/`. Pattern mirrors Bitkey (Block) and Mozilla Application Services.

**Tech stack (verified May 2026):**
- Rust 1.85, UniFFI 0.31, reqwest 0.12 (rustls), tokio, sled, moka, rust_decimal
- cargo-ndk 4.1+, cargo-swift 0.11+, Android NDK r28+
- Kotlin 2.2.20, Compose Multiplatform 1.10.3, AGP 9.0.0
- Koin 4.x DI, navigation-compose (KMP), multiplatform-settings

---

## Phase 0 — Toolchain & repo skeleton ✅ DONE

- [x] `git init` in repo root
- [x] Top-level dirs: `core/`, `app/`, `docs/`, `scripts/`
- [x] `.gitignore`, `.editorconfig`, `LICENSE` (GPL-3.0-or-later), `README.md`
- [x] `rust-toolchain.toml` pinning Rust 1.85 + all mobile targets
- [x] `core/Cargo.toml` workspace with 5 members and shared deps
- [x] `justfile` with `install-tools`, `test`, `build-android`, `build-ios`, etc.

## Phase 1 — `core/convert/` (sats ↔ BTC ↔ fiat math, pure Rust)

**Files:**
- Create: `core/convert/Cargo.toml`
- Create: `core/convert/src/lib.rs`
- Create: `core/convert/src/error.rs`

**Properties (proptests):**
- `btc_to_sats(s) -> btc -> sats` is identity for any integer sat count
- `sats_to_btc` never panics, always returns Decimal with ≤ 8 fractional digits
- `fiat_to_sats(price, fiat) -> sats_to_fiat(price, sats)` ≈ fiat within 1 sat rounding

**Unit tests (in `lib.rs` `#[cfg(test)]` mod):**
1. `1 BTC == 100_000_000 sats`
2. `0.00001 BTC == 1_000 sats`
3. `sats_to_fiat(67_000.0, 100_000_000) == 67_000.00`
4. `fiat_to_sats(67_000.0, 67.00) == 100_000` (0.001 BTC at $67k)
5. Reject negative inputs (`ConvertError::Negative`)

**Steps:**
- [ ] **1.1** Write `Cargo.toml` for convert crate (deps: `rust_decimal`, `thiserror`)
- [ ] **1.2** Write failing test `test_one_btc_equals_100m_sats` in `lib.rs`
- [ ] **1.3** Run `cargo test -p satsprice-convert`; expect "function not found"
- [ ] **1.4** Implement `btc_to_sats(Decimal) -> u64` minimally; rerun, expect pass
- [ ] **1.5** Add reciprocal test + impl `sats_to_btc(u64) -> Decimal`
- [ ] **1.6** Add fiat tests + impl `sats_to_fiat`, `fiat_to_sats`
- [ ] **1.7** Add negative-input rejection tests + `ConvertError`
- [ ] **1.8** Add 2 proptests for round-trip identity
- [ ] **1.9** `cargo clippy -p satsprice-convert -- -D warnings` clean
- [ ] **1.10** Commit: `feat(convert): sats/BTC/fiat conversion with rust_decimal`

## Phase 2 — `core/aggregator/` (median + σ-rejection + trust scores)

**Files:**
- Create: `core/aggregator/Cargo.toml`
- Create: `core/aggregator/src/lib.rs`
- Create: `core/aggregator/src/types.rs` (`RawQuote`, `AggregatedPrice`)
- Create: `core/aggregator/src/median.rs`
- Create: `core/aggregator/src/trust.rs` (EMA weights)

**Tests:**
1. Median of `[100, 101, 102, 200, 99]` = `101` (200 dropped as outlier)
2. Returns `None` if `< 3` live quotes (per 2026 best practice)
3. Drops quotes >60s old
4. Drops quotes >0.5% deviation from preliminary median
5. EMA weight update converges to uniform when sources agree

**Steps:**
- [ ] **2.1** `Cargo.toml` (no deps beyond `serde`, `thiserror`, `rust_decimal`)
- [ ] **2.2** Define `RawQuote { source: String, price: Decimal, fetched_at: u64 }`
- [ ] **2.3** Failing test: `median_of_three_returns_middle`
- [ ] **2.4** Implement `median(&[Decimal]) -> Option<Decimal>`
- [ ] **2.5** Add stale-quote filter test + impl `prune_stale(quotes, max_age_secs)`
- [ ] **2.6** Add outlier-rejection test + impl `prune_outliers(quotes, tolerance_pct)`
- [ ] **2.7** Add 3-source minimum test + impl `aggregate() -> Option<AggregatedPrice>`
- [ ] **2.8** Add EMA trust score test + impl `TrustScores::update(source, deviation)`
- [ ] **2.9** Proptest: aggregate is monotonic-ish in inputs
- [ ] **2.10** Commit: `feat(aggregator): median+σ-rejection with EMA trust weights`

## Phase 3 — `core/price-sources/` (trait + 4 adapters)

**Files:**
- Create: `core/price-sources/Cargo.toml`
- Create: `core/price-sources/src/lib.rs`
- Create: `core/price-sources/src/{coingecko,coinbase,kraken,bitstamp}.rs`
- Create: `core/price-sources/src/error.rs`
- Create: `core/price-sources/src/http.rs` (shared reqwest client builder)

**Trait:**
```rust
#[async_trait::async_trait]
pub trait PriceSource: Send + Sync {
    fn name(&self) -> &'static str;
    async fn fetch(&self, fiat: &str) -> Result<RawQuote, SourceError>;
}
```

**Endpoints (verified):**
- CoinGecko: `https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies={fiat}` → `bitcoin.{fiat}`
- Coinbase: `https://api.coinbase.com/v2/prices/BTC-{FIAT}/spot` → `data.amount` (string)
- Kraken: `https://api.kraken.com/0/public/Ticker?pair=XBT{FIAT}` → `result.XXBTZ{FIAT}.c[0]` (string)
- Bitstamp: `https://www.bitstamp.net/api/v2/ticker/btc{fiat}/` → `last` (string)

**Tests (one per adapter using `wiremock`):**
- Mock the exchange's JSON response shape → assert `RawQuote.price`
- 5xx response → `SourceError::ServerError`
- Malformed JSON → `SourceError::Parse`
- 2-second timeout enforced

**Steps:**
- [ ] **3.1** `Cargo.toml` (deps: `reqwest`, `rustls-platform-verifier`, `serde`, `serde_json`, `async-trait`, `thiserror`, workspace `tokio`)
- [ ] **3.2** Implement `http.rs` with shared `ClientBuilder` using platform verifier + 2s timeout
- [ ] **3.3** Define `PriceSource` trait + `SourceError` enum
- [ ] **3.4** Failing wiremock test for Coingecko adapter
- [ ] **3.5** Implement `CoingeckoSource` to pass
- [ ] **3.6** Repeat 3.4–3.5 for Coinbase, Kraken, Bitstamp
- [ ] **3.7** Add `factory::all_sources()` returning `Vec<Box<dyn PriceSource>>`
- [ ] **3.8** Commit: `feat(price-sources): 4 adapters with rustls + 2s timeout`

## Phase 4 — `core/cache/` (in-memory + persisted last-known)

**Files:**
- Create: `core/cache/Cargo.toml`
- Create: `core/cache/src/lib.rs`
- Create: `core/cache/src/mem.rs` (moka)
- Create: `core/cache/src/persist.rs` (sled)

**Tests:**
1. In-memory: insert then get within TTL → hit
2. After TTL: get → miss
3. Persisted: insert, drop cache, reopen → still readable
4. Concurrent: 100 goroutines (tasks) write/read; no panics, no races

**Steps:**
- [ ] **4.1** `Cargo.toml` (deps: `moka`, `sled`, `serde`, `bincode`)
- [ ] **4.2** Failing test for TTL behaviour
- [ ] **4.3** Implement `MemCache::new(ttl: Duration)` + `get_or_insert_with`
- [ ] **4.4** Failing test for sled roundtrip
- [ ] **4.5** Implement `PersistCache::open(path)` + `put`/`get`
- [ ] **4.6** Compose `PriceCache` that writes-through to both layers
- [ ] **4.7** Commit: `feat(cache): moka 60s in-mem + sled persistent fallback`

## Phase 5 — `core/ffi/` (UniFFI surface — single `SatsPriceCore` facade)

**Files:**
- Create: `core/ffi/Cargo.toml`
- Create: `core/ffi/src/lib.rs`
- Create: `core/ffi/build.rs` (UniFFI scaffolding generation)
- Create: `core/ffi/uniffi.toml` (Kotlin/Swift package config)

**Public API (Rust → mapped to Kotlin/Swift):**
```rust
#[derive(uniffi::Record)] pub struct PriceSnapshot {
    pub price: String,             // decimal as string (no f64 across FFI)
    pub fiat: String,
    pub timestamp: u64,
    pub sources_used: u32,
    pub stale: bool,
}

#[derive(uniffi::Object)] pub struct SatsPriceCore { /* ... */ }

#[uniffi::export(async_runtime = "tokio")]
impl SatsPriceCore {
    #[uniffi::constructor] pub fn new(cache_dir: String) -> Arc<Self>;
    pub async fn fetch_price(&self, fiat: String) -> Result<PriceSnapshot, FfiError>;
    pub fn convert_sats_to_fiat(&self, sats: u64, price: String) -> String;
    pub fn convert_fiat_to_sats(&self, fiat: String, price: String) -> u64;
    pub fn supported_fiats(&self) -> Vec<String>;
}
```

**Steps:**
- [ ] **5.1** `Cargo.toml` (`uniffi` with `tokio` feature, all sibling crates as path deps)
- [ ] **5.2** Write `build.rs` calling `uniffi::generate_scaffolding`
- [ ] **5.3** Write `uniffi.toml` declaring Kotlin + Swift bindings packages
- [ ] **5.4** Implement `SatsPriceCore::new` wiring cache + sources
- [ ] **5.5** Implement `fetch_price` using `tokio::join!` across all sources
- [ ] **5.6** Add integration test calling FFI surface end-to-end (with wiremock)
- [ ] **5.7** Run `cargo run --bin uniffi-bindgen -- generate ...` and verify Kotlin file is generated
- [ ] **5.8** Commit: `feat(ffi): UniFFI 0.31 SatsPriceCore facade exposing async fns`

## Phase 6 — Android cross-compile

**Steps:**
- [ ] **6.1** Install Android NDK r28+ via Android Studio SDK Manager; set `ANDROID_NDK_HOME`
- [ ] **6.2** `just build-android` — expect `.so` files in `app/android/src/main/jniLibs/<abi>/`
- [ ] **6.3** `just verify-android-alignment` — `LOAD` alignment must show `0x4000`
- [ ] **6.4** Strip-check: `du -sh app/android/src/main/jniLibs/arm64-v8a/*.so` < 10 MB
- [ ] **6.5** Commit `.jniLibs.gitignore` (don't commit binaries — Gradle rebuilds them)

## Phase 7 — KMP + Compose Multiplatform project scaffold

**Files (composeApp module):**
- Create: `app/settings.gradle.kts`, `app/build.gradle.kts`
- Create: `app/gradle/libs.versions.toml` (single source of truth for versions)
- Create: `app/composeApp/build.gradle.kts`
- Create: `app/composeApp/src/commonMain/kotlin/sats/price/App.kt`
- Create: `app/composeApp/src/androidMain/kotlin/sats/price/MainActivity.kt`
- Create: `app/composeApp/src/iosMain/kotlin/sats/price/MainViewController.kt`
- Create: `app/iosApp/iosApp/iOSApp.swift`, `app/iosApp/iosApp.xcodeproj`

**Steps:**
- [ ] **7.1** Write `libs.versions.toml` with all pinned versions
- [ ] **7.2** Write root `build.gradle.kts` declaring plugins (apply false)
- [ ] **7.3** Write `composeApp/build.gradle.kts` with `kotlin {}` block: `androidTarget`, `iosArm64`, `iosSimulatorArm64`, common deps
- [ ] **7.4** Write minimal `App.kt` Composable in `commonMain`
- [ ] **7.5** Write `MainActivity.kt` setting `setContent { App() }`
- [ ] **7.6** Write `MainViewController.kt` returning `ComposeUIViewController { App() }`
- [ ] **7.7** `./gradlew :composeApp:assembleDebug` — expect Android APK to build
- [ ] **7.8** Commit: `feat(app): KMP+CMP scaffold with shared App() Composable`

## Phase 8 — UniFFI Kotlin bindings + first "hello from Rust"

**Steps:**
- [ ] **8.1** Add `:rust` Gradle task in `composeApp/build.gradle.kts` running `cargo build` + bindgen
- [ ] **8.2** Add `androidMain` source set entry for generated Kotlin
- [ ] **8.3** Add Application class calling `System.loadLibrary("satsprice_ffi")` + `rustls_platform_verifier::android::init_hosted`
- [ ] **8.4** In `App.kt`, call `SatsPriceCore.fetchPrice("usd")` from a `LaunchedEffect` and render result in `Text()`
- [ ] **8.5** Install on emulator: `just run-android`
- [ ] **8.6** Verify a real BTC price renders on screen
- [ ] **8.7** Commit: `feat(app): first end-to-end Compose → Rust → Coingecko call`

## Phase 9 — Compose UI screens (functional clean-room equivalents)

**Screens:**
- `ConverterScreen` — three `OutlinedTextField`s (Sats / BTC / Fiat) that recompute each other on edit; currency dropdown; last-updated timestamp; pull-to-refresh
- `SourcesScreen` — `LazyColumn` of price sources with last fetched + enable toggle
- `SettingsScreen` — currency, theme, decimal precision, about link
- `AboutScreen` — version, GPL-3.0 notice, credits, donation BTC address

**Steps:**
- [ ] **9.1** Define navigation graph using `androidx.navigation:navigation-compose` (KMP)
- [ ] **9.2** Implement `ConverterScreen` with single source-of-truth ViewModel state
- [ ] **9.3** Implement `SourcesScreen`
- [ ] **9.4** Implement `SettingsScreen` + `multiplatform-settings` for persistence
- [ ] **9.5** Implement `AboutScreen`
- [ ] **9.6** Wire navigation suite scaffold for adaptive (compact/medium/expanded) layouts
- [ ] **9.7** Commit per screen

## Phase 10 — ViewModels + state wiring

**Steps:**
- [ ] **10.1** Add `androidx.lifecycle:lifecycle-viewmodel-compose` (multiplatform)
- [ ] **10.2** `ConverterViewModel` exposing `StateFlow<ConverterUiState>`
- [ ] **10.3** Background poll: refresh price every 60s while screen is visible
- [ ] **10.4** Wire Koin 4.x for DI; provide `SatsPriceCore` as singleton
- [ ] **10.5** Commit: `feat(app): viewmodels + Koin DI + 60s refresh poll`

## Phase 11 — Android end-to-end run

- [ ] **11.1** Boot Android emulator (Pixel 8 API 35, NDK r28+)
- [ ] **11.2** `just run-android`
- [ ] **11.3** Manually test: enter 1 BTC, see fiat update; change fiat, see number convert; pull-to-refresh
- [ ] **11.4** Capture logcat with `tracing` output from Rust visible
- [ ] **11.5** Commit any fixes; tag `v0.1.0-android`

## Phase 12 — iOS targets (requires full Xcode app installed)

- [ ] **12.1** Install Xcode 16+ from Mac App Store; accept license; `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`
- [ ] **12.2** `cargo install cargo-swift`
- [ ] **12.3** `just build-ios` — produces `SatsPriceCore.xcframework` + SwiftPM package
- [ ] **12.4** Create `iosApp/` Xcode project; add `SatsPriceCore` as local SwiftPM
- [ ] **12.5** Configure Run Script build phase: `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`
- [ ] **12.6** Implement `iOSApp.swift` hosting `MainViewController`
- [ ] **12.7** Build + run on iOS simulator
- [ ] **12.8** Add `PrivacyInfo.xcprivacy` (no tracking domains; declare reason codes for any time/UserDefaults API used)
- [ ] **12.9** Commit: `feat(app): iOS xcframework + SwiftUI host + privacy manifest`

## Phase 13 — Polish & release prep

- [ ] **13.1** Add Proguard/R8 rules for UniFFI + rustls-platform-verifier (from their READMEs)
- [ ] **13.2** Reproducible build config: pin all toolchain versions; strip timestamps
- [ ] **13.3** Add F-Droid metadata in `fastlane/metadata/android/`
- [ ] **13.4** Add Zap Store metadata (`.well-known/zapstore.yaml`)
- [ ] **13.5** Add GitHub Actions: lint, test, build-android, (build-ios on macos-latest)
- [ ] **13.6** Add `CHANGELOG.md`, version bump to `0.1.0`, tag release

---

## Self-review checklist

- **Spec coverage:** All sats-price functionality (multi-source aggregation, sats/BTC/fiat conversion, source selection, offline cache) is covered in Phases 1-5 (Rust core) and Phase 9 (UI).
- **Type consistency:** `PriceSnapshot`, `RawQuote`, `AggregatedPrice`, `SatsPriceCore` are used identically across phases. FFI uses `String` for decimals (no `f64` across boundary). Error types are `thiserror`-based on Rust side, map to UniFFI `Error` enum exposed as Kotlin sealed class.
- **No placeholders:** Each step has a concrete deliverable. No "TBD" or "add validation here".
- **Bite-sized:** Steps are 2-5 minutes; commits frequent (one per sub-phase).

## Out of scope (intentionally)

- Multi-currency conversion chains (only BTC ↔ fiat for now)
- Charts / historical data (deferred to v0.2)
- Lightning address / zap calculator (placeholder module reserved in Phase 13)
- Push notifications for price alerts (deferred to v0.2)
- Desktop targets (CMP supports them; not in v0.1 scope)
- Reproducible-build verification automation (manual for v0.1)
