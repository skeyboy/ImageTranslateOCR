use std::{sync::RwLock, time::Instant};

use async_trait::async_trait;
use ocr_translation_core::model::{
    ModelTranslationFailure, build_model_prompt, literal_identifiers, missing_literal_identifiers,
    parse_translation_content_unvalidated,
};
use reqwest::{Client, Proxy};
use serde_json::{Value, json};

use crate::{
    config::Config,
    contract::{SemanticTranslationRequest, TranslationGroup},
    error::AppError,
    qwen::{ModelHealth, ModelTranslation, TranslationModel},
};

pub struct GeminiNativeClient {
    client: Client,
    endpoint: String,
    api_key: Option<String>,
    model: String,
    thinking_level: String,
    request_timeout: std::time::Duration,
    proxy_configured: bool,
    request_timings: RwLock<std::collections::HashMap<String, Value>>,
}

impl GeminiNativeClient {
    pub fn new(config: &Config) -> Result<Self, AppError> {
        let mut builder = Client::builder();
        if let Some(proxy_url) = config.gemini_proxy_url.as_deref() {
            let proxy = Proxy::all(proxy_url).map_err(|error| {
                AppError::configuration(format!("invalid GEMINI_PROXY_URL: {error}"))
            })?;
            builder = builder.proxy(proxy);
        }
        let client = builder
            .build()
            .map_err(|error| AppError::configuration(error.to_string()))?;
        let model = config.gemini_model.trim();
        if model.is_empty()
            || !model
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
        {
            return Err(AppError::configuration(
                "GEMINI_MODEL contains invalid characters",
            ));
        }
        Ok(Self {
            client,
            endpoint: format!(
                "{}/models/{}:generateContent",
                config.gemini_base_url.trim_end_matches('/'),
                model
            ),
            api_key: config.gemini_api_key.clone(),
            model: model.to_owned(),
            thinking_level: config.gemini_thinking_level.to_ascii_uppercase(),
            request_timeout: config.gemini_timeout,
            proxy_configured: config.gemini_proxy_url.is_some(),
            request_timings: RwLock::new(std::collections::HashMap::new()),
        })
    }

    fn request_body(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
    ) -> Result<Value, AppError> {
        let prompt = build_model_prompt(request, groups).map_err(AppError::from)?;
        let response_schema = prompt
            .response_format
            .pointer("/json_schema/schema")
            .cloned()
            .ok_or_else(|| AppError::configuration("translation response schema is missing"))?;
        let mut generation_config = json!({
            "temperature": 0,
            "maxOutputTokens": prompt.recommended_max_tokens,
            "responseMimeType": "application/json",
            "responseJsonSchema": response_schema
        });
        if self.model.to_ascii_lowercase().starts_with("gemini-3") {
            generation_config["thinkingConfig"] = json!({
                "includeThoughts": true,
                "thinkingLevel": self.thinking_level
            });
        }
        Ok(json!({
            "systemInstruction": {"parts": [{"text": prompt.system}]},
            "contents": [{"role": "user", "parts": [{"text": prompt.user}]}],
            "generationConfig": generation_config
        }))
    }

    fn repair_request_body(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
    ) -> Result<Value, AppError> {
        let mut body = self.request_body(request, groups)?;
        let requirements = groups
            .iter()
            .map(|group| {
                format!(
                    "- groupId {}: {}",
                    group.group_id,
                    literal_identifiers(&group.source_text).join(", ")
                )
            })
            .collect::<Vec<_>>()
            .join("\n");
        let system = body
            .pointer_mut("/systemInstruction/parts/0/text")
            .and_then(|value| value.as_str())
            .ok_or_else(|| AppError::configuration("Gemini repair prompt is missing"))?
            .to_owned();
        body["systemInstruction"]["parts"][0]["text"] = Value::from(format!(
            "{system}\n\nCORRECTION RETRY: Translate only the supplied groups. Preserve each required literal identifier exactly, but surrounding quotation marks and punctuation may follow the target language.\n{requirements}"
        ));
        Ok(body)
    }

    async fn generate(&self, api_key: &str, body: &Value) -> Result<Value, AppError> {
        let response = self
            .client
            .post(&self.endpoint)
            .timeout(self.request_timeout)
            .header("x-goog-api-key", api_key)
            .json(body)
            .send()
            .await
            .map_err(|error| AppError::Upstream(format!("Gemini request failed: {error}")))?;
        let status = response.status();
        let raw = response
            .text()
            .await
            .map_err(|error| AppError::Upstream(format!("Gemini response failed: {error}")))?;
        if !status.is_success() {
            let detail = serde_json::from_str::<Value>(&raw)
                .ok()
                .and_then(|value| {
                    value
                        .pointer("/error/message")
                        .and_then(Value::as_str)
                        .map(str::to_owned)
                })
                .unwrap_or_else(|| "upstream error details unavailable".to_owned());
            let detail = detail.chars().take(300).collect::<String>();
            return Err(AppError::Upstream(format!(
                "Gemini returned HTTP {}: {detail}",
                status.as_u16(),
            )));
        }
        serde_json::from_str(&raw)
            .map_err(|error| AppError::Upstream(format!("invalid Gemini response: {error}")))
    }

    fn answer_text(response: &Value) -> Result<String, AppError> {
        let text = response
            .pointer("/candidates/0/content/parts")
            .and_then(Value::as_array)
            .map(|parts| {
                parts
                    .iter()
                    .filter(|part| part.get("thought").and_then(Value::as_bool) != Some(true))
                    .filter_map(|part| part.get("text").and_then(Value::as_str))
                    .collect::<String>()
            })
            .unwrap_or_default();
        let text = text.trim();
        if text.is_empty() {
            return Err(AppError::Upstream(
                "Gemini returned no answer text".to_owned(),
            ));
        }
        Ok(text.to_owned())
    }
}

#[async_trait]
impl TranslationModel for GeminiNativeClient {
    fn request_json(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
    ) -> Option<String> {
        self.request_body(request, groups)
            .ok()
            .and_then(|body| serde_json::to_string(&body).ok())
    }

    async fn translate(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
    ) -> Result<Vec<ModelTranslation>, AppError> {
        if groups.is_empty() {
            return Ok(Vec::new());
        }
        let api_key = self.api_key.as_deref().ok_or_else(|| {
            AppError::ModelUnavailable("GEMINI_API_KEY or GOOGLE_API_KEY is required".to_owned())
        })?;
        let body = self.request_body(request, groups)?;
        let started = Instant::now();
        let envelope = self.generate(api_key, &body).await?;
        let answer = Self::answer_text(&envelope)?;
        let mut translations =
            parse_translation_content_unvalidated(&answer, groups).map_err(AppError::from)?;
        let repair_groups = if request.translation.preserve_identifiers {
            groups
                .iter()
                .filter(|group| {
                    translations
                        .iter()
                        .find(|item| item.group_id == group.group_id)
                        .is_some_and(|item| {
                            !missing_literal_identifiers(group, &item.translated_text).is_empty()
                        })
                })
                .cloned()
                .collect::<Vec<_>>()
        } else {
            Vec::new()
        };
        let mut repair_timing = Value::Null;
        if !repair_groups.is_empty() {
            let repair_body = self.repair_request_body(request, &repair_groups)?;
            let repair_started = Instant::now();
            let repair_result = self
                .generate(api_key, &repair_body)
                .await
                .and_then(|envelope| {
                    let repair_answer = Self::answer_text(&envelope)?;
                    let repaired =
                        parse_translation_content_unvalidated(&repair_answer, &repair_groups)
                            .map_err(AppError::from)?;
                    Ok((repaired, envelope))
                });
            repair_timing = json!({
                "durationMs": repair_started.elapsed().as_millis() as u64,
                "groupCount": repair_groups.len(),
                "succeeded": repair_result.is_ok()
            });
            match repair_result {
                Ok((repaired, _)) => {
                    for group in &repair_groups {
                        let repaired_item =
                            repaired.iter().find(|item| item.group_id == group.group_id);
                        let missing = repaired_item
                            .map(|item| missing_literal_identifiers(group, &item.translated_text))
                            .unwrap_or_else(|| literal_identifiers(&group.source_text));
                        if let Some(current) = translations
                            .iter_mut()
                            .find(|item| item.group_id == group.group_id)
                        {
                            if missing.is_empty() {
                                *current = repaired_item.expect("validated repair item").clone();
                            } else {
                                current.failure = Some(identifier_failure(group, &missing));
                            }
                        }
                    }
                }
                Err(error) => {
                    for group in &repair_groups {
                        if let Some(current) = translations
                            .iter_mut()
                            .find(|item| item.group_id == group.group_id)
                        {
                            let missing =
                                missing_literal_identifiers(group, &current.translated_text);
                            current.failure = Some(ModelTranslationFailure {
                                code: "IDENTIFIER_REPAIR_FAILED".to_owned(),
                                message: format!(
                                    "Identifier repair failed for group {}: {error}",
                                    group.group_id
                                ),
                                retryable: true,
                            });
                            if missing.is_empty() {
                                current.failure = None;
                            }
                        }
                    }
                }
            }
        }
        let elapsed_ms = started.elapsed().as_millis() as u64;
        self.request_timings
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .insert(
                request.request_id.clone(),
                json!({
                    "actualModelRequest": body,
                    "providerTotalMs": elapsed_ms,
                    "thinkingControlMode": "thinking_level",
                    "thinkingLevel": self.thinking_level.to_ascii_lowercase(),
                    "actualThinkingParameter": "generationConfig.thinkingConfig.thinkingLevel",
                    "proxyConfigured": self.proxy_configured,
                    "usageMetadata": envelope.get("usageMetadata").cloned().unwrap_or(Value::Null)
                    ,"repair": repair_timing
                }),
            );
        Ok(translations)
    }

    async fn health(&self) -> ModelHealth {
        ModelHealth {
            configured: self.api_key.is_some(),
            reachable: self.api_key.is_some(),
            model_available: self.api_key.is_some(),
            execution_mode: "remote",
        }
    }

    fn take_timing(&self, request_id: &str) -> Option<Value> {
        self.request_timings
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .remove(request_id)
    }
}

fn identifier_failure(group: &TranslationGroup, missing: &[String]) -> ModelTranslationFailure {
    ModelTranslationFailure {
        code: "IDENTIFIER_PRESERVATION_FAILED".to_owned(),
        message: format!(
            "Translation still misses identifiers {} for group {} after correction retry",
            missing.join(", "),
            group.group_id
        ),
        retryable: true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> (SemanticTranslationRequest, Vec<TranslationGroup>) {
        let request: SemanticTranslationRequest = serde_json::from_str(include_str!(
            "../../ocr-translation-core/examples/v4-minimal-request.json"
        ))
        .unwrap();
        let groups = ocr_translation_core::planning_v4::build_regions_first_plan(&request)
            .translation_groups();
        (request, groups)
    }

    #[test]
    fn builds_native_generate_content_request_with_schema_and_thinking_level() {
        let mut config = Config::for_test(":memory:".to_owned());
        config.gemini_api_key = Some("secret".to_owned());
        config.gemini_thinking_level = "high".to_owned();
        let client = GeminiNativeClient::new(&config).unwrap();
        let (request, groups) = fixture();

        let body = client.request_body(&request, &groups).unwrap();

        assert!(body.pointer("/systemInstruction/parts/0/text").is_some());
        assert!(body.pointer("/contents/0/parts/0/text").is_some());
        assert_eq!(
            body.pointer("/generationConfig/responseMimeType"),
            Some(&Value::from("application/json"))
        );
        assert_eq!(
            body.pointer("/generationConfig/temperature"),
            Some(&Value::from(0))
        );
        assert_eq!(
            body.pointer("/generationConfig/thinkingConfig/thinkingLevel"),
            Some(&Value::from("HIGH"))
        );
        assert!(
            body.pointer("/generationConfig/responseJsonSchema/properties/translations")
                .is_some()
        );
        assert!(!body.to_string().contains("secret"));
    }

    #[test]
    fn ignores_thought_parts_when_extracting_structured_answer() {
        let response = json!({
            "candidates": [{"content": {"parts": [
                {"thought": true, "text": "internal reasoning"},
                {"text": "{\"translations\":[]}"}
            ]}}]
        });
        assert_eq!(
            GeminiNativeClient::answer_text(&response).unwrap(),
            "{\"translations\":[]}"
        );
    }

    #[test]
    fn joins_multiple_non_thought_answer_parts() {
        let response = json!({
            "candidates": [{"content": {"parts": [
                {"text": "{\"translations\":"},
                {"thought": true, "text": "internal reasoning"},
                {"text": "[]}"}
            ]}}]
        });
        assert_eq!(
            GeminiNativeClient::answer_text(&response).unwrap(),
            "{\"translations\":[]}"
        );
    }
}
