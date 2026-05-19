use crate::{now_unix, read_body_capped, PriceSource, SourceError, MAX_RESPONSE_BYTES};
use async_trait::async_trait;
use reqwest::Client;
use rust_decimal::Decimal;
use satsprice_aggregator::RawQuote;
use serde::Deserialize;
use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;

const DEFAULT_BASE: &str = "https://api.coinbase.com";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(2);

pub struct CoinbaseSource {
    client: Arc<Client>,
    base_url: String,
}

impl CoinbaseSource {
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
    data: Spot,
}

#[derive(Deserialize)]
struct Spot {
    amount: String,
}

/// Parse a Coinbase `/v2/prices/BTC-XXX/spot` response body into a [`Decimal`]
/// price. Pulled out of [`PriceSource::fetch`] so it can be exercised by the
/// cargo-fuzz harness under `core/price-sources/fuzz/`.
///
/// Pure / side-effect-free: same input bytes always produce the same result.
pub fn parse_response(body: &[u8]) -> Result<Decimal, SourceError> {
    let parsed: Response =
        serde_json::from_slice(body).map_err(|e| SourceError::Parse(e.to_string()))?;
    Decimal::from_str(&parsed.data.amount)
        .map_err(|e| SourceError::Parse(format!("amount '{}': {}", parsed.data.amount, e)))
}

#[async_trait]
impl PriceSource for CoinbaseSource {
    fn name(&self) -> &'static str {
        "coinbase"
    }

    async fn fetch(&self, fiat: &str) -> Result<RawQuote, SourceError> {
        let fiat_uc = fiat.to_uppercase();
        let url = format!("{}/v2/prices/BTC-{}/spot", self.base_url, fiat_uc);
        let resp = self
            .client
            .get(&url)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(SourceError::Http(resp.status().as_u16()));
        }
        let bytes = read_body_capped(resp, MAX_RESPONSE_BYTES).await?;
        let price = parse_response(&bytes)?;
        Ok(RawQuote {
            source: "coinbase".into(),
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
    async fn parses_price_from_mock_response() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v2/prices/BTC-USD/spot"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "data": { "base": "BTC", "currency": "USD", "amount": "67432.10" }
            })))
            .mount(&server)
            .await;

        let client = Arc::new(Client::new());
        let source = CoinbaseSource::new(client).with_base_url(server.uri());
        let quote = source.fetch("usd").await.expect("fetch");
        assert_eq!(quote.source, "coinbase");
        assert_eq!(quote.price, dec!(67432.10));
    }

    #[tokio::test]
    async fn rejects_oversize_response_body() {
        // Exercises the workspace-wide `read_body_capped` defense: a body
        // larger than `MAX_RESPONSE_BYTES` (64 KiB) must be rejected as
        // `SourceError::Parse`, not buffered into heap. The helper is
        // shared by all four adapters; coinbase is the canary.
        let server = MockServer::start().await;
        let huge_body = "x".repeat(crate::MAX_RESPONSE_BYTES + 1);
        Mock::given(method("GET"))
            .and(path("/v2/prices/BTC-USD/spot"))
            .respond_with(ResponseTemplate::new(200).set_body_string(huge_body))
            .mount(&server)
            .await;

        let client = Arc::new(Client::new());
        let source = CoinbaseSource::new(client).with_base_url(server.uri());
        let err = source.fetch("usd").await.expect_err("should fail");
        assert!(matches!(err, SourceError::Parse(_)));
    }

    #[tokio::test]
    async fn rejects_non_decimal_amount() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v2/prices/BTC-USD/spot"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "data": { "base": "BTC", "currency": "USD", "amount": "banana" }
            })))
            .mount(&server)
            .await;

        let client = Arc::new(Client::new());
        let source = CoinbaseSource::new(client).with_base_url(server.uri());
        let err = source.fetch("usd").await.expect_err("should fail");
        assert!(matches!(err, SourceError::Parse(_)));
    }
}
