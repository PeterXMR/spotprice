use crate::{now_unix, PriceSource, SourceError};
use async_trait::async_trait;
use reqwest::Client;
use rust_decimal::Decimal;
use satsprice_aggregator::RawQuote;
use serde::Deserialize;
use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;

const DEFAULT_BASE: &str = "https://www.bitstamp.net";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(2);

pub struct BitstampSource {
    client: Arc<Client>,
    base_url: String,
}

impl BitstampSource {
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
    last: String,
}

/// Parse a Bitstamp `/api/v2/ticker/btcXXX/` response body into a [`Decimal`]
/// price. Pulled out of [`PriceSource::fetch`] so it can be exercised by the
/// cargo-fuzz harness under `core/price-sources/fuzz/`.
///
/// Pure / side-effect-free: same input bytes always produce the same result.
pub fn parse_response(body: &[u8]) -> Result<Decimal, SourceError> {
    let parsed: Response =
        serde_json::from_slice(body).map_err(|e| SourceError::Parse(e.to_string()))?;
    Decimal::from_str(&parsed.last)
        .map_err(|e| SourceError::Parse(format!("last '{}': {}", parsed.last, e)))
}

#[async_trait]
impl PriceSource for BitstampSource {
    fn name(&self) -> &'static str {
        "bitstamp"
    }

    async fn fetch(&self, fiat: &str) -> Result<RawQuote, SourceError> {
        let fiat_lc = fiat.to_lowercase();
        let url = format!("{}/api/v2/ticker/btc{}/", self.base_url, fiat_lc);
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
            source: "bitstamp".into(),
            fiat: fiat_lc,
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
    async fn parses_last_field() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/api/v2/ticker/btcusd/"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "last": "67501.23", "bid": "67500", "ask": "67502",
                "high": "68000", "low": "67000",
                "open": "67200", "volume": "1000",
                "vwap": "67400", "timestamp": "1715000000"
            })))
            .mount(&server)
            .await;

        let client = Arc::new(Client::new());
        let source = BitstampSource::new(client).with_base_url(server.uri());
        let quote = source.fetch("usd").await.expect("fetch");
        assert_eq!(quote.source, "bitstamp");
        assert_eq!(quote.price, dec!(67501.23));
    }
}
