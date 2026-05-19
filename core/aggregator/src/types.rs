//! Shared types crossing the aggregator API surface.

use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

/// A single price observation from one source at one point in time.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RawQuote {
    pub source: String,
    pub fiat: String,
    pub price: Decimal,
    /// Unix seconds when this quote was fetched.
    pub fetched_at: u64,
}

/// The aggregated price returned to callers (median of surviving sources;
/// trust-weighting is reserved for v0.2 — see crate-level docs).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AggregatedPrice {
    pub price: Decimal,
    pub fiat: String,
    pub sources_used: u32,
    /// Largest absolute deviation among contributing sources, as a fraction of the median.
    pub dispersion: Decimal,
    pub timestamp: u64,
}

/// Tunable thresholds for aggregation.
#[derive(Debug, Clone)]
pub struct AggregateOpts {
    /// Quotes older than this (in seconds, relative to `now`) are discarded.
    pub max_age_secs: u64,
    /// Quotes deviating by more than this fraction from the preliminary median are dropped.
    pub outlier_tolerance: Decimal,
    /// Minimum surviving quotes for a publishable result.
    pub min_sources: u32,
}

impl Default for AggregateOpts {
    fn default() -> Self {
        Self {
            max_age_secs: 60,
            outlier_tolerance: Decimal::new(5, 3), // 0.005 = 0.5%
            min_sources: 3,
        }
    }
}
