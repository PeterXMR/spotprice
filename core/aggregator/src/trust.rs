//! EMA-based per-source trust scores.
//!
//! Each source has a score in `[0.0, 1.0]` updated with exponential moving
//! average. Smaller deviations from the aggregate median push the score
//! toward 1.0; larger deviations pull it toward 0.0. Weights are derived
//! from scores at read time and clamped to `[0.1, 0.5]` so no single source
//! can dominate.

use rust_decimal::Decimal;
use std::collections::HashMap;

const ALPHA_NUMERATOR: u64 = 1;
const ALPHA_DENOMINATOR: u64 = 10;

/// Per-source EMA scores.
#[derive(Debug, Clone, Default)]
pub struct TrustScores {
    scores: HashMap<String, Decimal>,
}

impl TrustScores {
    pub fn new() -> Self {
        Self::default()
    }

    /// Update the EMA for `source` given an absolute deviation (price units,
    /// not fraction). The deviation is converted to a score via
    /// `1 / (1 + deviation)` so zero deviation maps to 1.0 and large
    /// deviations asymptotically approach zero.
    pub fn update(&mut self, source: &str, deviation: Decimal) {
        let observation = Decimal::ONE / (Decimal::ONE + deviation.abs());
        let alpha = Decimal::from(ALPHA_NUMERATOR) / Decimal::from(ALPHA_DENOMINATOR);
        let prev = self.scores.get(source).copied().unwrap_or(Decimal::ONE);
        let new = (Decimal::ONE - alpha) * prev + alpha * observation;
        self.scores.insert(source.to_string(), new);
    }

    pub fn score(&self, source: &str) -> Decimal {
        self.scores.get(source).copied().unwrap_or(Decimal::ONE)
    }

    pub fn known_sources(&self) -> Vec<String> {
        self.scores.keys().cloned().collect()
    }
}
