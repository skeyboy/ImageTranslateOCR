use std::{env, path::PathBuf, time::Duration};

use crate::error::AppError;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum TranslationProvider {
    Qwen,
    Openlux,
}

impl TranslationProvider {
    pub const ALL: [Self; 2] = [Self::Qwen, Self::Openlux];

    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "qwen" => Some(Self::Qwen),
            "openlux" => Some(Self::Openlux),
            _ => None,
        }
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Qwen => "qwen",
            Self::Openlux => "openlux",
        }
    }
}

#[derive(Clone, Debug)]
pub struct Config {
    pub server_addr: String,
    pub database_url: String,
    pub qwen_base_url: String,
    pub qwen_api_key: Option<String>,
    pub qwen_model: String,
    pub qwen_models: Vec<String>,
    pub qwen_reasoning_effort: Option<String>,
    pub qwen_max_tokens: u32,
    pub qwen_timeout: Duration,
    pub openlux_base_url: String,
    pub openlux_api_key: Option<String>,
    pub openlux_model: Option<String>,
    pub openlux_models: Vec<String>,
    pub openlux_reasoning_effort: Option<String>,
    pub openlux_max_tokens: u32,
    pub openlux_timeout: Duration,
    pub translation_provider: TranslationProvider,
    pub bearer_token: Option<String>,
    pub request_history_limit: i64,
    pub request_image_dir: PathBuf,
}

impl Config {
    pub fn from_env() -> Result<Self, AppError> {
        let qwen_timeout_seconds = timeout_seconds("QWEN_TIMEOUT_SECONDS", 210)?;
        let qwen_max_tokens = max_tokens("QWEN_MAX_TOKENS", 4096)?;
        let openlux_timeout_seconds = timeout_seconds("OPENLUX_TIMEOUT_SECONDS", 210)?;
        let openlux_max_tokens = max_tokens("OPENLUX_MAX_TOKENS", 4096)?;
        let request_history_limit = env::var("REQUEST_HISTORY_LIMIT")
            .unwrap_or_else(|_| "200".to_owned())
            .parse::<i64>()
            .map_err(|_| AppError::configuration("REQUEST_HISTORY_LIMIT must be an integer"))?;
        if !(10..=5_000).contains(&request_history_limit) {
            return Err(AppError::configuration(
                "REQUEST_HISTORY_LIMIT must be between 10 and 5000",
            ));
        }
        let qwen_reasoning_effort = reasoning_effort("QWEN_REASONING_EFFORT")?;
        let openlux_reasoning_effort = reasoning_effort("OPENLUX_REASONING_EFFORT")?;
        let translation_provider = TranslationProvider::parse(
            &env::var("TRANSLATION_PROVIDER").unwrap_or_else(|_| "qwen".to_owned()),
        )
        .ok_or_else(|| AppError::configuration("TRANSLATION_PROVIDER must be qwen or openlux"))?;
        let qwen_model =
            env::var("QWEN_MODEL").unwrap_or_else(|_| "qwen3.5-translation:9b".to_owned());
        let qwen_models = model_list("QWEN_MODELS", Some(&qwen_model));
        let openlux_model = non_empty_env("OPENLUX_MODEL");
        let openlux_models = model_list("OPENLUX_MODELS", openlux_model.as_deref());
        let config = Self {
            server_addr: env::var("SERVER_ADDR").unwrap_or_else(|_| "0.0.0.0:8090".to_owned()),
            database_url: env::var("DATABASE_URL")
                .unwrap_or_else(|_| "demo-server.sqlite3".to_owned()),
            qwen_base_url: env::var("QWEN_BASE_URL")
                .unwrap_or_else(|_| "http://127.0.0.1:11434/v1".to_owned()),
            qwen_api_key: non_empty_env("QWEN_API_KEY"),
            qwen_model,
            qwen_models,
            qwen_reasoning_effort,
            qwen_max_tokens,
            qwen_timeout: Duration::from_secs(qwen_timeout_seconds),
            openlux_base_url: env::var("OPENLUX_BASE_URL")
                .unwrap_or_else(|_| "https://api.openlux.ai/v1".to_owned()),
            openlux_api_key: non_empty_env("OPENLUX_API_KEY"),
            openlux_model,
            openlux_models,
            openlux_reasoning_effort,
            openlux_max_tokens,
            openlux_timeout: Duration::from_secs(openlux_timeout_seconds),
            translation_provider,
            bearer_token: non_empty_env("SELF_HOSTED_BEARER_TOKEN"),
            request_history_limit,
            request_image_dir: PathBuf::from(
                env::var("REQUEST_IMAGE_DIR").unwrap_or_else(|_| "request-images".to_owned()),
            ),
        };
        if config.translation_provider == TranslationProvider::Openlux
            && (config.openlux_api_key.is_none() || config.openlux_model.is_none())
        {
            return Err(AppError::configuration(
                "OPENLUX_API_KEY and OPENLUX_MODEL are required when TRANSLATION_PROVIDER=openlux",
            ));
        }
        if !config.qwen_models.contains(&config.qwen_model) {
            return Err(AppError::configuration(
                "QWEN_MODEL must be included in QWEN_MODELS",
            ));
        }
        if config
            .openlux_model
            .as_ref()
            .is_some_and(|model| !config.openlux_models.contains(model))
        {
            return Err(AppError::configuration(
                "OPENLUX_MODEL must be included in OPENLUX_MODELS",
            ));
        }
        Ok(config)
    }

    pub fn for_test(database_url: String) -> Self {
        let request_image_dir = PathBuf::from(format!("{database_url}.images"));
        Self {
            server_addr: "127.0.0.1:0".to_owned(),
            database_url,
            qwen_base_url: "http://127.0.0.1:1/v1".to_owned(),
            qwen_api_key: None,
            qwen_model: "fake-qwen".to_owned(),
            qwen_models: vec!["fake-qwen".to_owned()],
            qwen_reasoning_effort: Some("none".to_owned()),
            qwen_max_tokens: 4096,
            qwen_timeout: Duration::from_secs(1),
            openlux_base_url: "https://api.openlux.ai/v1".to_owned(),
            openlux_api_key: None,
            openlux_model: None,
            openlux_models: Vec::new(),
            openlux_reasoning_effort: Some("none".to_owned()),
            openlux_max_tokens: 4096,
            openlux_timeout: Duration::from_secs(1),
            translation_provider: TranslationProvider::Qwen,
            bearer_token: None,
            request_history_limit: 100,
            request_image_dir,
        }
    }
}

fn timeout_seconds(name: &str, default: u64) -> Result<u64, AppError> {
    let value = env::var(name)
        .unwrap_or_else(|_| default.to_string())
        .parse::<u64>()
        .map_err(|_| AppError::configuration(format!("{name} must be an integer")))?;
    if value == 0 || value > 300 {
        return Err(AppError::configuration(format!(
            "{name} must be between 1 and 300"
        )));
    }
    Ok(value)
}

fn max_tokens(name: &str, default: u32) -> Result<u32, AppError> {
    let value = env::var(name)
        .unwrap_or_else(|_| default.to_string())
        .parse::<u32>()
        .map_err(|_| AppError::configuration(format!("{name} must be an integer")))?;
    if !(256..=16_384).contains(&value) {
        return Err(AppError::configuration(format!(
            "{name} must be between 256 and 16384"
        )));
    }
    Ok(value)
}

fn reasoning_effort(name: &str) -> Result<Option<String>, AppError> {
    let value = non_empty_env(name).or_else(|| Some("none".to_owned()));
    if value
        .as_deref()
        .is_some_and(|item| !matches!(item, "none" | "low" | "medium" | "high"))
    {
        return Err(AppError::configuration(format!(
            "{name} must be none, low, medium, or high"
        )));
    }
    Ok(value)
}

fn model_list(name: &str, fallback: Option<&str>) -> Vec<String> {
    let mut models = env::var(name)
        .ok()
        .into_iter()
        .flat_map(|value| {
            value
                .split(',')
                .map(str::trim)
                .filter(|item| !item.is_empty())
                .map(str::to_owned)
                .collect::<Vec<_>>()
        })
        .collect::<Vec<_>>();
    if models.is_empty() {
        models.extend(fallback.filter(|item| !item.is_empty()).map(str::to_owned));
    }
    models.sort();
    models.dedup();
    models
}

fn non_empty_env(name: &str) -> Option<String> {
    env::var(name)
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
}
