# Certificate Pin Rotation

SatsPrice pins the TLS leaf and issuing intermediate CA for each of the four
upstream price APIs (Coinbase, Kraken, Bitstamp, CoinGecko) via
`app/composeApp/src/androidMain/res/xml/network_security_config.xml`. This
document explains *when* pins must be refreshed and *how* to do it.

## Why this matters

We ship via GitHub Releases sideload and (planned) F-Droid, neither of which
push-updates users like Play Store does. If a pin set becomes stale and the
upstream rotates both the leaf *and* the intermediate, the app will refuse to
load prices on Android with no in-app override. We mitigate this two ways:

1. **Backup pin on the intermediate CA** — the leaf can rotate freely under the
   same intermediate without breakage. This is the common case for all four
   providers (Let's Encrypt-style 90-day rotations as well as Google Trust
   Services' 13-month leafs all stay under the same intermediate for years).
2. **`<pin-set expiration="...">`** — Android silently disables pinning for a
   host after this date and falls back to platform CA trust. We deliberately
   set it to ~5 days *before* the leaf's notAfter, so a stale APK degrades to
   "no pinning, normal HTTPS" rather than "no prices at all."

## Current pin expiration deadlines (set when pins were last refreshed)

The table and deadline sentence below are regenerated automatically by
`scripts/rotate_tls_pins.py` (run from `.github/workflows/pin-rotation.yml`).
Don't hand-edit between the `AUTO-PIN-*` markers — your changes will be
overwritten on the next rotation.

<!-- AUTO-PIN-TABLE:START -->
| Host | Leaf notAfter | `expiration` in XML | Issuer (intermediate) |
|------|---------------|---------------------|-----------------------|
| api.kraken.com | 2026-09-28 | **2026-09-23** | Google Trust Services WE1 |
| api.coingecko.com | 2026-10-01 | 2026-09-26 | Google Trust Services WE1 |
| api.coinbase.com | 2026-10-13 | 2026-10-08 | Google Trust Services WE1 |
| www.bitstamp.net | 2026-11-07 | 2026-11-02 | DigiCert EV RSA CA G2 |
<!-- AUTO-PIN-TABLE:END -->

<!-- AUTO-PIN-DEADLINE:START -->
**The driving deadline is api.kraken.com on 2026-09-23.** A release with refreshed pins MUST be on GitHub Releases (and have had time to propagate to F-Droid + users) before that date, or pinning silently disables for api.kraken.com.
<!-- AUTO-PIN-DEADLINE:END -->

## Automated rotation (preferred)

Pin rotation is automated end-to-end by **`scripts/rotate_tls_pins.py`**, run
daily by **`.github/workflows/pin-rotation.yml`**. The script reads the host
list straight from `network_security_config.xml` (so adding a `<domain-config>`
brings it under rotation with no further wiring), re-fetches each live chain,
recomputes the leaf + intermediate SPKI pins, sets `expiration` to `--lead-days`
(default 5) before the leaf's `notAfter`, bumps the "Pins last fetched" stamp,
and regenerates the table + deadline sentence above (between the `AUTO-PIN-*`
markers — don't hand-edit those).

When the live certs differ from what's pinned, the workflow opens (or updates)
a single rolling PR on the `automation/tls-pin-rotation` branch. **It never
auto-merges** — a maintainer still reviews the diff and verifies on-device
before merging. The daily cadence means a freshly rotated upstream cert is
usually turned into a PR within 24h, long before the weekly watchdog would nag.

Run it locally to preview a rotation without writing anything:

```bash
python3 scripts/rotate_tls_pins.py --dry-run
```

## How to regenerate pins manually (fallback)

Use this only if the automation is unavailable (e.g. a host blocks the runner,
or you're rotating offline). It's the same computation the script performs.

Run this for each of the four hosts. It extracts the SPKI SHA-256 base64 pin
for the leaf certificate served right now:

```bash
HOST=api.kraken.com
echo | openssl s_client -servername "$HOST" -connect "$HOST":443 -showcerts 2>/dev/null \
  | awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64
```

To also extract the intermediate (second cert in the chain) pin and the leaf's
notAfter date in one shot:

```bash
for HOST in api.coinbase.com api.kraken.com www.bitstamp.net api.coingecko.com; do
  TMP=$(mktemp -d)
  echo | openssl s_client -servername "$HOST" -connect "$HOST":443 -showcerts 2>/dev/null \
    | awk -v d="$TMP" '/-----BEGIN CERTIFICATE-----/{n++} n{print > (d"/c"n".pem")}'
  echo "=== $HOST ==="
  for i in 1 2; do
    CERT="$TMP/c$i.pem"
    [ -f "$CERT" ] || continue
    PIN=$(openssl x509 -in "$CERT" -pubkey -noout | openssl pkey -pubin -outform DER \
          | openssl dgst -sha256 -binary | openssl enc -base64)
    SUBJ=$(openssl x509 -in "$CERT" -noout -subject)
    NAFT=$(openssl x509 -in "$CERT" -noout -enddate)
    echo "  cert$i: $PIN | $SUBJ | $NAFT"
  done
  rm -rf "$TMP"
done
```

Update each `<pin-set>` block in `network_security_config.xml` with:

- the new leaf SPKI hash (first `<pin>`),
- the new intermediate SPKI hash (second `<pin>`),
- an `expiration` date set ~5 days before the new leaf's notAfter.

Bump the "Pins last fetched" comment at the top of the XML and the table above,
ship a release, and verify on a real device against a proxy like mitmproxy
(pinning should **reject** the proxy's cert).

## CI: the two pin workflows

Two scheduled workflows cover pins, with distinct jobs:

| Workflow | Schedule | Does |
|----------|----------|------|
| `.github/workflows/pin-rotation.yml` | daily 07:00 UTC | **Fixes** stale pins — re-fetches live certs and opens a PR when they differ. |
| `.github/workflows/pin-expiry-check.yml` | Mon 09:00 UTC | **Backstop nag** — opens an issue if any `expiration` is within 30 days, in case a rotation PR was missed. |

In the steady state the watchdog never fires: the rotation workflow refreshes
pins (and the maintainer merges + releases) well before the 30-day threshold.
The watchdog remains as a safety net for the cases automation can't handle on
its own — e.g. the upstream hasn't issued a replacement cert yet, or a rotation
PR has been sitting unmerged.

**Prerequisite for the rotation PR:** repo *Settings → Actions → General →
"Allow GitHub Actions to create and approve pull requests"* must be enabled, or
the `gh pr create` step returns 403. The branch push itself works regardless.

The 30-day watchdog threshold gives ~4 weeks of lead time on the earliest pin,
which comfortably covers "review the auto-PR, cut a release, wait for users to
update."
