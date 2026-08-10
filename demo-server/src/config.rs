use std::{env, path::PathBuf, time::Duration};

use crate::error::AppError;
use image_translate_v4_service::QwenConfig;

#[derive(Clone, Debug)]
pub struct Config {
    pub server_addr: String,
    pub database_url: String,
    pub qwen_base_url: String,
    pub qwen_api_key: Option<String>,
    pub qwen_model: String,
    pub qwen_reasoning_effort: Option<String>,
    pub qwen_max_tokens: u32,
    pub qwen_timeout: Duration,
    pub bearer_token: Option<String>,
    pub request_history_limit: i64,
    pub request_image_dir: PathBuf,
}

impl Config {
    pub fn qwen_config(&self) -> QwenConfig {
        QwenConfig {
            qwen_base_url: self.qwen_base_url.clone(),
            qwen_api_key: self.qwen_api_key.clone(),
            qwen_model: self.qwen_model.clone(),
            qwen_reasoning_effort: self.qwen_reasoning_effort.clone(),
            qwen_max_tokens: self.qwen_max_tokens,
            qwen_timeout_seconds: self.qwen_timeout.as_secs(),
        }
    }

    pub fn from_env() -> Result<Self, AppError> {
        let timeout_seconds = env::var("QWEN_TIMEOUT_SECONDS")
            .unwrap_or_else(|_| "210".to_owned())
            .parse::<u64>()
            .map_err(|_| AppError::configuration("QWEN_TIMEOUT_SECONDS must be an integer"))?;
        if timeout_seconds == 0 || timeout_seconds > 300 {
            return Err(AppError::configuration(
                "QWEN_TIMEOUT_SECONDS must be between 1 and 300",
            ));
        }
        let qwen_max_tokens = env::var("QWEN_MAX_TOKENS")
            .unwrap_or_else(|_| "4096".to_owned())
            .parse::<u32>()
            .map_err(|_| AppError::configuration("QWEN_MAX_TOKENS must be an integer"))?;
        if !(256..=16_384).contains(&qwen_max_tokens) {
            return Err(AppError::configuration(
                "QWEN_MAX_TOKENS must be between 256 and 16384",
            ));
        }
        let request_history_limit = env::var("REQUEST_HISTORY_LIMIT")
            .unwrap_or_else(|_| "200".to_owned())
            .parse::<i64>()
            .map_err(|_| AppError::configuration("REQUEST_HISTORY_LIMIT must be an integer"))?;
        if !(10..=5_000).contains(&request_history_limit) {
            return Err(AppError::configuration(
                "REQUEST_HISTORY_LIMIT must be between 10 and 5000",
            ));
        }
        let reasoning_effort =
            non_empty_env("QWEN_REASONING_EFFORT").or_else(|| Some("none".to_owned()));
        if reasoning_effort
            .as_deref()
            .is_some_and(|value| !matches!(value, "none" | "low" | "medium" | "high"))
        {
            return Err(AppError::configuration(
                "QWEN_REASONING_EFFORT must be none, low, medium, or high",
            ));
        }
        Ok(Self {
            server_addr: env::var("SERVER_ADDR").unwrap_or_else(|_| "0.0.0.0:8090".to_owned()),
            database_url: env::var("DATABASE_URL")
                .unwrap_or_else(|_| "demo-server.sqlite3".to_owned()),
            qwen_base_url: env::var("QWEN_BASE_URL")
                .unwrap_or_else(|_| "http://127.0.0.1:11434/v1".to_owned()),
            qwen_api_key: non_empty_env("QWEN_API_KEY"),
            qwen_model: env::var("QWEN_MODEL")
                .unwrap_or_else(|_| "qwen3.5-translation:9b".to_owned()),
            qwen_reasoning_effort: reasoning_effort,
            qwen_max_tokens,
            qwen_timeout: Duration::from_secs(timeout_seconds),
            bearer_token: non_empty_env("SELF_HOSTED_BEARER_TOKEN"),
            request_history_limit,
            request_image_dir: PathBuf::from(
                env::var("REQUEST_IMAGE_DIR").unwrap_or_else(|_| "request-images".to_owned()),
            ),
        })
    }

    pub fn for_test(database_url: String) -> Self {
        let request_image_dir = PathBuf::from(format!("{database_url}.images"));
        Self {
            server_addr: "127.0.0.1:0".to_owned(),
            database_url,
            qwen_base_url: "http://127.0.0.1:1/v1".to_owned(),
            qwen_api_key: None,
            qwen_model: "fake-qwen".to_owned(),
            qwen_reasoning_effort: Some("none".to_owned()),
            qwen_max_tokens: 4096,
            qwen_timeout: Duration::from_secs(1),
            bearer_token: None,
            request_history_limit: 100,
            request_image_dir,
        }
    }
}

fn non_empty_env(name: &str) -> Option<String> {
    env::var(name)
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
}
