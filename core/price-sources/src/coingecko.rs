use crate::{now_unix, PriceSource, SourceError};
use async_trait::async_trait;
use reqwest::Client;
use rust_decimal::Decimal;
use satsprice_aggregator::RawQuote;
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

const DEFAULT_BASE: &str = "https://api.coingecko.com";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(2);

pub struct CoingeckoSource {
    client: Arc<Client>,
    base_url: String,
}

impl CoingeckoSource {
    pub fn new(client: Arc<Client>) -> Self {
        Self {
            client,
            base_url: DEFAULT_BASE.into(),
        }
    }

    /// Test hook — point the source at a wiremock URL.
    pub fn with_base_url(mut self, url: String) -> Self {
        self.base_url = url;
        self
    }
}

#[derive(Deserialize)]
struct Response {
    bitcoin: HashMap<String, f64>,
}

/// Parse a CoinGecko `/api/v3/simple/price?ids=bitcoin&vs_currencies=XXX`
/// response body into a [`Decimal`] price for the given (lowercase) fiat key.
/// Pulled out of [`PriceSource::fetch`] so it can be exercised by the
/// cargo-fuzz harness under `core/price-sources/fuzz/`.
///
/// Pure / side-effect-free: same input bytes + key always produce the same
/// result.
pub fn parse_response(body: &[u8], fiat_lc: &str) -> Result<Decimal, SourceError> {
    let parsed: Response =
        serde_json::from_slice(body).map_err(|e| SourceError::Parse(e.to_string()))?;
    let raw = parsed
        .bitcoin
        .get(fiat_lc)
        .copied()
        .ok_or_else(|| SourceError::Parse(format!("missing bitcoin.{fiat_lc}")))?;
    Decimal::try_from(raw).map_err(|e| SourceError::Parse(format!("bad number: {e}")))
}

#[async_trait]
impl PriceSource for CoingeckoSource {
    fn name(&self) -> &'static str {
        "coingecko"
    }

    async fn fetch(&self, fiat: &str) -> Result<RawQuote, SourceError> {
        let fiat_lc = fiat.to_lowercase();
        let url = format!(
            "{}/api/v3/simple/price?ids=bitcoin&vs_currencies={}",
            self.base_url, fiat_lc
        );
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
        let price = parse_response(&bytes, &fiat_lc)?;
        Ok(RawQuote {
            source: "coingecko".into(),
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
    use wiremock::matchers::{method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[tokio::test]
    async fn parses_price_from_mock_response() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/api/v3/simple/price"))
            .and(query_param("ids", "bitcoin"))
            .and(query_param("vs_currencies", "usd"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(json!({ "bitcoin": { "usd": 67000.0 } })),
            )
            .mount(&server)
            .await;

        let client = Arc::new(Client::new());
        let source = CoingeckoSource::new(client).with_base_url(server.uri());

        let quote = source.fetch("usd").await.expect("fetch");
        assert_eq!(quote.source, "coingecko");
        assert_eq!(quote.fiat, "usd");
        assert_eq!(quote.price, dec!(67000));
    }

    #[tokio::test]
    async fn server_error_becomes_http_error() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/api/v3/simple/price"))
            .respond_with(ResponseTemplate::new(503))
            .mount(&server)
            .await;

        let client = Arc::new(Client::new());
        let source = CoingeckoSource::new(client).with_base_url(server.uri());
        let err = source.fetch("usd").await.expect_err("should fail");
        assert!(matches!(err, SourceError::Http(503)));
    }

    #[tokio::test]
    async fn malformed_json_is_parse_error() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/api/v3/simple/price"))
            .respond_with(ResponseTemplate::new(200).set_body_string("not json"))
            .mount(&server)
            .await;

        let client = Arc::new(Client::new());
        let source = CoingeckoSource::new(client).with_base_url(server.uri());
        let err = source.fetch("usd").await.expect_err("should fail");
        // reqwest::Error from `.json()` failure surfaces as Network — both are
        // "could not parse"-shaped from the caller's POV; assert it's not Ok.
        match err {
            SourceError::Network(_) | SourceError::Parse(_) => {}
            other => panic!("unexpected error: {other:?}"),
        }
    }
}
