use std::{env, fs, sync::Arc};

use image_translate_v4_service::{
    QwenConfig, V4TranslationService, contract::SemanticTranslationRequest, qwen::QwenClient,
};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let request_path = env::args()
        .nth(1)
        .ok_or("usage: translate_v4 <schema-v4-request.json>")?;
    let config = QwenConfig {
        qwen_base_url: env::var("QWEN_BASE_URL")?,
        qwen_api_key: env::var("QWEN_API_KEY")
            .ok()
            .filter(|value| !value.is_empty()),
        qwen_model: env::var("QWEN_MODEL")?,
        qwen_reasoning_effort: env::var("QWEN_REASONING_EFFORT")
            .ok()
            .filter(|value| !value.is_empty()),
        qwen_max_tokens: env::var("QWEN_MAX_TOKENS")
            .unwrap_or_else(|_| "4096".to_owned())
            .parse()?,
        qwen_timeout_seconds: env::var("QWEN_TIMEOUT_SECONDS")
            .unwrap_or_else(|_| "210".to_owned())
            .parse()?,
    };
    config.validate()?;
    let request: SemanticTranslationRequest =
        serde_json::from_str(&fs::read_to_string(request_path)?)?;
    let model_version = config.qwen_model.clone();
    let service = V4TranslationService::new(Arc::new(QwenClient::new(&config)?), model_version);
    println!(
        "{}",
        serde_json::to_string_pretty(&service.translate(request).await?)?
    );
    Ok(())
}
