# price-sources fuzz harnesses

`cargo-fuzz` targets for the four third-party price-source JSON parsers
shipped in [`satsprice-price-sources`](..). Each parser is hit on every
app launch, so a parser panic effectively bricks the product for the user
— these harnesses are the cheapest insurance we have against that.

## Targets

| Target      | Function under test                            |
| ----------- | ---------------------------------------------- |
| `coinbase`  | `coinbase::parse_response(&[u8])`              |
| `kraken`    | `kraken::parse_response(&[u8])`                |
| `bitstamp`  | `bitstamp::parse_response(&[u8])`              |
| `coingecko` | `coingecko::parse_response(&[u8], "usd")`     |

The harnesses are intentionally minimal: they call the parser and drop the
result. The fuzzer only cares that the parser never panics — any
`Err(SourceError::Parse)` is fine.

## Why a separate crate (not a workspace member)

`cargo-fuzz` builds with nightly-only flags (e.g. `-Zsanitizer=address`).
Keeping `core/price-sources/fuzz/` outside the root `core/` workspace lets
the rest of the workspace stay on the stable channel (MSRV 1.85, per
`core/Cargo.toml`).

## Running locally

You need:

- `rustup install nightly` (one-time)
- `cargo install cargo-fuzz` (one-time)

Then, from the repo root:

```bash
# Smoke-run any target for 30s.
cargo +nightly fuzz run --fuzz-dir core/price-sources/fuzz coinbase -- -max_total_time=30
cargo +nightly fuzz run --fuzz-dir core/price-sources/fuzz kraken    -- -max_total_time=30
cargo +nightly fuzz run --fuzz-dir core/price-sources/fuzz bitstamp  -- -max_total_time=30
cargo +nightly fuzz run --fuzz-dir core/price-sources/fuzz coingecko -- -max_total_time=30

# Or just build all targets without running them.
cargo +nightly fuzz build --fuzz-dir core/price-sources/fuzz
```

If you prefer to `cd` into the fuzz dir, the equivalent is:

```bash
cd core/price-sources/fuzz
cargo +nightly fuzz run coinbase -- -max_total_time=30
```

## Adding corpus inputs

Seed corpora live under `core/price-sources/fuzz/corpus/<target>/` (gitignored
by default — bring your own). Drop one file per input. Real-world response
bodies make the best seeds; the existing `wiremock` fixtures in
`core/price-sources/src/<target>.rs` tests are good starting points.

```bash
mkdir -p core/price-sources/fuzz/corpus/coinbase
echo '{"data":{"base":"BTC","currency":"USD","amount":"67432.10"}}' \
  > core/price-sources/fuzz/corpus/coinbase/spot-usd.json
```

## When the fuzzer finds a crash

`cargo-fuzz` writes the offending input to
`core/price-sources/fuzz/artifacts/<target>/crash-<hash>`. To reproduce:

```bash
cargo +nightly fuzz run --fuzz-dir core/price-sources/fuzz coinbase \
  core/price-sources/fuzz/artifacts/coinbase/crash-<hash>
```

The CI workflow (`.github/workflows/fuzz.yml`) uploads the `artifacts/`
directory automatically when a target crashes, then fails the job. Fix the
parser, add the crash input as a corpus regression seed, and re-run.

## CI

Two triggers (see `.github/workflows/fuzz.yml`):

- **PR runs** — only when the PR touches `core/price-sources/**`. Smoke
  budget: 60s per target. Just verifies the harness still builds and runs.
- **Nightly schedule** — 04:00 UTC, 10 min per target. The real bug-hunting
  pass.

Plus `workflow_dispatch` for ad-hoc runs.
