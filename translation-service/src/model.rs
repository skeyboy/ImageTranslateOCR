use std::{sync::Arc, time::Instant};

use async_trait::async_trait;
use reqwest::StatusCode;
use serde::{Deserialize, Serialize};
use tokio::sync::Semaphore;

use crate::{config::Config, contract::chinese_language_name};

#[derive(Debug, Clone)]
pub struct TranslationInput {
    pub text: String,
    pub source_language: Option<String>,
    pub target_language: String,
    pub preserve_identifiers: bool,
}

#[derive(Debug, Clone)]
pub struct ModelOutput {
    pub text: String,
    pub latency_ms: u64,
}

#[derive(Debug, thiserror::Error)]
pub enum ModelError {
    #[error("Hy-MT2 timed out")]
    Timeout,
    #[error("Hy-MT2 is unavailable: {0}")]
    Unavailable(String),
    #[error("Hy-MT2 returned an invalid response: {0}")]
    InvalidResponse(String),
}

impl ModelError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::Timeout => "PROVIDER_TIMEOUT",
            Self::Unavailable(_) => "PROVIDER_UNAVAILABLE",
            Self::InvalidResponse(_) => "EMPTY_TRANSLATION",
        }
    }
}

#[async_trait]
pub trait TranslationModel: Send + Sync {
    async fn translate(&self, input: TranslationInput) -> Result<ModelOutput, ModelError>;
    async fn health(&self) -> Result<(), ModelError>;
}

#[derive(Clone)]
pub struct HyMt2Client {
    client: reqwest::Client,
    base_url: String,
    model_id: String,
    api_key: Option<String>,
    max_tokens: u32,
    semaphore: Arc<Semaphore>,
}

impl HyMt2Client {
    pub fn new(config: &Config) -> anyhow::Result<Self> {
        let client = reqwest::Client::builder()
            .connect_timeout(std::time::Duration::from_millis(1_500))
            .timeout(config.model_timeout)
            .pool_max_idle_per_host(config.model_max_concurrency)
            .build()?;
        Ok(Self {
            client,
            base_url: config.model_base_url.clone(),
            model_id: config.model_id.clone(),
            api_key: config.model_api_key.clone(),
            max_tokens: config.model_max_tokens,
            semaphore: Arc::new(Semaphore::new(config.model_max_concurrency)),
        })
    }

    fn prompt(input: &TranslationInput) -> Result<String, ModelError> {
        let target = chinese_language_name(&input.target_language)
            .ok_or_else(|| ModelError::InvalidResponse("unsupported target language".to_owned()))?;
        Ok(format!(
            "将以下文本翻译为{target}，注意只需要输出翻译后的结果，不要额外解释：\n\n{}",
            input.text
        ))
    }

    fn request(&self, input: &TranslationInput) -> Result<ChatRequest, ModelError> {
        Ok(ChatRequest {
            model: self.model_id.clone(),
            messages: vec![ChatMessage {
                role: "user",
                content: Self::prompt(input)?,
            }],
            temperature: 0.7,
            top_p: 0.6,
            top_k: 20,
            repetition_penalty: 1.05,
            max_tokens: self.max_tokens,
        })
    }
}

#[async_trait]
impl TranslationModel for HyMt2Client {
    async fn translate(&self, input: TranslationInput) -> Result<ModelOutput, ModelError> {
        let _permit = self
            .semaphore
            .acquire()
            .await
            .map_err(|_| ModelError::Unavailable("model queue was closed".to_owned()))?;
        let started = Instant::now();
        let mut request = self
            .client
            .post(format!("{}/chat/completions", self.base_url));
        if let Some(api_key) = &self.api_key {
            request = request.bearer_auth(api_key);
        }
        let response = request
            .json(&self.request(&input)?)
            .send()
            .await
            .map_err(map_reqwest)?;
        let status = response.status();
        if !status.is_success() {
            return Err(ModelError::Unavailable(format!("HTTP {status}")));
        }
        let response: ChatResponse = response
            .json()
            .await
            .map_err(|error| ModelError::InvalidResponse(error.to_string()))?;
        let text = response
            .choices
            .into_iter()
            .next()
            .map(|choice| choice.message.content.trim().to_owned())
            .filter(|text| !text.is_empty())
            .ok_or_else(|| ModelError::InvalidResponse("empty model output".to_owned()))?;
        if is_suspicious_output(&text) {
            return Err(ModelError::InvalidResponse(
                "model returned prompt instructions instead of a translation".to_owned(),
            ));
        }
        Ok(ModelOutput {
            text,
            latency_ms: started.elapsed().as_millis() as u64,
        })
    }

    async fn health(&self) -> Result<(), ModelError> {
        let mut request = self.client.get(format!("{}/models", self.base_url));
        if let Some(api_key) = &self.api_key {
            request = request.bearer_auth(api_key);
        }
        let response = request.send().await.map_err(map_reqwest)?;
        if response.status() == StatusCode::OK {
            Ok(())
        } else {
            Err(ModelError::Unavailable(format!(
                "HTTP {}",
                response.status()
            )))
        }
    }
}

fn map_reqwest(error: reqwest::Error) -> ModelError {
    if error.is_timeout() {
        ModelError::Timeout
    } else {
        ModelError::Unavailable(error.to_string())
    }
}

fn is_suspicious_output(text: &str) -> bool {
    [
        "只需要输出翻译后的结果",
        "不要额外解释",
        "保留URL、代码标识符",
        "only output the translated result",
    ]
    .iter()
    .any(|instruction| text.contains(instruction))
}

#[derive(Debug, Serialize)]
struct ChatRequest {
    model: String,
    messages: Vec<ChatMessage>,
    temperature: f32,
    top_p: f32,
    top_k: i32,
    repetition_penalty: f32,
    max_tokens: u32,
}

#[derive(Debug, Serialize)]
struct ChatMessage {
    role: &'static str,
    content: String,
}

#[derive(Debug, Deserialize)]
struct ChatResponse {
    choices: Vec<ChatChoice>,
}

#[derive(Debug, Deserialize)]
struct ChatChoice {
    message: ChatResponseMessage,
}

#[derive(Debug, Deserialize)]
struct ChatResponseMessage {
    content: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prompt_uses_official_target_language_style() {
        let prompt = HyMt2Client::prompt(&TranslationInput {
            text: "Hello".to_owned(),
            source_language: Some("en".to_owned()),
            target_language: "zh".to_owned(),
            preserve_identifiers: true,
        })
        .unwrap();
        assert!(prompt.starts_with("将以下文本翻译为中文"));
        assert!(prompt.ends_with("Hello"));
    }

    #[test]
    fn rejects_leaked_prompt_instructions() {
        assert!(is_suspicious_output(
            "保留URL、代码标识符、占位符、数字和品牌名称不变。"
        ));
        assert!(!is_suspicious_output("开始识别"));
    }
}
