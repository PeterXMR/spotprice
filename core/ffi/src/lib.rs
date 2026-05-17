//! UniFFI surface — the only crate exposed to Kotlin/Swift.
//!
//! Public API:
//! * [`SatsPriceCore::new`] — constructs the shared core with all default sources.
//! * [`SatsPriceCore::fetch_price`] — async; fan-outs to all sources, aggregates, caches.
//! * [`SatsPriceCore::convert_sats_to_fiat`] / [`convert_fiat_to_sats`] — synchronous math.
//! * [`SatsPriceCore::supported_fiats`] — static list of recommended fiat codes.

use rust_decimal::Decimal;
use satsprice_aggregator::{aggregate, AggregateOpts, AggregatedPrice, RawQuote};
use satsprice_cache::PriceCache;
use satsprice_convert::{btc_to_sats, fiat_to_sats, sats_to_btc, sats_to_fiat};
use satsprice_price_sources::{all_sources, PriceSource};
use std::str::FromStr;
use std::sync::Arc;

uniffi::setup_scaffolding!();

#[derive(Clone, Debug, uniffi::Record)]
pub struct PriceSnapshot {
    /// Decimal price as a string — exact representation, no f64 across FFI.
    pub price: String,
    pub fiat: String,
    pub sources_used: u32,
    /// True when this was served from cache after a failed refresh.
    pub stale: bool,
    pub timestamp: u64,
    /// Largest deviation among contributing sources, as a fraction (string).
    pub dispersion: String,
}

// Tuple variants (positional) — avoids generating Kotlin `val message: String`
// fields that collide with `Throwable.message: String?` on the sealed-class side.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    #[error("insufficient sources reachable (got {0}, need 3+)")]
    InsufficientSources(u32),

    #[error("network error: {0}")]
    Network(String),

    #[error("invalid input: {0}")]
    InvalidInput(String),

    #[error("conversion error: {0}")]
    Conversion(String),
}

#[derive(uniffi::Object)]
pub struct SatsPriceCore {
    sources: Vec<Arc<dyn PriceSource>>,
    cache: PriceCache,
}

#[uniffi::export(async_runtime = "tokio")]
impl SatsPriceCore {
    /// Production constructor — bundles every default source.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        let client = Arc::new(build_http_client());
        Arc::new(Self {
            sources: all_sources(client),
            cache: PriceCache::default_tuned(),
        })
    }

    /// Refresh the price for `fiat` across all sources.
    /// Returns a cached snapshot when a fresh entry exists; otherwise fans out.
    pub async fn fetch_price(&self, fiat: String) -> Result<PriceSnapshot, FfiError> {
        let fiat = fiat.to_lowercase();
        if let Some(cached) = self.cache.get(&fiat).await {
            return Ok(snapshot_from_aggregated(cached, false));
        }

        let mut handles = Vec::with_capacity(self.sources.len());
        for source in &self.sources {
            let source = source.clone();
            let fiat = fiat.clone();
            handles.push(tokio::spawn(async move { source.fetch(&fiat).await }));
        }

        let mut quotes: Vec<RawQuote> = Vec::new();
        for h in handles {
            match h.await {
                Ok(Ok(q)) => quotes.push(q),
                Ok(Err(e)) => tracing::warn!(error = %e, "source failed"),
                Err(e) => tracing::warn!(error = %e, "source task panicked"),
            }
        }

        let got = quotes.len() as u32;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);

        // Relax min_sources to 1 at the consumer layer so exotic fiats
        // (e.g. PYG, ARS) that only Coingecko supports still publish a price.
        // The aggregator crate keeps its strict default for non-mobile consumers.
        let opts = AggregateOpts {
            min_sources: 1,
            ..AggregateOpts::default()
        };
        let aggregated = aggregate(quotes, &opts, now).ok_or(FfiError::InsufficientSources(got))?;
        self.cache.put(&fiat, aggregated.clone()).await;
        Ok(snapshot_from_aggregated(aggregated, false))
    }

    pub fn convert_sats_to_fiat(&self, sats: u64, price: String) -> Result<String, FfiError> {
        let price = parse_decimal(&price)?;
        let fiat = sats_to_fiat(sats, price).map_err(|e| FfiError::Conversion(e.to_string()))?;
        Ok(format!("{:.2}", fiat))
    }

    pub fn convert_fiat_to_sats(
        &self,
        fiat_amount: String,
        price: String,
    ) -> Result<u64, FfiError> {
        let fiat_amount = parse_decimal(&fiat_amount)?;
        let price = parse_decimal(&price)?;
        fiat_to_sats(fiat_amount, price).map_err(|e| FfiError::Conversion(e.to_string()))
    }

    /// BTC decimal string for the given sat count, 8 fractional digits.
    pub fn convert_sats_to_btc(&self, sats: u64) -> String {
        format!("{:.8}", sats_to_btc(sats))
    }

    /// Exact sat count for a BTC decimal string. Rejects negative / non-numeric input.
    pub fn convert_btc_to_sats(&self, btc: String) -> Result<u64, FfiError> {
        let btc = parse_decimal(&btc)?;
        btc_to_sats(btc).map_err(|e| FfiError::Conversion(e.to_string()))
    }

    /// Alphabetically-sorted set of common fiats. Coverage by source:
    /// * Major fiats (~7): all 4 sources support → real median + dispersion guard
    /// * Mid-tier (~20): Coingecko + Coinbase → 2-source median
    /// * Long tail (PYG, UYU, KES, …): Coingecko only → single-source feed
    pub fn supported_fiats(&self) -> Vec<String> {
        [
            "aed", "ars", "aud", "brl", "cad", "chf", "clp", "cny", "cop", "czk", "dkk", "egp",
            "eur", "gbp", "hkd", "huf", "idr", "ils", "inr", "jpy", "kes", "krw", "mxn", "myr",
            "ngn", "nok", "nzd", "pen", "php", "pln", "pyg", "ron", "rub", "sar", "sek", "sgd",
            "thb", "try", "twd", "uah", "usd", "uyu", "vnd", "zar",
        ]
        .iter()
        .map(|s| (*s).to_string())
        .collect()
    }
}

fn parse_decimal(s: &str) -> Result<Decimal, FfiError> {
    Decimal::from_str(s).map_err(|e| FfiError::InvalidInput(format!("could not parse '{s}': {e}")))
}

fn snapshot_from_aggregated(agg: AggregatedPrice, stale: bool) -> PriceSnapshot {
    PriceSnapshot {
        price: agg.price.to_string(),
        fiat: agg.fiat,
        sources_used: agg.sources_used,
        stale,
        timestamp: agg.timestamp,
        dispersion: agg.dispersion.to_string(),
    }
}

#[cfg(feature = "mobile-tls")]
fn build_http_client() -> reqwest::Client {
    use rustls_platform_verifier::ConfigVerifierExt;
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    let tls = rustls::ClientConfig::with_platform_verifier()
        .expect("platform verifier should initialize");
    reqwest::Client::builder()
        .user_agent("spotprice/0.1.0")
        .use_preconfigured_tls(tls)
        .build()
        .expect("reqwest client")
}

#[cfg(not(feature = "mobile-tls"))]
fn build_http_client() -> reqwest::Client {
    // rustls 0.23 requires a default crypto provider before any TLS handshake.
    // install_default is idempotent (subsequent calls return Err which we ignore).
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    reqwest::Client::builder()
        .user_agent("spotprice/0.1.0")
        .build()
        .expect("reqwest client")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn convert_sats_to_fiat_at_67k() {
        let core = SatsPriceCore::new();
        // 1 BTC == $67,000
        let result = core
            .convert_sats_to_fiat(100_000_000, "67000".into())
            .expect("convert");
        assert_eq!(result, "67000.00");
    }

    #[test]
    fn convert_fiat_to_sats_at_67k() {
        let core = SatsPriceCore::new();
        // $67 at $67k/BTC == 0.001 BTC == 100_000 sats
        let result = core
            .convert_fiat_to_sats("67".into(), "67000".into())
            .expect("convert");
        assert_eq!(result, 100_000);
    }

    #[test]
    fn convert_rejects_invalid_decimal() {
        let core = SatsPriceCore::new();
        let err = core
            .convert_sats_to_fiat(1, "banana".into())
            .expect_err("should fail");
        assert!(matches!(err, FfiError::InvalidInput(_)));
    }

    #[test]
    fn supported_fiats_includes_usd() {
        let core = SatsPriceCore::new();
        let fiats = core.supported_fiats();
        assert!(fiats.contains(&"usd".to_string()));
    }
}
