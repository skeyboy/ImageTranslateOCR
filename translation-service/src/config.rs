use std::{env, net::SocketAddr, time::Duration};

use anyhow::{Context, Result};

#[derive(Clone, Debug)]
pub struct Config {
    pub bind_addr: SocketAddr,
    pub database_url: String,
    pub model_base_url: String,
    pub model_id: String,
    pub provider_id: String,
    pub model_api_key: Option<String>,
    pub api_bearer_token: Option<String>,
    pub model_timeout: Duration,
    pub model_max_tokens: u32,
    pub model_max_concurrency: usize,
    pub paddle_ocr_base_url: String,
    pub paddle_ocr_timeout: Duration,
    pub idempotency_retention_seconds: i64,
}

impl Config {
    pub fn from_env() -> Result<Self> {
        let bind_addr = env_value("BIND_ADDR", "0.0.0.0:8090")
            .parse()
            .context("BIND_ADDR must be a socket address")?;
        let model_timeout_ms = parse_env("HY_MT2_TIMEOUT_MS", 8_000_u64)?;
        let model_max_tokens = parse_env("HY_MT2_MAX_TOKENS", 1_024_u32)?;
        let model_max_concurrency = parse_env("HY_MT2_MAX_CONCURRENCY", 2_usize)?;
        let paddle_ocr_timeout_ms = parse_env("PADDLE_OCR_TIMEOUT_MS", 20_000_u64)?;
        let idempotency_retention_seconds = parse_env("IDEMPOTENCY_RETENTION_SECONDS", 600_i64)?;
        anyhow::ensure!(
            model_max_concurrency > 0,
            "HY_MT2_MAX_CONCURRENCY must be positive"
        );
        anyhow::ensure!(
            idempotency_retention_seconds > 0,
            "IDEMPOTENCY_RETENTION_SECONDS must be positive"
        );

        Ok(Self {
            bind_addr,
            database_url: env_value("DATABASE_URL", "postgresql://localhost/image_translate_ocr"),
            model_base_url: env_value("HY_MT2_BASE_URL", "http://127.0.0.1:8088/v1")
                .trim_end_matches('/')
                .to_owned(),
            model_id: env_value("HY_MT2_MODEL", "tencent/Hy-MT2-1.8B"),
            provider_id: env_value("HY_MT2_PROVIDER_ID", "hy-mt2-1.8b-stq"),
            model_api_key: optional_env("HY_MT2_API_KEY"),
            api_bearer_token: optional_env("API_BEARER_TOKEN"),
            model_timeout: Duration::from_millis(model_timeout_ms),
            model_max_tokens,
            model_max_concurrency,
            paddle_ocr_base_url: env_value("PADDLE_OCR_BASE_URL", "http://127.0.0.1:8081")
                .trim_end_matches('/')
                .to_owned(),
            paddle_ocr_timeout: Duration::from_millis(paddle_ocr_timeout_ms),
            idempotency_retention_seconds,
        })
    }
}

fn env_value(name: &str, default: &str) -> String {
    env::var(name).unwrap_or_else(|_| default.to_owned())
}

fn optional_env(name: &str) -> Option<String> {
    env::var(name).ok().filter(|value| !value.trim().is_empty())
}

fn parse_env<T>(name: &str, default: T) -> Result<T>
where
    T: std::str::FromStr,
    T::Err: std::error::Error + Send + Sync + 'static,
{
    match env::var(name) {
        Ok(value) => value
            .parse()
            .with_context(|| format!("{name} has an invalid value")),
        Err(_) => Ok(default),
    }
}
