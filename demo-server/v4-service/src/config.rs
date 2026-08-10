use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::error::V4ServiceError;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QwenConfig {
    pub qwen_base_url: String,
    #[serde(default)]
    pub qwen_api_key: Option<String>,
    pub qwen_model: String,
    #[serde(default = "default_reasoning_effort")]
    pub qwen_reasoning_effort: Option<String>,
    #[serde(default = "default_max_tokens")]
    pub qwen_max_tokens: u32,
    #[serde(default = "default_timeout_seconds")]
    pub qwen_timeout_seconds: u64,
}

impl QwenConfig {
    pub fn validate(&self) -> Result<(), V4ServiceError> {
        if self.qwen_base_url.trim().is_empty() {
            return Err(V4ServiceError::configuration(
                "qwenBaseUrl must not be empty",
            ));
        }
        if self.qwen_model.trim().is_empty() {
            return Err(V4ServiceError::configuration("qwenModel must not be empty"));
        }
        if !(256..=16_384).contains(&self.qwen_max_tokens) {
            return Err(V4ServiceError::configuration(
                "maxTokens must be between 256 and 16384",
            ));
        }
        if self.qwen_timeout_seconds == 0 || self.qwen_timeout_seconds > 300 {
            return Err(V4ServiceError::configuration(
                "timeoutSeconds must be between 1 and 300",
            ));
        }
        if self
            .qwen_reasoning_effort
            .as_deref()
            .is_some_and(|value| !matches!(value, "none" | "low" | "medium" | "high"))
        {
            return Err(V4ServiceError::configuration(
                "reasoningEffort must be none, low, medium, or high",
            ));
        }
        Ok(())
    }

    pub fn timeout(&self) -> Duration {
        Duration::from_secs(self.qwen_timeout_seconds)
    }

    #[cfg(test)]
    pub(crate) fn for_test() -> Self {
        Self {
            qwen_base_url: "http://127.0.0.1:1/v1".to_owned(),
            qwen_api_key: None,
            qwen_model: "fake-qwen".to_owned(),
            qwen_reasoning_effort: Some("none".to_owned()),
            qwen_max_tokens: 4096,
            qwen_timeout_seconds: 1,
        }
    }
}

fn default_reasoning_effort() -> Option<String> {
    Some("none".to_owned())
}

const fn default_max_tokens() -> u32 {
    4096
}

const fn default_timeout_seconds() -> u64 {
    210
}
