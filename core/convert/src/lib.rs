//! SpotPrice — unit conversion between satoshis, BTC, and arbitrary fiat.
//!
//! All math is performed with [`rust_decimal::Decimal`] to avoid IEEE-754
//! rounding surprises that would otherwise corrupt sat counts displayed
//! to users.

use rust_decimal::prelude::*;
use rust_decimal::Decimal;
use thiserror::Error;

pub const SATS_PER_BTC: u64 = 100_000_000;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ConvertError {
    #[error("negative input not permitted: {0}")]
    Negative(String),
    #[error("price is zero or negative")]
    NonPositivePrice,
    #[error("decimal overflow during conversion")]
    Overflow,
}

/// Exact integer satoshi count for a given BTC amount (rounded down).
pub fn btc_to_sats(btc: Decimal) -> Result<u64, ConvertError> {
    if btc.is_sign_negative() {
        return Err(ConvertError::Negative(btc.to_string()));
    }
    let multiplied = btc
        .checked_mul(Decimal::from(SATS_PER_BTC))
        .ok_or(ConvertError::Overflow)?;
    multiplied.floor().to_u64().ok_or(ConvertError::Overflow)
}

/// BTC equivalent of a sat count, at 8 fractional digits.
pub fn sats_to_btc(sats: u64) -> Decimal {
    Decimal::from(sats) / Decimal::from(SATS_PER_BTC)
}

/// Fiat value of a sat amount given a BTC/fiat price.
pub fn sats_to_fiat(sats: u64, price_per_btc: Decimal) -> Result<Decimal, ConvertError> {
    if price_per_btc.is_sign_negative() || price_per_btc.is_zero() {
        return Err(ConvertError::NonPositivePrice);
    }
    Ok(sats_to_btc(sats) * price_per_btc)
}

/// Sat count required to equal a fiat amount at the given price.
pub fn fiat_to_sats(fiat: Decimal, price_per_btc: Decimal) -> Result<u64, ConvertError> {
    if fiat.is_sign_negative() {
        return Err(ConvertError::Negative(fiat.to_string()));
    }
    if price_per_btc.is_sign_negative() || price_per_btc.is_zero() {
        return Err(ConvertError::NonPositivePrice);
    }
    // `Decimal::Div` panics on scale overflow. Workspace ships with
    // `panic = "abort"`, so on a user device that panic is a SIGABRT
    // with no recovery — guard with checked_div instead.
    let btc = fiat
        .checked_div(price_per_btc)
        .ok_or(ConvertError::Overflow)?;
    btc_to_sats(btc)
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    use rust_decimal_macros::dec;

    #[test]
    fn one_btc_equals_one_hundred_million_sats() {
        assert_eq!(btc_to_sats(dec!(1)).unwrap(), 100_000_000);
    }

    #[test]
    fn small_btc_rounds_down() {
        // 0.000_000_01 BTC == 1 sat
        assert_eq!(btc_to_sats(dec!(0.00000001)).unwrap(), 1);
        // 0.000_000_005 BTC rounds down to 0 sats (floor)
        assert_eq!(btc_to_sats(dec!(0.000000005)).unwrap(), 0);
    }

    #[test]
    fn sats_to_btc_is_exact() {
        assert_eq!(sats_to_btc(100_000_000), dec!(1));
        assert_eq!(sats_to_btc(1), dec!(0.00000001));
        assert_eq!(sats_to_btc(0), dec!(0));
    }

    #[test]
    fn one_btc_at_67k_is_67k_fiat() {
        let price = dec!(67000);
        let fiat = sats_to_fiat(100_000_000, price).unwrap();
        assert_eq!(fiat, dec!(67000));
    }

    #[test]
    fn fiat_to_sats_round_trip() {
        let price = dec!(67000);
        // $67 at $67k/BTC = 0.001 BTC = 100_000 sats
        assert_eq!(fiat_to_sats(dec!(67), price).unwrap(), 100_000);
    }

    #[test]
    fn negative_btc_rejected() {
        assert!(matches!(
            btc_to_sats(dec!(-1)),
            Err(ConvertError::Negative(_))
        ));
    }

    #[test]
    fn zero_price_rejected() {
        assert_eq!(
            sats_to_fiat(1_000, dec!(0)),
            Err(ConvertError::NonPositivePrice)
        );
        assert_eq!(
            fiat_to_sats(dec!(10), dec!(0)),
            Err(ConvertError::NonPositivePrice)
        );
    }

    #[test]
    fn negative_fiat_rejected() {
        assert!(matches!(
            fiat_to_sats(dec!(-1), dec!(67000)),
            Err(ConvertError::Negative(_))
        ));
    }

    #[test]
    fn fiat_to_sats_overflow_returns_err_not_panic() {
        // `Decimal::MAX` / a very small price would overflow the result's
        // representable scale. We assert this returns Err rather than
        // panicking (which would be SIGABRT under panic=abort).
        let huge = Decimal::MAX;
        let tiny_price = dec!(0.0000001);
        let result = fiat_to_sats(huge, tiny_price);
        assert!(matches!(result, Err(ConvertError::Overflow)));
    }

    proptest! {
        #[test]
        fn sats_to_btc_to_sats_is_identity(sats in 0u64..21_000_000 * SATS_PER_BTC) {
            let btc = sats_to_btc(sats);
            let back = btc_to_sats(btc).unwrap();
            prop_assert_eq!(back, sats);
        }

        #[test]
        fn fiat_round_trip_within_one_sat(
            sats in 1u64..21_000_000 * SATS_PER_BTC,
            price_cents in 1u64..100_000_000_u64,
        ) {
            let price = Decimal::from(price_cents) / Decimal::from(100);
            let fiat = sats_to_fiat(sats, price).unwrap();
            let recovered = fiat_to_sats(fiat, price).unwrap();
            // Round-trip may lose at most 1 sat due to floor() in btc_to_sats
            prop_assert!(
                recovered <= sats && sats - recovered <= 1,
                "lost {} sats round-tripping {} sats at {}",
                sats.saturating_sub(recovered),
                sats,
                price,
            );
        }
    }
}
