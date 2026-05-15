use thiserror::Error;

#[derive(Debug, Error)]
pub enum SourceError {
    #[error("network error: {0}")]
    Network(#[from] reqwest::Error),

    #[error("server returned HTTP {0}")]
    Http(u16),

    #[error("parse error: {0}")]
    Parse(String),

    #[error("request timed out")]
    Timeout,
}
