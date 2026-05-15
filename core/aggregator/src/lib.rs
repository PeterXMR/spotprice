//! Cross-source aggregation: takes a set of [`RawQuote`]s, drops stale and
//! outlier readings, and returns a robust [`AggregatedPrice`] only if at
//! least `min_sources` quotes survive.

mod median;
mod trust;
mod types;

pub use median::{dispersion, median, prune_outliers, prune_stale};
pub use trust::TrustScores;
pub use types::{AggregateOpts, AggregatedPrice, RawQuote};

/// Aggregate the given quotes into a single trust-weighted price.
///
/// Returns `None` when fewer than `opts.min_sources` quotes survive the
/// stale + outlier filters — callers should keep showing the last cached
/// price and surface a "stale" indicator to the user.
pub fn aggregate(
    quotes: Vec<RawQuote>,
    opts: &AggregateOpts,
    now: u64,
) -> Option<AggregatedPrice> {
    if quotes.is_empty() {
        return None;
    }
    let fiat = quotes[0].fiat.clone();
    let fresh = prune_stale(quotes, opts.max_age_secs, now);
    if fresh.is_empty() {
        return None;
    }
    let trimmed = prune_outliers(fresh, opts.outlier_tolerance);
    if (trimmed.len() as u32) < opts.min_sources {
        return None;
    }
    let prices: Vec<_> = trimmed.iter().map(|q| q.price).collect();
    let price = median(&prices)?;
    Some(AggregatedPrice {
        price,
        fiat,
        sources_used: trimmed.len() as u32,
        dispersion: dispersion(&prices, price),
        timestamp: now,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal::Decimal;
    use rust_decimal_macros::dec;

    fn q(source: &str, price: Decimal, fetched_at: u64) -> RawQuote {
        RawQuote {
            source: source.into(),
            fiat: "usd".into(),
            price,
            fetched_at,
        }
    }

    #[test]
    fn median_of_three_returns_middle() {
        assert_eq!(median(&[dec!(101), dec!(99), dec!(100)]), Some(dec!(100)));
    }

    #[test]
    fn median_of_four_averages_middle_two() {
        assert_eq!(
            median(&[dec!(99), dec!(100), dec!(101), dec!(102)]),
            Some(dec!(100.5))
        );
    }

    #[test]
    fn median_of_empty_is_none() {
        assert_eq!(median(&[]), None);
    }

    #[test]
    fn prune_stale_drops_old_quotes() {
        let qs = vec![
            q("a", dec!(100), 1000),
            q("b", dec!(101), 900), // 100s old, dropped at 60s ttl
            q("c", dec!(102), 1000),
        ];
        let fresh = prune_stale(qs, 60, 1000);
        assert_eq!(fresh.len(), 2);
        assert!(fresh.iter().all(|q| q.source != "b"));
    }

    #[test]
    fn prune_outliers_drops_far_quotes() {
        let qs = vec![
            q("a", dec!(100), 0),
            q("b", dec!(101), 0),
            q("c", dec!(102), 0),
            q("d", dec!(200), 0), // wildly out of range
        ];
        let trimmed = prune_outliers(qs, dec!(0.05)); // 5% tolerance
        assert_eq!(trimmed.len(), 3);
        assert!(trimmed.iter().all(|q| q.source != "d"));
    }

    #[test]
    fn aggregate_returns_none_with_too_few_sources() {
        let qs = vec![q("a", dec!(100), 1000), q("b", dec!(101), 1000)];
        let result = aggregate(qs, &AggregateOpts::default(), 1000);
        assert!(result.is_none(), "expected None with 2 sources < min 3");
    }

    #[test]
    fn aggregate_happy_path_three_close_sources() {
        let qs = vec![
            q("coingecko", dec!(67000), 1000),
            q("coinbase", dec!(67050), 1000),
            q("kraken", dec!(66980), 1000),
        ];
        let result = aggregate(qs, &AggregateOpts::default(), 1000).expect("aggregate");
        assert_eq!(result.sources_used, 3);
        assert_eq!(result.price, dec!(67000)); // median of the three
        assert_eq!(result.fiat, "usd");
        assert!(result.dispersion > Decimal::ZERO);
    }

    #[test]
    fn aggregate_drops_outlier_then_succeeds() {
        let qs = vec![
            q("coingecko", dec!(67000), 1000),
            q("coinbase", dec!(67050), 1000),
            q("kraken", dec!(66980), 1000),
            q("badfeed", dec!(150000), 1000), // outlier
        ];
        let result = aggregate(qs, &AggregateOpts::default(), 1000).expect("aggregate");
        assert_eq!(result.sources_used, 3, "outlier should have been dropped");
    }

    #[test]
    fn trust_zero_deviation_keeps_score_near_one() {
        let mut t = TrustScores::new();
        for _ in 0..50 {
            t.update("a", dec!(0));
        }
        assert!(t.score("a") > dec!(0.99));
    }

    #[test]
    fn trust_large_deviation_drives_score_down() {
        let mut t = TrustScores::new();
        for _ in 0..200 {
            t.update("bad", dec!(100));
        }
        assert!(t.score("bad") < dec!(0.05));
    }
}
