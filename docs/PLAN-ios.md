# SatsPrice — iOS Implementation Plan (Phase 2)

> **Prerequisite:** [PLAN.md](PLAN.md) Phase 1 (Android) is complete and shipping
> on Google Play / F-Droid. This plan adds iOS as a second target consuming the
> same Rust `core/` and Kotlin `composeApp` you already built — no rewrites.
>
> **Why a separate plan?** iOS work has hard prerequisites (full Xcode app,
> Apple Developer Program enrollment, Privacy Manifest declarations) that don't
> belong in the Android critical path. Tracking them separately keeps Phase 1
> shippable on its own.

**Goal:** Ship the same SatsPrice app on iOS (iPhone + iPad, iOS 16+) using the
existing Rust core and Compose Multiplatform UI.

**Architecture (unchanged from PLAN.md):** Rust `core/` produces an
`.xcframework` (via `cargo-swift`) that ships with the iOS app bundle. KMP
`composeApp` gets iOS targets added (`iosArm64`, `iosSimulatorArm64`,
optionally `iosX64`). `iosApp/` is a thin SwiftUI host that calls
`ComposeUIViewController { App() }` to render the shared Compose UI.

---

## Phase i0 — Prerequisites

- [ ] **i0.1** Install **Xcode 16+** from the Mac App Store (~10 GB)
- [ ] **i0.2** `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`
- [ ] **i0.3** `xcodebuild -version` reports `Xcode 16.x`
- [ ] **i0.4** Open Xcode once, accept license, install additional components
- [ ] **i0.5** Enroll in Apple Developer Program ($99/year) if you want TestFlight or App Store distribution (optional for simulator dev)
- [ ] **i0.6** `cargo install cargo-swift --locked` (research: 0.11.0+)
- [ ] **i0.7** Verify all Apple Rust targets installed (already pinned in `rust-toolchain.toml`):
  ```sh
  rustup target list --installed | grep apple-ios
  # expect: aarch64-apple-ios, aarch64-apple-ios-sim, x86_64-apple-ios
  ```

## Phase i1 — Build the `.xcframework`

**Files:**
- Generated: `core/target/SatsPriceCore/` (cargo-swift output)
- Generated: `app/ios/SatsPriceCore.xcframework/`

**Steps:**
- [ ] **i1.1** From `core/`: `cargo swift package --platforms ios --name SatsPriceCore --release`
  - Produces: `SatsPriceCore/Package.swift`, `SatsPriceCore/RustFramework.xcframework`, `SatsPriceCore/Sources/SatsPriceCore/SatsPriceCore.swift` (the UniFFI Swift bindings)
- [ ] **i1.2** Move/symlink output to `app/ios/SatsPriceCore/` so the Xcode project can reference it as a local SwiftPM dep
- [ ] **i1.3** Add a `just build-ios` recipe wrapping i1.1 (already stubbed in `justfile` from Phase 0)
- [ ] **i1.4** Verify symbol presence: `nm -gU app/ios/SatsPriceCore/RustFramework.xcframework/ios-arm64/RustFramework.framework/RustFramework | grep _uniffi`
- [ ] **i1.5** Commit: `build(ios): produce SatsPriceCore.xcframework via cargo-swift`

## Phase i2 — Extend KMP project with iOS targets

**Files modified:**
- `app/composeApp/build.gradle.kts` — add iOS targets to `kotlin {}` block
- Create: `app/composeApp/src/iosMain/kotlin/price/sats/MainViewController.kt`

**Steps:**
- [ ] **i2.1** In `composeApp/build.gradle.kts`, add inside `kotlin {}`:
  ```kotlin
  listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
      target.binaries.framework {
          baseName = "ComposeApp"
          isStatic = true
      }
  }
  sourceSets["iosMain"].dependencies { /* nothing platform-specific yet */ }
  ```
- [ ] **i2.2** Write `MainViewController.kt`:
  ```kotlin
  package price.sats
  import androidx.compose.ui.window.ComposeUIViewController
  fun MainViewController() = ComposeUIViewController { App() }
  ```
- [ ] **i2.3** `gradle :composeApp:embedAndSignAppleFrameworkForXcode` should succeed (Xcode-invoked task)
- [ ] **i2.4** Commit: `feat(app): add iOS targets to composeApp`

## Phase i3 — Bridge UniFFI to Swift (replacing JNA path)

**Files:**
- `app/composeApp/src/commonMain/kotlin/price/sats/PriceCore.kt` — `expect class`
- `app/composeApp/src/androidMain/kotlin/price/sats/PriceCore.android.kt` — `actual` wrapping JNA-generated `SatsPriceCore`
- `app/composeApp/src/iosMain/kotlin/price/sats/PriceCore.ios.kt` — `actual` wrapping ObjC-generated `SatsPriceCore`

**Why:** the UniFFI-generated Kotlin uses JNA (JVM-only). The Swift bindings
hand the same surface to Kotlin/Native via the `.xcframework`. The `expect/actual`
pattern lets `commonMain` UI code stay platform-agnostic.

**Steps:**
- [ ] **i3.1** Move the existing JNA-based `androidMain` UniFFI binding inside an `actual` wrapper
- [ ] **i3.2** Wire the Kotlin/Native ObjC binding for iOS via the cinterop step (cargo-swift's Package.swift exposes ObjC interfaces)
- [ ] **i3.3** Refactor `App.kt` calls in `commonMain` to use the `expect` interface
- [ ] **i3.4** `gradle :composeApp:assembleDebug` still green on Android side
- [ ] **i3.5** Commit: `refactor: expect/actual PriceCore bridge for platform parity`

## Phase i4 — Xcode app shell + SwiftUI host

**Files:**
- Create: `app/iosApp/iosApp.xcodeproj`
- Create: `app/iosApp/iosApp/iOSApp.swift`
- Create: `app/iosApp/iosApp/Info.plist`
- Create: `app/iosApp/iosApp/Assets.xcassets/`

**Steps:**
- [ ] **i4.1** In Xcode: New Project → iOS App → "iosApp" → directory `app/iosApp` → SwiftUI lifecycle
- [ ] **i4.2** Add Swift Package dependency: `app/ios/SatsPriceCore/Package.swift` (local)
- [ ] **i4.3** Add Run Script build phase before "Compile Sources" running `gradle -p ../.. :composeApp:embedAndSignAppleFrameworkForXcode`
- [ ] **i4.4** Embed and sign the `ComposeApp.framework` in Build Phases → Frameworks
- [ ] **i4.5** Implement `iOSApp.swift` with `ComposeView` (a `UIViewControllerRepresentable` wrapping `MainViewControllerKt.MainViewController()`)
- [ ] **i4.6** Build + run on iOS Simulator (iPhone 16) — verify "Hello from Rust" via `supportedFiats()`
- [ ] **i4.7** Commit: `feat(ios): Xcode shell + SwiftUI host calling Compose Multiplatform`

## Phase i5 — TLS on iOS (rustls-platform-verifier)

**Why:** unlike Android (which needs `init_hosted(JNIEnv, Context)`), iOS gets
the platform verifier for free via `SecTrust` — but the rustls crypto provider
still needs to be installed once on app startup.

**Steps:**
- [ ] **i5.1** Enable the `mobile-tls` feature in `core/ffi`'s release builds for iOS targets (already conditional in `lib.rs`)
- [ ] **i5.2** Run a real HTTPS fetch from the simulator and confirm it succeeds (test: `core.fetchPrice("usd")` returns a real price)
- [ ] **i5.3** Capture Console.app logs to confirm `tracing` output appears via `oslog`
- [ ] **i5.4** Commit: `feat(ios): real HTTPS fetch via rustls-platform-verifier`

## Phase i6 — Privacy Manifest (App Store gate)

**Why:** since May 2024 Apple rejects builds without a `PrivacyInfo.xcprivacy`
that declares any "required reason API" usage. `cargo-swift` does not generate
this file — you must author it manually.

**Files:**
- Create: `app/iosApp/iosApp/PrivacyInfo.xcprivacy`

**Steps:**
- [ ] **i6.1** Inventory which APIs your Rust core (via reqwest/tokio/etc.) touches: `mach_absolute_time`, `stat()`, file timestamps, `UserDefaults`, `system_boottime`. Use `nm`/`otool` on the xcframework slice.
- [ ] **i6.2** For each, add a `NSPrivacyAccessedAPICategory*` reason code to the manifest with the correct Apple-allowed reason string
- [ ] **i6.3** Declare `NSPrivacyCollectedDataTypes = []` and `NSPrivacyTracking = false` (we genuinely don't collect or track)
- [ ] **i6.4** Validate with Xcode's "Privacy Manifest" report (Build → Inspect Privacy Manifest)
- [ ] **i6.5** Commit: `chore(ios): PrivacyInfo.xcprivacy declaring read-only API uses`

## Phase i7 — Polish & TestFlight prep

- [ ] **i7.1** App icon set (Assets.xcassets) — 1024×1024 + auto-derived
- [ ] **i7.2** Launch screen storyboard
- [ ] **i7.3** Bundle ID + signing identity configured (Developer Program)
- [ ] **i7.4** Bump version to `0.1.0`, archive, upload via Xcode Organizer
- [ ] **i7.5** Configure TestFlight metadata (test info, contact, beta description)
- [ ] **i7.6** GitHub Action `build-ios` on `macos-latest` — produces signed archive on tag
- [ ] **i7.7** Tag `v0.1.0-ios` after first TestFlight build accepts

## Phase i8 — App Store submission

- [ ] **i8.1** App Store Connect listing: title, subtitle, keywords, description
- [ ] **i8.2** Screenshots: iPhone 6.7", 6.5", iPad 12.9", iPad 11"
- [ ] **i8.3** Privacy policy URL (host on the GitHub Pages of the repo)
- [ ] **i8.4** "Bitcoin price viewer; no wallet/keys" — clarify in description to ease review (Apple historically scrutinizes crypto apps)
- [ ] **i8.5** Submit for review; expect 24–48 h turnaround in 2026
- [ ] **i8.6** Tag `v0.1.0` when public

---

## Hardware/host requirements (recap)

| Item | Required | Notes |
|---|---|---|
| macOS | Sequoia 15+ | Xcode 16 minimum |
| Xcode | 16+ | Full app, not CLT |
| Apple Silicon Mac | Strongly preferred | x86_64 builds still work but slow |
| Apple Developer Program | For TestFlight/App Store | $99/year |
| iOS device for sideloading | Optional | Simulator suffices for dev |

## Known iOS-specific gotchas (May 2026)

- **`isStatic = true`** required on the iOS framework when consuming multiple KMP modules in one Xcode target — duplicate symbols otherwise.
- **`embedAndSignAppleFrameworkForXcode`** must run as a Run Script build phase *before* "Compile Sources" — otherwise Xcode picks up the previous build's framework.
- **iOS background thread coroutines:** Compose runs on main thread; `Dispatchers.Default` is a worker pool on iOS-Native — accidental UI access from background still crashes.
- **Resource access:** the generated `Res.drawable.foo` from `commonMain/composeResources/` works; don't reach for `R.*` or iOS's `Bundle.main.url(forResource:)`.
- **CMP iOS overhead** is ~9 MB; for size-sensitive apps consider native SwiftUI screens with KMP only for logic.
- **Swift Export** (Kotlin 2.2.20 default-on) gives nicer Swift APIs from Kotlin but doesn't yet cover all language features — you may need `@ObjCName` for Swift-friendly naming of trickier types.
