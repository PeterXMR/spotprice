use crate::{now_unix, PriceSource, SourceError};
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
        let body: Response = resp.json().await?;
        let price = Decimal::from_str(&body.data.amount)
            .map_err(|e| SourceError::Parse(format!("amount '{}': {}", body.data.amount, e)))?;
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
