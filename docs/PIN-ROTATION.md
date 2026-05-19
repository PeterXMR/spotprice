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

| Host                | Leaf notAfter | `expiration` in XML | Issuer (intermediate)      |
|---------------------|---------------|---------------------|----------------------------|
| api.kraken.com      | 2026-07-31    | **2026-07-26**      | Google Trust Services WE1  |
| api.coingecko.com   | 2026-08-03    | 2026-07-29          | Google Trust Services WE1  |
| api.coinbase.com    | 2026-08-15    | 2026-08-10          | Google Trust Services WE1  |
| www.bitstamp.net    | 2026-11-07    | 2026-11-02          | DigiCert EV RSA CA G2      |

**The driving deadline is api.kraken.com on 2026-07-26.** A release with
refreshed pins MUST be on GitHub Releases (and have had time to propagate to
F-Droid + users) before that date, or pinning silently disables for Kraken.

## How to regenerate pins

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

## CI: scheduled pin-expiry watchdog

Add `.github/workflows/pin-expiry-check.yml` (suggested skeleton — not yet
wired in):

```yaml
name: Pin expiry check
on:
  schedule:
    - cron: '0 9 * * 1'    # every Monday 09:00 UTC
  workflow_dispatch:
jobs:
  check:
    runs-on: ubuntu-latest
    permissions:
      issues: write
      contents: read
    steps:
      - uses: actions/checkout@v4
      - name: Check pin-set expirations
        run: |
          set -euo pipefail
          THRESHOLD_DAYS=30
          NOW=$(date -u +%s)
          STALE=()
          # Extract every expiration="YYYY-MM-DD" from the XML.
          grep -oE 'expiration="[0-9-]+"' \
            app/composeApp/src/androidMain/res/xml/network_security_config.xml \
            | sed 's/expiration="//;s/"$//' | sort -u > /tmp/exps.txt
          while read -r EXP; do
            EXP_TS=$(date -u -d "$EXP" +%s)
            DAYS=$(( (EXP_TS - NOW) / 86400 ))
            echo "$EXP -> $DAYS days"
            if [ "$DAYS" -lt "$THRESHOLD_DAYS" ]; then
              STALE+=("$EXP ($DAYS days)")
            fi
          done < /tmp/exps.txt
          if [ ${#STALE[@]} -gt 0 ]; then
            printf 'STALE_PINS=%s\n' "${STALE[*]}" >> "$GITHUB_ENV"
          fi
      - name: Open issue if stale
        if: env.STALE_PINS != ''
        uses: actions/github-script@v7
        with:
          script: |
            const stale = process.env.STALE_PINS;
            await github.rest.issues.create({
              owner: context.repo.owner,
              repo: context.repo.repo,
              title: `TLS pins approaching expiry: ${stale}`,
              body: `One or more <pin-set expiration="..."> values in network_security_config.xml are within 30 days of expiry. Refresh pins per docs/PIN-ROTATION.md and cut a release before the earliest date.\n\nStale: ${stale}`,
              labels: ['security', 'maintenance']
            });
```

This gives ~4 weeks of lead time on the earliest pin, which comfortably covers
"write a PR, get it reviewed, cut a release, wait for users to update."
