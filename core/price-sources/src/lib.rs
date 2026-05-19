//! Direct HTTPS adapters for BTC spot-price feeds. Each adapter implements
//! [`PriceSource`] and returns a normalized [`RawQuote`].
//!
//! TLS configuration is the caller's responsibility — adapters take an
//! `Arc<reqwest::Client>` so production can wire in `rustls-platform-verifier`
//! while tests can use plain HTTP against `wiremock`.

// Modules are `pub` so that out-of-crate consumers (notably the cargo-fuzz
// harnesses under `core/price-sources/fuzz/`) can reach the per-source
// `parse_response` helpers directly. End users should still go through
// `PriceSource::fetch` via the re-exports below.
#[doc(hidden)]
pub mod bitstamp;
#[doc(hidden)]
pub mod coinbase;
#[doc(hidden)]
pub mod coingecko;
mod error;
#[doc(hidden)]
pub mod kraken;

pub use bitstamp::BitstampSource;
pub use coinbase::CoinbaseSource;
pub use coingecko::CoingeckoSource;
pub use error::SourceError;
pub use kraken::KrakenSource;

use async_trait::async_trait;
use satsprice_aggregator::RawQuote;
use std::sync::Arc;

/// Maximum response body size accepted from any exchange. A BTC spot-price
/// JSON payload is on the order of a few hundred bytes; 64 KiB is generous
/// headroom while preventing an arbitrarily large response (from a
/// compromised exchange, MITM, or BGP hijack) from buffering into heap and
/// OOM-killing the app process.
pub(crate) const MAX_RESPONSE_BYTES: usize = 64 * 1024;

/// Buffer a response body up to `max_bytes` and return it as `Vec<u8>`.
///
/// Refuses early if `Content-Length` is declared and exceeds the cap.
/// Streams the body chunk-by-chunk via [`reqwest::Response::chunk`] so a
/// missing or lying `Content-Length` cannot trigger an unbounded
/// `bytes()` allocation. As soon as the running total would exceed the
/// cap the function returns `SourceError::Parse`, freeing the partial
/// buffer.
pub(crate) async fn read_body_capped(
    mut resp: reqwest::Response,
    max_bytes: usize,
) -> Result<Vec<u8>, SourceError> {
    if let Some(declared) = resp.content_length() {
        if declared as usize > max_bytes {
            return Err(SourceError::Parse(format!(
                "response too large: declared {declared} bytes (cap {max_bytes})"
            )));
        }
    }
    let mut buf: Vec<u8> = Vec::new();
    while let Some(chunk) = resp.chunk().await? {
        if buf.len() + chunk.len() > max_bytes {
            return Err(SourceError::Parse(format!(
                "response exceeded {max_bytes} bytes"
            )));
        }
        buf.extend_from_slice(&chunk);
    }
    Ok(buf)
}

#[async_trait]
pub trait PriceSource: Send + Sync {
    fn name(&self) -> &'static str;
    async fn fetch(&self, fiat: &str) -> Result<RawQuote, SourceError>;
}

/// Build the default set of sources, sharing one `reqwest::Client`.
pub fn all_sources(client: Arc<reqwest::Client>) -> Vec<Arc<dyn PriceSource>> {
    vec![
        Arc::new(CoingeckoSource::new(client.clone())),
        Arc::new(CoinbaseSource::new(client.clone())),
        Arc::new(KrakenSource::new(client.clone())),
        Arc::new(BitstampSource::new(client)),
    ]
}

pub(crate) fn now_unix() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
