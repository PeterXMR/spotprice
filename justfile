# SpotPrice — task runner
# Run `just` (no args) to list recipes.

default:
    @just --list

# ---- Toolchain bootstrap ----

install-tools:
    cargo install cargo-ndk --locked
    cargo install cargo-swift --locked
    cargo install uniffi-bindgen --locked
    rustup show

# ---- Rust core ----

# Run all unit + property tests
test:
    cargo test --manifest-path core/Cargo.toml --all-features

# Lint with clippy (deny warnings — keep the core clean)
lint:
    cargo clippy --manifest-path core/Cargo.toml --all-targets -- -D warnings

# Format check
fmt-check:
    cargo fmt --manifest-path core/Cargo.toml --all -- --check

# Format
fmt:
    cargo fmt --manifest-path core/Cargo.toml --all

# ---- Supply chain ----

# Enforce license policy + duplicate/source bans (config: deny.toml)
deny:
    cargo deny --manifest-path core/Cargo.toml check

# Verify every ignore entry in .cargo/audit.toml is still justified — has its
# suppression-meta line, is inside its re-check date, and names a crate that is
# still absent from the build graph. Mirrors the audit-suppressions job in
# .github/workflows/security.yml.
audit-suppressions:
    python3 scripts/check_audit_suppressions.py

# Check core/Cargo.lock against the RustSec advisory DB.
#
# Must run from the repo root: cargo-audit reads `<cwd>/.cargo/audit.toml` and,
# unlike Cargo with config.toml, does NOT walk up to ancestor directories. just
# executes recipes from the justfile's directory, so this is always correct —
# but a bare `cd core && cargo audit` misses the suppression list and reports
# findings CI does not. core/.cargo/audit.toml symlinks to the root file so
# that invocation agrees too.
audit: audit-suppressions
    cargo audit --file core/Cargo.lock

# ---- Cross-compile for Android ----

# Build .so for all 4 Android ABIs (release). Output: app/android/src/main/jniLibs/<abi>/
build-android:
    cargo ndk \
        -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 \
        -o app/android/src/main/jniLibs \
        build --manifest-path core/ffi/Cargo.toml --release

# Verify 16 KB page alignment on built .so (required for Android 15+)
verify-android-alignment:
    @for f in app/android/src/main/jniLibs/arm64-v8a/*.so; do \
        echo "Checking $f"; \
        readelf -l $f | grep LOAD | head -2; \
    done

# ---- Cross-compile for iOS (full Xcode required) ----

# Build .xcframework + SwiftPM package for iOS device + simulators
build-ios:
    cargo swift package --platforms ios --name SatsPriceCore --release \
        --manifest-path core/ffi/Cargo.toml

# ---- KMP / Compose Multiplatform ----

# Run Android app on connected device/emulator
run-android: build-android
    cd app && ./gradlew :android:installDebug

# Open the iOS project in Xcode
open-ios:
    open app/ios/SpotPrice.xcodeproj

# ---- Generate Kotlin bindings from UniFFI ----

bindings-kotlin:
    cd core && cargo run --bin uniffi-bindgen -- generate \
        --library target/release/libsatsprice_ffi.dylib \
        --language kotlin \
        --out-dir ../app/domain/build/generated/uniffi

# ---- Clean everything ----

clean:
    cargo clean --manifest-path core/Cargo.toml
    rm -rf app/android/src/main/jniLibs
    rm -rf app/ios/SatsPriceCore
    cd app && ./gradlew clean 2>/dev/null || true
