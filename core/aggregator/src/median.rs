//! Median + outlier-rejection helpers.

use rust_decimal::Decimal;

use crate::types::RawQuote;

/// Median of a slice of decimals. Returns `None` for an empty input.
/// For even-length slices, returns the average of the two middle values.
pub fn median(values: &[Decimal]) -> Option<Decimal> {
    if values.is_empty() {
        return None;
    }
    let mut sorted: Vec<Decimal> = values.to_vec();
    sorted.sort();
    let mid = sorted.len() / 2;
    if sorted.len().is_multiple_of(2) {
        Some((sorted[mid - 1] + sorted[mid]) / Decimal::from(2))
    } else {
        Some(sorted[mid])
    }
}

/// Drop quotes whose `fetched_at` is older than `max_age_secs` relative to `now`.
pub fn prune_stale(quotes: Vec<RawQuote>, max_age_secs: u64, now: u64) -> Vec<RawQuote> {
    quotes
        .into_iter()
        .filter(|q| now.saturating_sub(q.fetched_at) <= max_age_secs)
        .collect()
}

/// Drop quotes whose deviation from the preliminary median exceeds `tolerance`
/// (expressed as a fraction, e.g. `0.005` for 0.5%).
pub fn prune_outliers(quotes: Vec<RawQuote>, tolerance: Decimal) -> Vec<RawQuote> {
    let prices: Vec<Decimal> = quotes.iter().map(|q| q.price).collect();
    let Some(prelim) = median(&prices) else {
        return Vec::new();
    };
    if prelim.is_zero() {
        return quotes;
    }
    let bound = prelim * tolerance;
    quotes
        .into_iter()
        .filter(|q| (q.price - prelim).abs() <= bound)
        .collect()
}

/// Largest deviation (as a fraction of `center`) among the given prices.
pub fn dispersion(prices: &[Decimal], center: Decimal) -> Decimal {
    if center.is_zero() || prices.is_empty() {
        return Decimal::ZERO;
    }
    prices
        .iter()
        .map(|p| ((*p - center).abs()) / center)
        .max()
        .unwrap_or(Decimal::ZERO)
}
