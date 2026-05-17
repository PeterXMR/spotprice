use crate::{now_unix, PriceSource, SourceError};
use async_trait::async_trait;
use reqwest::Client;
use rust_decimal::Decimal;
use satsprice_aggregator::RawQuote;
use serde::Deserialize;
use std::collections::HashMap;
use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;

const DEFAULT_BASE: &str = "https://api.kraken.com";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(2);

pub struct KrakenSource {
    client: Arc<Client>,
    base_url: String,
}

impl KrakenSource {
    pub fn new(client: Arc<Client>) -> Self {
        Self {
            client,
            base_url: DEFAULT_BASE.into(),
        }
    }

    pub fn with_base_url(mut self, url: String) -> Self {
        self.base_url = url;
        self
    }
}

#[derive(Deserialize)]
struct Response {
    error: Vec<String>,
    result: HashMap<String, Ticker>,
}

#[derive(Deserialize)]
struct Ticker {
    /// [last_trade_price, last_trade_lot_volume]
    c: Vec<String>,
}

/// Parse a Kraken `/0/public/Ticker?pair=XBTXXX` response body into a [`Decimal`]
/// price. Pulled out of [`PriceSource::fetch`] so it can be exercised by the
/// cargo-fuzz harness under `core/price-sources/fuzz/`.
///
/// Pure / side-effect-free: same input bytes always produce the same result.
pub fn parse_response(body: &[u8]) -> Result<Decimal, SourceError> {
    let parsed: Response =
        serde_json::from_slice(body).map_err(|e| SourceError::Parse(e.to_string()))?;
    if !parsed.error.is_empty() {
        return Err(SourceError::Parse(format!("api error: {:?}", parsed.error)));
    }
    // Kraken returns the first match under a normalized key — pick whatever's there.
    let (_, ticker) = parsed
        .result
        .into_iter()
        .next()
        .ok_or_else(|| SourceError::Parse("empty result".into()))?;
    let last = ticker
        .c
        .first()
        .ok_or_else(|| SourceError::Parse("empty 'c' array".into()))?;
    Decimal::from_str(last).map_err(|e| SourceError::Parse(format!("last '{last}': {e}")))
}

#[async_trait]
impl PriceSource for KrakenSource {
    fn name(&self) -> &'static str {
        "kraken"
    }

    async fn fetch(&self, fiat: &str) -> Result<RawQuote, SourceError> {
        let fiat_uc = fiat.to_uppercase();
        let pair = format!("XBT{}", fiat_uc);
        let url = format!("{}/0/public/Ticker?pair={}", self.base_url, pair);
        let resp = self
            .client
            .get(&url)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(SourceError::Http(resp.status().as_u16()));
        }
        let bytes = resp.bytes().await?;
        let price = parse_response(&bytes)?;
        Ok(RawQuote {
            source: "kraken".into(),
            fiat: fiat.to_lowercase(),
            price,
            fetched_at: now_unix(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal_macros::dec;
    use serde_json::json;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[tokio::test]
    async fn parses_kraken_response_with_doubled_prefix_key() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/0/public/Ticker"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "error": [],
                "result": {
                    "XXBTZUSD": {
                        "a": ["67500.1", "1", "1.000"],
                        "b": ["67499.9", "1", "1.000"],
                        "c": ["67500.0", "0.01"],
                        "v": ["100.0", "200.0"],
                        "p": ["67450.0", "67400.0"],
                        "t": [10, 20],
                        "l": ["67000.0", "66900.0"],
                        "h": ["68000.0", "68100.0"],
                        "o": "67200.0"
                    }
                }
            })))
            .mount(&server)
            .await;

        let client = Arc::new(Client::new());
        let source = KrakenSource::new(client).with_base_url(server.uri());
        let quote = source.fetch("usd").await.expect("fetch");
        assert_eq!(quote.source, "kraken");
        assert_eq!(quote.price, dec!(67500.0));
    }

    #[tokio::test]
    async fn non_empty_error_array_is_parse_error() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/0/public/Ticker"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "error": ["EQuery:Unknown asset pair"],
                "result": {}
            })))
            .mount(&server)
            .await;

        let client = Arc::new(Client::new());
        let source = KrakenSource::new(client).with_base_url(server.uri());
        let err = source.fetch("usd").await.expect_err("should fail");
        assert!(matches!(err, SourceError::Parse(_)));
    }
}
