# Releasing SpotPrice

How builds get from a commit to a downloadable APK, and what's still missing
for a production-grade signed release.

## TL;DR

| Situation | Trigger | Result |
|---|---|---|
| Open or push to a PR against `main` | `pull_request` | CI builds the APK, attaches it as a workflow artifact (14-day retention). Download from the run's Summary page. |
| Merge to `main` | `push: branches: [main]` | Same — APK as workflow artifact, useful for nightly-style testing. |
| Push a tag like `v0.1.0-preview1` | `push: tags: ['v*']` | CI builds and **publishes a GitHub Release** with the APK attached. Tag has a `-suffix` → pre-release. |
| Push a tag like `v0.1.0` | `push: tags: ['v*']` | Same workflow, but the release is marked as **"Latest"** (not pre-release). |

CI workflow: [.github/workflows/android.yml](../.github/workflows/android.yml)

## Pre-release vs. release: what's the difference?

In GitHub, "pre-release" is **a flag, not a different artifact**. It:

- Adds a "Pre-release" badge on the release page.
- Excludes the release from the "Latest release" auto-tracker on the repo
  landing page and from the `releases/latest` redirect.

It does **not** affect the APK contents, the build process, or signing. A
pre-release APK is the same kind of file as a release APK — only the
discoverability differs.

This workflow decides the flag from the tag name (semver convention): any tag
with a hyphen after the version (e.g. `v1.2.3-preview1`, `v1.2.3-rc.1`,
`v1.2.3-beta.4`) is marked pre-release. Plain `v1.2.3` becomes "Latest".

## Publishing a release (today)

Decide on a tag name following [semver](https://semver.org/):

- `v0.2.0` — a normal release. Becomes "Latest" on the repo page.
- `v0.2.0-preview1`, `v0.2.0-rc.1` — pre-releases.

Then:

```sh
# From the commit you want to release (typically on main)
git tag v0.2.0
git push origin v0.2.0
```

That's it. The workflow:

1. Builds the Rust core for all 4 Android ABIs.
2. Assembles the debug APK.
3. Renames it to `SpotPrice-v0.2.0.apk`.
4. Creates a GitHub Release at `/releases/tag/v0.2.0` with auto-generated
   release notes from the commits since the previous tag.
5. Attaches the APK as a release asset.

Find the published release under [Releases](https://github.com/PeterXMR/spotprice/releases).

## Publishing a release manually (CI bypass)

If you need a release without going through CI — e.g. for a hotfix from a
machine that can build locally:

```sh
# Build all 4 ABIs locally
just build-android
cd app && ./gradlew :composeApp:assembleDebug && cd ..

# Stage the APK with a meaningful filename
cp app/composeApp/build/outputs/apk/debug/composeApp-debug.apk \
   /tmp/SpotPrice-v0.2.0.apk

# Create the release. Omit --prerelease for a real release.
gh release create v0.2.0 /tmp/SpotPrice-v0.2.0.apk \
    --title "v0.2.0" \
    --generate-notes
```

The CI workflow does exactly this, just with GitHub-hosted runners.

## What this build is NOT (yet)

The APK that [android.yml](../.github/workflows/android.yml) attaches to a
GitHub Release is still **a debug build** — fine for sideloading and ad-hoc
testing, not Play Store quality. Phase 13 (this section) added the missing
machinery for *signed* release builds; the only remaining gap is the keystore
itself.

State of the world as of Phase 13:

- **Debug-signed (`android.yml`).** The original workflow still uploads a
  debug-signed APK on tags. It's the fallback when signing secrets aren't
  configured.
- **R8 + keep-rules: DONE.** `app/composeApp/build.gradle.kts` now sets
  `isMinifyEnabled = true` + `isShrinkResources = true` on the release variant,
  with keep-rules in [`app/composeApp/proguard-rules.pro`](../app/composeApp/proguard-rules.pro)
  protecting UniFFI's generated `price.sats.core.*` bindings and JNA's
  reflection-loaded `Structure` / `Callback` paths. Rules mirror Mozilla's
  battle-tested `application-services` consumer rules.
- **Signing config: DONE.** A `signingConfigs.release` block reads from env
  vars (`RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`) and is wired to the release build type — but only
  when all four env vars are non-empty, so local `assembleDebug` still works
  with zero setup.
- **Signed release workflow: DONE.** [`release.yml`](../.github/workflows/release.yml)
  fires on tag pushes, decodes the keystore secret, and produces a signed APK
  + AAB. It's guarded by `vars.RELEASE_SIGNING_ENABLED == 'true'`, so the job
  is skipped (not failed) until the maintainer opts in by adding the secrets
  below.

## Enabling signed releases (one-time setup)

1. **Generate a keystore** locally. Keep this file safe — losing it means you
   can never publish a non-breaking update on Play Store.

   ```sh
   keytool -genkeypair -v \
     -keystore spotprice-release.jks \
     -alias spotprice \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. **Base64-encode it** so it fits in a GitHub Actions secret:

   ```sh
   base64 -i spotprice-release.jks | tr -d '\n' > spotprice-release.jks.b64
   ```

3. **Add the secrets** under Settings → Secrets and variables → Actions →
   *Repository secrets*:

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | Contents of `spotprice-release.jks.b64` |
   | `RELEASE_KEYSTORE_PASSWORD` | The keystore password from `keytool` |
   | `RELEASE_KEY_ALIAS` | `spotprice` (or whatever alias you chose) |
   | `RELEASE_KEY_PASSWORD` | The key password from `keytool` (often same as keystore password) |

4. **Set the variable** under the same page → *Variables* tab:

   | Variable | Value |
   |---|---|
   | `RELEASE_SIGNING_ENABLED` | `true` |

5. **Push a tag.** The next `v*` tag will trigger `release.yml`, which produces
   `SpotPrice-vX.Y.Z.apk` + `SpotPrice-vX.Y.Z.aab` and attaches them to the
   GitHub Release alongside the debug APK from `android.yml`.

## Roadmap to Play Store

Remaining items for actual Play Store distribution:

1. **Confirm `applicationId`** before first publish. Today the id is
   `price.sats` — once on Play Store it can't change without orphaning the
   app's reviews and installs. Verify this is the production identifier.
2. **Drop the debug-APK release step from `android.yml`** once `release.yml`
   has produced at least one signed build successfully. Keeping both during
   the transition gives a known-good fallback.
3. **Upload the AAB to Play Console** (manually for the first release, then
   consider `r0adkll/upload-google-play` to automate).

## CI architecture quick reference

The single workflow file ([.github/workflows/android.yml](../.github/workflows/android.yml)) does three things based on the trigger:

```
on push to main          → build APK, save 14-day artifact
on PR against main       → build APK, save 14-day artifact (pre-merge gate)
on push of v* tag        → build APK + publish GitHub Release with the APK
```

Caching: `Swatinem/rust-cache@v2` (cargo registry + `core/target`),
`gradle/actions/setup-gradle@v3` (`~/.gradle`), and `taiki-e/install-action`
fetches a prebuilt `cargo-ndk` binary instead of compiling it from source
(saves ~3 minutes per run).

Concurrency: in-progress runs on the same branch are cancelled on new
commits, but tag-triggered runs are **never** cancelled — every tag must
produce exactly one release.
