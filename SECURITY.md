# Security Policy

SatsPrice is a small, volunteer-maintained open-source project. This document
describes how to report vulnerabilities and what you can reasonably expect in
return. It is written for security researchers and F-Droid reviewers, not
corporate compliance teams.

## Threat Model

SatsPrice is a **read-only Bitcoin price viewer**. Concretely:

- It does **not** custody Bitcoin, hold keys, sign transactions, or interact
  with any wallet.
- It does **not** collect telemetry, analytics, crash reports, or identifiers.
- It has **no backend, no accounts, no server-side component** under our control.
- On each price refresh it makes up to **four outbound HTTPS calls** — to
  Coinbase, Kraken, Bitstamp, and CoinGecko public price endpoints — and
  aggregates the results locally with median + sigma-rejection.

The trust boundary is therefore: **the user's device, the OS network stack,
the four upstream price APIs, and the TLS certificate authorities that
underpin them.** Everything inside the app runs locally with no persisted user
data beyond the last-known price cache.

### In scope

- Vulnerabilities in the Kotlin/Compose UI or Android shell shipped from this
  repository (e.g. exported components, intent handling, file URI handling,
  WebView misuse — though we don't currently ship a WebView).
- Vulnerabilities in the Rust core (`core/`) or its UniFFI surface, including
  panics reachable from untrusted API responses, integer/decimal handling
  bugs, or memory-safety issues. (The Rust workspace forbids `unsafe_code`
  at compile time, so memory-safety findings in *our* Rust code would be a
  configuration regression.)
- Network-layer issues in our code: missing TLS validation, certificate
  pinning bypass, request leakage to unintended hosts, plaintext fallback.
- Build/release supply-chain issues affecting artifacts published under this
  repository's GitHub Releases (signing, reproducibility, dependency
  confusion in our manifests).
- Privacy regressions: any code path that exfiltrates data beyond the four
  documented price endpoints.

### Out of scope

Please do not file these — we will close them without action:

- **Manipulation of upstream price APIs.** Coordinated lies from all four of
  Coinbase, Kraken, Bitstamp, and CoinGecko would mislead the displayed
  price. Our sigma-rejection mitigates single-source outliers but cannot
  defend against a coordinated attack on every source. This is an accepted
  residual risk of any client that aggregates third-party feeds.
- **Denial of service of upstream price APIs.** If a provider is down, the
  app shows the cached last-known price; this is by design.
- **Attacks requiring physical access** to an unlocked device, or attacks
  presupposing a compromised OS / rooted device / malicious keyboard.
- **Social engineering** of users or maintainers.
- **Side-channel attacks** (timing, power, EM) against the device.
- **Theoretical findings without a demonstrated impact** on the app's
  behaviour (e.g. "library X has CVE-Y" with no reachable code path).
- **Missing security headers / hardening on third-party domains** we merely
  call as a client.
- **Reports generated solely by automated scanners** with no manual triage.

## Supported Versions

Only the **latest tagged release** on this repository's GitHub Releases page
is supported. There are no backports to older versions. If you are running an
older build, please update before reporting — the issue may already be fixed.

| Version           | Supported          |
| ----------------- | ------------------ |
| Latest release    | Yes                |
| Anything older    | No                 |

## Reporting a Vulnerability

**Preferred channel: [GitHub Security Advisories](https://github.com/PeterXMR/satsprice/security/advisories/new).**
This opens a private discussion thread visible only to you and the
maintainers, lets us collaborate on a fix in a private fork, and integrates
cleanly with CVE issuance if warranted.

**Fallback:** if the maintainer is unresponsive for more than 14 days on a
GHSA report, you may email the address listed on the maintainer's GitHub
profile. No PGP key is published; if you have something genuinely sensitive
that needs encryption in transit, mention this in a first-contact email
without details and we will arrange a key out-of-band.

When reporting, please include:

- A short description of the issue and the security impact.
- The affected version (git commit or release tag).
- Reproduction steps or a minimal proof-of-concept.
- Your assessment of severity (low / medium / high) and why.

## What to Expect

These targets are **aspirational, not contractual** — this is a volunteer
project with no paid on-call rotation:

- **Acknowledgement:** within 7 days of report.
- **Triage and initial assessment:** within 14 days.
- **Fix for high-severity issues:** released within 30 days where feasible.
- **Public disclosure:** coordinated with you after a fix ships, typically
  via the GHSA advisory being published. We will credit you by the name or
  handle you choose, or keep you anonymous if you prefer.

If a fix requires longer than 30 days (e.g. it depends on an upstream Rust
crate or Android platform change), we will keep you updated on the GHSA
thread.

## What This Project Does Not Offer

To set expectations honestly:

- **No bug bounty.** There is no monetary reward, swag, or hall-of-fame
  page. Reports are appreciated and credited; that is the extent of it.
- **No CVE coordination commitments** for low-severity issues. We will
  request a CVE for high-severity issues where appropriate.
- **No SLA guarantees.** The timelines above are best-effort.
- **No support for forks or repackaged builds.** If you obtained the app
  from somewhere other than this repository's GitHub Releases (or, once
  available, F-Droid / Obtainium pointing at this repository), report the
  issue to whoever distributed that build. We can only speak for artifacts
  we sign and publish ourselves.

## Distribution and Signing

SatsPrice is GPL-3.0-or-later and is currently distributed via:

- **GitHub Releases** (sideload APK) — signed with the maintainer's release
  key; the public certificate fingerprint is published in the release notes
  of each tagged version.
- **F-Droid** (planned) — will be built reproducibly by F-Droid's
  infrastructure from this repository's source.
- **Obtainium** (planned) — will pull the signed APK from GitHub Releases.

If you receive an APK claiming to be SatsPrice from any other source,
treat it as untrusted.
