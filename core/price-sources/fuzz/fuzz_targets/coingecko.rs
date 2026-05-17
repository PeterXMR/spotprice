#![no_main]

//! Fuzz target: CoinGecko `/api/v3/simple/price?ids=bitcoin&vs_currencies=XXX`
//! JSON → Decimal parser.
//!
//! Every app launch hits this endpoint; a panic here = the user can't see
//! their Bitcoin price. The fiat key is fixed to "usd" — production callers
//! always pass a lowercased ISO-4217 code, so we don't need to fuzz that
//! axis here. We assert only that `parse_response` never panics; returning
//! `Err(SourceError::Parse)` for garbage input is fine.

use libfuzzer_sys::fuzz_target;
use satsprice_price_sources::coingecko;

fuzz_target!(|data: &[u8]| {
    let _ = coingecko::parse_response(data, "usd");
});
