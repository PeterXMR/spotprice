#![no_main]

//! Fuzz target: Coinbase `/v2/prices/BTC-XXX/spot` JSON → Decimal parser.
//!
//! Every app launch hits this endpoint; a panic here = the user can't see
//! their Bitcoin price. We assert only that `parse_response` never panics —
//! returning `Err(SourceError::Parse)` for garbage input is fine.

use libfuzzer_sys::fuzz_target;
use satsprice_price_sources::coinbase;

fuzz_target!(|data: &[u8]| {
    let _ = coinbase::parse_response(data);
});
