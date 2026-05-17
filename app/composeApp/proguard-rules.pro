# R8 / ProGuard keep-rules for the SpotPrice Android release build.
#
# Why this file exists
# --------------------
# Release builds enable R8 (shrink + obfuscate). R8 walks bytecode statically
# from `Application#onCreate` to decide which classes are reachable. Two things
# in this app are *not* reachable from bytecode and would therefore be stripped,
# crashing on first FFI call:
#
#   1. UniFFI's generated Kotlin bindings call into the Rust .so via JNA. The
#      `UniffiLib` interface is loaded at runtime by `Native.load(...)`; R8 only
#      sees the interface as "declared, never instantiated" and may drop the
#      methods or rename them, breaking the JNI symbol lookup.
#   2. JNA itself (`com.sun.jna.*`) uses reflection on `Structure` field order,
#      `Callback` interface methods, and native-method names. Stripped fields →
#      `IllegalArgumentException: Structure ... has no fields`.
#
# The rules below mirror Mozilla's application-services consumer rules
# (https://github.com/mozilla/application-services/blob/main/proguard-rules-consumer-jna.pro),
# which is the battle-tested reference for this exact stack (Rust + UniFFI +
# JNA inside Firefox / Fenix).


# ---------------------------------------------------------------------------
# JNA
# ---------------------------------------------------------------------------
# Source: https://github.com/java-native-access/jna/blob/master/www/FrequentlyAskedQuestions.md#jna-on-android
# `java.awt.*` is referenced by JNA on the JVM but never present on Android;
# `-dontwarn` suppresses the (otherwise build-failing) missing-class warnings.
# The three `-keep` lines protect JNA's own classes plus any user-defined
# `Structure` / `Callback` subclasses (UniFFI generates dozens of these) from
# being renamed or shrunk away.
-dontwarn java.awt.*
-keep class com.sun.jna.* { *; }
-keep class * extends com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }


# ---------------------------------------------------------------------------
# UniFFI generated bindings
# ---------------------------------------------------------------------------
# `cargo run --bin uniffi-bindgen` emitted Kotlin bindings into
# `app/composeApp/src/androidMain/kotlin/price/sats/core/satsprice_ffi.kt`,
# i.e. package `price.sats.core`. (Other projects put bindings under `uniffi.*`
# — we don't, so we keep our actual package.) Everything in there is either a
# JNA `Structure`/`Callback` (already covered above) or a top-level helper /
# `UniffiLib` interface invoked reflectively. Keeping the whole package is the
# only safe option; the bindings are tiny relative to the Rust .so anyway.
-keep class price.sats.core.** { *; }
-keepclassmembers class price.sats.core.** { *; }

# Annotations on the bindings (e.g. `@Structure.FieldOrder`) drive JNA's
# field-layout decisions at runtime, so all annotation attributes must survive.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleTypeAnnotations,RuntimeInvisibleTypeAnnotations
-keepattributes AnnotationDefault,InnerClasses,EnclosingMethod,Signature


# ---------------------------------------------------------------------------
# Koin (DI)
# ---------------------------------------------------------------------------
# Koin uses constructor injection via lambdas (no reflection on user types), so
# in principle it's R8-clean. But koin-android touches `android.app.Application`
# / `ComponentActivity` via reflection in a handful of helper paths
# (e.g. `getKoin()` extensions). Keeping the framework classes themselves
# costs almost nothing and avoids subtle R8-fullMode crashes.
-keep class org.koin.** { *; }
-keep interface org.koin.** { *; }
-dontwarn org.koin.**


# ---------------------------------------------------------------------------
# multiplatform-settings
# ---------------------------------------------------------------------------
# `com.russhwolf.settings.SharedPreferencesSettings` looks up its delegate via
# Android's `Context.getSharedPreferences` (not reflection on user types), so
# the library is generally R8-safe. The keep below is defensive against future
# refactors that might add a reflective factory.
-keep class com.russhwolf.settings.** { *; }
-dontwarn com.russhwolf.settings.**


# ---------------------------------------------------------------------------
# Compose / AGP defaults
# ---------------------------------------------------------------------------
# AGP's `proguard-android-optimize.txt` already ships the Compose / Kotlin
# stdlib / kotlinx.coroutines / AndroidX keep-rules. We don't override those
# here. If a Compose-related crash surfaces post-minify, add a targeted rule
# rather than blanket-keeping `androidx.compose.**`.
