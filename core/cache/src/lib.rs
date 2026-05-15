//! In-memory TTL cache for aggregated price snapshots, keyed by fiat code.
//!
//! Backed by [`moka::future::Cache`] for thread-safe async access.
//! Persistent fallback (sled) is intentionally deferred to v0.2 — first
//! release simply re-fetches on cold start.

use moka::future::Cache;
use satsprice_aggregator::AggregatedPrice;
use std::time::Duration;

/// Wraps a moka cache with a fixed TTL.
#[derive(Clone)]
pub struct PriceCache {
    inner: Cache<String, AggregatedPrice>,
}

impl PriceCache {
    pub fn new(ttl: Duration, max_capacity: u64) -> Self {
        let inner = Cache::builder()
            .time_to_live(ttl)
            .max_capacity(max_capacity)
            .build();
        Self { inner }
    }

    /// Default tuning: 60 s TTL, room for ~50 fiat currencies.
    pub fn default_tuned() -> Self {
        Self::new(Duration::from_secs(60), 64)
    }

    pub async fn get(&self, fiat: &str) -> Option<AggregatedPrice> {
        self.inner.get(&fiat.to_lowercase()).await
    }

    pub async fn put(&self, fiat: &str, snapshot: AggregatedPrice) {
        self.inner.insert(fiat.to_lowercase(), snapshot).await;
    }

    pub async fn invalidate_all(&self) {
        self.inner.invalidate_all();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal::Decimal;
    use rust_decimal_macros::dec;

    fn sample(fiat: &str, price: Decimal) -> AggregatedPrice {
        AggregatedPrice {
            price,
            fiat: fiat.into(),
            sources_used: 3,
            dispersion: dec!(0.001),
            timestamp: 1000,
        }
    }

    #[tokio::test]
    async fn hit_within_ttl() {
        let cache = PriceCache::new(Duration::from_secs(60), 4);
        cache.put("usd", sample("usd", dec!(67000))).await;
        let got = cache.get("usd").await.expect("hit");
        assert_eq!(got.price, dec!(67000));
    }

    #[tokio::test]
    async fn miss_after_ttl_expires() {
        let cache = PriceCache::new(Duration::from_millis(50), 4);
        cache.put("usd", sample("usd", dec!(67000))).await;
        tokio::time::sleep(Duration::from_millis(120)).await;
        // Moka lazily evicts; touching with get() triggers eviction.
        let got = cache.get("usd").await;
        assert!(got.is_none(), "expected None after TTL expiry, got {got:?}");
    }

    #[tokio::test]
    async fn fiat_keys_are_case_insensitive() {
        let cache = PriceCache::default_tuned();
        cache.put("USD", sample("usd", dec!(67000))).await;
        assert!(cache.get("usd").await.is_some());
        assert!(cache.get("Usd").await.is_some());
    }

    #[tokio::test]
    async fn invalidate_clears_all_entries() {
        let cache = PriceCache::default_tuned();
        cache.put("usd", sample("usd", dec!(67000))).await;
        cache.put("eur", sample("eur", dec!(62000))).await;
        cache.invalidate_all().await;
        // Run pending tasks so invalidate completes deterministically.
        cache.inner.run_pending_tasks().await;
        assert!(cache.get("usd").await.is_none());
        assert!(cache.get("eur").await.is_none());
    }
}
