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
