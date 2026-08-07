use std::collections::{HashMap, HashSet};

use async_trait::async_trait;
use reqwest::{Client, StatusCode};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::time::Duration;

use crate::{
    config::Config,
    contract::{SemanticTranslationRequest, TranslationGroup, resolved_render_slots},
    error::AppError,
};

pub const PROMPT_VERSION: &str = "semantic-translation-qwen-v4-geometry-safe-output";

#[derive(Clone, Debug)]
pub struct ModelTranslation {
    pub group_id: String,
    pub translated_text: String,
    pub detected_source_language: String,
    pub target_language: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelHealth {
    pub configured: bool,
    pub reachable: bool,
    pub model_available: bool,
    pub execution_mode: &'static str,
}

#[async_trait]
pub trait TranslationModel: Send + Sync {
    fn request_json(
        &self,
        _request: &SemanticTranslationRequest,
        _groups: &[TranslationGroup],
    ) -> Option<String> {
        None
    }

    async fn translate(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
    ) -> Result<Vec<ModelTranslation>, AppError>;

    async fn health(&self) -> ModelHealth {
        ModelHealth {
            configured: true,
            reachable: true,
            model_available: true,
            execution_mode: "embedded",
        }
    }
}

pub struct QwenClient {
    client: Client,
    endpoint: String,
    models_endpoint: String,
    api_key: Option<String>,
    model: String,
    reasoning_effort: Option<String>,
    max_tokens: u32,
    execution_mode: &'static str,
}

impl QwenClient {
    pub fn new(config: &Config) -> Result<Self, AppError> {
        let client = Client::builder()
            .timeout(config.qwen_timeout)
            .build()
            .map_err(|error| AppError::configuration(error.to_string()))?;
        let base = config.qwen_base_url.trim_end_matches('/');
        let endpoint = if base.ends_with("/chat/completions") {
            base.to_owned()
        } else {
            format!("{base}/chat/completions")
        };
        let api_base = endpoint.strip_suffix("/chat/completions").unwrap_or(base);
        let models_endpoint = format!("{api_base}/models");
        Ok(Self {
            client,
            endpoint,
            models_endpoint,
            api_key: config.qwen_api_key.clone(),
            model: config.qwen_model.clone(),
            reasoning_effort: config.qwen_reasoning_effort.clone(),
            max_tokens: config.qwen_max_tokens,
            execution_mode: if is_loopback_endpoint(base) {
                "local"
            } else {
                "remote"
            },
        })
    }

    fn chat_request(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
    ) -> Result<ChatRequest<'_>, AppError> {
        let payload = ModelPayload::from_request(request, groups);
        let user_content = serde_json::to_string(&payload)
            .map_err(|error| AppError::Upstream(error.to_string()))?;
        Ok(ChatRequest {
            model: self.model.clone(),
            messages: vec![
                ChatMessage {
                    role: "system",
                    content: SYSTEM_PROMPT.to_owned(),
                },
                ChatMessage {
                    role: "user",
                    content: user_content,
                },
            ],
            temperature: 0.0,
            seed: 0,
            max_tokens: self.max_tokens,
            response_format: model_response_format(groups),
            reasoning_effort: self.reasoning_effort.as_deref(),
        })
    }
}

#[async_trait]
impl TranslationModel for QwenClient {
    fn request_json(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
    ) -> Option<String> {
        if groups.is_empty() {
            return None;
        }
        self.chat_request(request, groups)
            .and_then(|body| {
                serde_json::to_string(&body).map_err(|error| AppError::Upstream(error.to_string()))
            })
            .ok()
    }

    async fn translate(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
    ) -> Result<Vec<ModelTranslation>, AppError> {
        if groups.is_empty() {
            return Ok(Vec::new());
        }
        if self.api_key.is_none() && self.endpoint.starts_with("https://") {
            return Err(AppError::ModelUnavailable(
                "QWEN_API_KEY is required for the configured Qwen endpoint".to_owned(),
            ));
        }
        let body = self.chat_request(request, groups)?;
        let mut builder = self.client.post(&self.endpoint).json(&body);
        if let Some(api_key) = &self.api_key {
            builder = builder.bearer_auth(api_key);
        }
        let response = builder
            .send()
            .await
            .map_err(|error| AppError::Upstream(format!("Qwen request failed: {error}")))?;
        let status = response.status();
        let response_text = response
            .text()
            .await
            .map_err(|error| AppError::Upstream(format!("Qwen response failed: {error}")))?;
        if !status.is_success() {
            return Err(AppError::Upstream(format!(
                "Qwen returned HTTP {}{}",
                status.as_u16(),
                safe_upstream_suffix(status, &response_text)
            )));
        }
        let envelope: ChatResponse = serde_json::from_str(&response_text)
            .map_err(|error| AppError::Upstream(format!("invalid Qwen envelope: {error}")))?;
        let content = completion_content(&envelope)?;
        parse_model_response(content, groups)
    }

    async fn health(&self) -> ModelHealth {
        let configured = self.api_key.is_some() || !self.endpoint.starts_with("https://");
        if !configured {
            return ModelHealth {
                configured: false,
                reachable: false,
                model_available: false,
                execution_mode: self.execution_mode,
            };
        }
        let mut builder = self
            .client
            .get(&self.models_endpoint)
            .timeout(Duration::from_secs(2));
        if let Some(api_key) = &self.api_key {
            builder = builder.bearer_auth(api_key);
        }
        let Ok(response) = builder.send().await else {
            return ModelHealth {
                configured: true,
                reachable: false,
                model_available: false,
                execution_mode: self.execution_mode,
            };
        };
        if !response.status().is_success() {
            return ModelHealth {
                configured: true,
                reachable: false,
                model_available: false,
                execution_mode: self.execution_mode,
            };
        }
        let model_available = response
            .json::<ModelCatalog>()
            .await
            .map(|catalog| catalog.data.iter().any(|item| item.id == self.model))
            .unwrap_or(false);
        ModelHealth {
            configured: true,
            reachable: true,
            model_available,
            execution_mode: self.execution_mode,
        }
    }
}

#[derive(Serialize)]
struct ChatRequest<'a> {
    model: String,
    messages: Vec<ChatMessage>,
    temperature: f32,
    seed: u64,
    max_tokens: u32,
    response_format: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    reasoning_effort: Option<&'a str>,
}

#[derive(Serialize)]
struct ChatMessage {
    role: &'static str,
    content: String,
}

#[derive(Deserialize)]
struct ChatResponse {
    choices: Vec<ChatChoice>,
    #[serde(default)]
    usage: Option<ChatUsage>,
}

#[derive(Deserialize)]
struct ChatChoice {
    message: ChatResponseMessage,
    #[serde(default)]
    finish_reason: Option<String>,
}

#[derive(Deserialize)]
struct ChatResponseMessage {
    content: String,
}

#[derive(Deserialize)]
struct ChatUsage {
    prompt_tokens: u64,
    completion_tokens: u64,
    total_tokens: u64,
}

fn completion_content(envelope: &ChatResponse) -> Result<&str, AppError> {
    let choice = envelope
        .choices
        .first()
        .ok_or_else(|| AppError::Upstream("Qwen returned no choices".to_owned()))?;
    if choice.finish_reason.as_deref() == Some("length") {
        let usage = envelope.usage.as_ref().map_or_else(
            || "token usage unavailable".to_owned(),
            |usage| {
                format!(
                    "promptTokens={}, completionTokens={}, totalTokens={}",
                    usage.prompt_tokens, usage.completion_tokens, usage.total_tokens
                )
            },
        );
        return Err(AppError::Upstream(format!(
            "Qwen output truncated because the context or completion limit was reached ({usage}); increase the Ollama num_ctx setting or reduce the request size"
        )));
    }
    let content = choice.message.content.trim();
    if content.is_empty() {
        return Err(AppError::Upstream(
            "Qwen returned no message content".to_owned(),
        ));
    }
    Ok(content)
}

#[derive(Deserialize)]
struct ModelCatalog {
    data: Vec<ModelDescriptor>,
}

#[derive(Deserialize)]
struct ModelDescriptor {
    id: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ModelPayload<'a> {
    task: &'static str,
    scene: &'a str,
    translation_mode: &'a str,
    document_context: Option<&'a str>,
    viewport: ModelViewport,
    translate_groups: Vec<ModelGroup<'a>>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ModelViewport {
    width: i32,
    height: i32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ModelGroup<'a> {
    group_id: &'a str,
    role: &'a str,
    source_text: &'a str,
    source_language: &'a str,
    target_language: &'a str,
    reading_order: i32,
    normalized_bounds: [f32; 4],
    layout_shape: &'a str,
    render_slots: Vec<[f32; 4]>,
    region_lines: Vec<ModelRegionLine<'a>>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ModelRegionLine<'a> {
    text: &'a str,
    reading_order: i32,
    normalized_bounds: [f32; 4],
}

impl<'a> ModelPayload<'a> {
    fn from_request(
        request: &'a SemanticTranslationRequest,
        groups: &'a [TranslationGroup],
    ) -> Self {
        let regions = request
            .regions
            .iter()
            .map(|region| (region.region_id.as_str(), region))
            .collect::<HashMap<_, _>>();
        let width = request.viewport.width as f32;
        let height = request.viewport.height as f32;
        Self {
            task: "Translate every translateGroups.sourceText faithfully and completely into its targetLanguage. Use regionLines and renderSlots only to reconstruct reading order and semantic structure; never split the output back into OCR lines. Preserve currency values, units, numbers, AIMS/AIMS-Next, URLs, brands, names, and organization identities. Do not summarize, invent, merge, delete, or abbreviate content. Output only the required results schema.",
            scene: &request.scene,
            translation_mode: &request.translation.mode,
            document_context: request
                .translation
                .use_document_context
                .then_some(request.document_context.text.as_str()),
            viewport: ModelViewport {
                width: request.viewport.width,
                height: request.viewport.height,
            },
            translate_groups: groups
                .iter()
                .map(|group| {
                    let group_regions = group
                        .member_region_ids
                        .iter()
                        .filter_map(|id| regions.get(id.as_str()).copied())
                        .collect::<Vec<_>>();
                    let region = group_regions.first().copied();
                    let render_slots = resolved_render_slots(group, &group_regions);
                    ModelGroup {
                        group_id: &group.group_id,
                        role: &group.role,
                        source_text: &group.source_text,
                        source_language: region
                            .and_then(|item| item.source_language.as_deref())
                            .unwrap_or("auto"),
                        target_language: region
                            .and_then(|item| item.target_language.as_deref())
                            .unwrap_or("auto"),
                        reading_order: group.reading_order,
                        normalized_bounds: [
                            group.bounds.left as f32 / width,
                            group.bounds.top as f32 / height,
                            group.bounds.right as f32 / width,
                            group.bounds.bottom as f32 / height,
                        ],
                        layout_shape: &group.layout_shape,
                        render_slots: render_slots
                            .iter()
                            .map(|slot| {
                                [
                                    slot.left as f32 / width,
                                    slot.top as f32 / height,
                                    slot.right as f32 / width,
                                    slot.bottom as f32 / height,
                                ]
                            })
                            .collect(),
                        region_lines: group_regions
                            .iter()
                            .map(|item| ModelRegionLine {
                                text: &item.text,
                                reading_order: item.reading_order,
                                normalized_bounds: [
                                    item.bounds.left as f32 / width,
                                    item.bounds.top as f32 / height,
                                    item.bounds.right as f32 / width,
                                    item.bounds.bottom as f32 / height,
                                ],
                            })
                            .collect(),
                    }
                })
                .collect(),
        }
    }
}

fn model_response_format(groups: &[TranslationGroup]) -> Value {
    let group_ids = groups
        .iter()
        .map(|group| group.group_id.as_str())
        .collect::<Vec<_>>();
    let result_count = group_ids.len();
    json!({
        "type": "json_schema",
        "json_schema": {
            "name": "semantic_translation",
            "strict": true,
            "schema": {
                "type": "object",
                "properties": {
                    "results": {
                        "type": "array",
                        "minItems": result_count,
                        "maxItems": result_count,
                        "items": {
                            "type": "object",
                            "properties": {
                                "groupId": { "type": "string", "enum": group_ids },
                                "translatedText": { "type": "string" },
                                "detectedSourceLanguage": { "type": "string" },
                                "targetLanguage": { "type": "string" }
                            },
                            "required": [
                                "groupId",
                                "translatedText",
                                "detectedSourceLanguage",
                                "targetLanguage"
                            ],
                            "additionalProperties": false
                        }
                    }
                },
                "required": ["results"],
                "additionalProperties": false
            }
        }
    })
}

#[derive(Deserialize)]
struct ModelResponse {
    results: Vec<ModelResult>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ModelResult {
    group_id: String,
    translated_text: String,
    detected_source_language: String,
    target_language: String,
}

fn parse_model_response(
    raw: &str,
    groups: &[TranslationGroup],
) -> Result<Vec<ModelTranslation>, AppError> {
    let json = strip_json_fence(raw);
    let response: ModelResponse = serde_json::from_str(json)
        .map_err(|error| AppError::Upstream(format!("invalid Qwen result JSON: {error}")))?;
    let expected = groups
        .iter()
        .map(|group| group.group_id.as_str())
        .collect::<HashSet<_>>();
    let mut seen = HashSet::with_capacity(response.results.len());
    let mut by_id = HashMap::with_capacity(response.results.len());
    for result in response.results {
        if !expected.contains(result.group_id.as_str()) {
            return Err(AppError::Upstream(
                "Qwen returned an unknown groupId".to_owned(),
            ));
        }
        if !seen.insert(result.group_id.clone()) {
            return Err(AppError::Upstream(
                "Qwen returned a duplicate groupId".to_owned(),
            ));
        }
        let translated_text = sanitize_model_translation(&result.translated_text)?;
        if translated_text.is_empty() {
            return Err(AppError::Upstream(
                "Qwen returned an empty translation".to_owned(),
            ));
        }
        let group = groups
            .iter()
            .find(|group| group.group_id == result.group_id)
            .expect("groupId membership was validated above");
        let translated_text = normalize_preserved_identifiers(group, translated_text);
        validate_critical_invariants(group, &translated_text)?;
        by_id.insert(
            result.group_id.clone(),
            ModelTranslation {
                group_id: result.group_id,
                translated_text,
                detected_source_language: normalized_language(&result.detected_source_language),
                target_language: normalized_language(&result.target_language),
            },
        );
    }
    if seen.len() != expected.len() {
        return Err(AppError::Upstream(
            "Qwen omitted one or more groupIds".to_owned(),
        ));
    }
    Ok(groups
        .iter()
        .filter_map(|group| by_id.remove(&group.group_id))
        .collect())
}

fn normalize_preserved_identifiers(group: &TranslationGroup, translated: String) -> String {
    if !group.source_text.contains("AIMS") {
        return translated;
    }
    translated
        .replace("AIM S", "AIMS")
        .replace("AIMS - Next", "AIMS-Next")
        .replace("AIMS–Next", "AIMS-Next")
}

fn sanitize_model_translation(raw: &str) -> Result<String, AppError> {
    let trimmed = raw.trim();
    let protocol_markers = [
        "”},{", "\"},{", "”}]}]}", "\"}]}]}", "”}]}", "\"}]}", ".user:{", "```",
    ];
    let first_marker = protocol_markers
        .iter()
        .filter_map(|marker| trimmed.find(marker))
        .min();
    let candidate = first_marker.map_or(trimmed, |index| &trimmed[..index]);
    let cleaned = candidate
        .trim()
        .trim_end_matches(['\"', '\'', '”', '`', ',', '{', '}', '[', ']'])
        .trim()
        .to_owned();
    let forbidden_protocol_tokens = [
        ".user:{",
        "\"groupId\"",
        "\"translatedText\"",
        "translateGroups",
        "```",
    ];
    if forbidden_protocol_tokens
        .iter()
        .any(|token| cleaned.contains(token))
    {
        return Err(AppError::Upstream(
            "Qwen translation contained response-protocol text".to_owned(),
        ));
    }
    Ok(cleaned)
}

fn validate_critical_invariants(
    group: &TranslationGroup,
    translated: &str,
) -> Result<(), AppError> {
    let source = group.source_text.as_str();
    let missing_currency = (source.contains("C$")
        && !(translated.contains("C$") || translated.contains("加元")))
        || (source.contains("US$") && !(translated.contains("US$") || translated.contains("美元")));
    if missing_currency {
        return Err(AppError::Upstream(format!(
            "Qwen translation lost a currency unit for group {}",
            group.group_id
        )));
    }
    if source.contains("AIMS") && !translated.contains("AIMS") {
        return Err(AppError::Upstream(format!(
            "Qwen translation lost the AIMS identifier for group {}",
            group.group_id
        )));
    }
    Ok(())
}

fn strip_json_fence(raw: &str) -> &str {
    let trimmed = raw.trim();
    if !trimmed.starts_with("```") || !trimmed.ends_with("```") {
        return trimmed;
    }
    let Some(newline) = trimmed.find('\n') else {
        return trimmed;
    };
    trimmed[newline + 1..trimmed.len() - 3].trim()
}

fn normalized_language(value: &str) -> String {
    let value = value.trim().to_lowercase();
    if value.is_empty() {
        "auto".to_owned()
    } else {
        value
    }
}

fn safe_upstream_suffix(status: StatusCode, body: &str) -> String {
    if status.is_client_error() && body.len() <= 300 {
        format!(": {body}")
    } else {
        String::new()
    }
}

fn is_loopback_endpoint(endpoint: &str) -> bool {
    endpoint.starts_with("http://127.0.0.1:")
        || endpoint.starts_with("http://localhost:")
        || endpoint.starts_with("http://[::1]:")
}

const SYSTEM_PROMPT: &str = r#"You are a professional screen OCR translation engine.
The user supplies a JSON document containing the complete visible context and translateGroups.
Use documentContext, reading order, roles, and normalized bounds only to disambiguate meaning.
Use each group's regionLines and renderSlots to understand wrapped text and reading order, not to produce visual line breaks.
Translate every translateGroups item as one complete semantic unit.
Translate faithfully and completely. Every source phrase, modifier, and relationship must be represented in the translation.
Do not shorten or compress headlines, even when a shorter paraphrase sounds natural.
Translate named entities consistently across groups and never abbreviate person, place, organization, brand, or section names.
Translate Perimeter Institute for Theoretical Physics as 圆周理论物理研究所.
Translate Next Einstein centres as 下一代爱因斯坦中心; never omit Next.
When document context identifies a person named only by family name in one group, use that person's conventional full target-language name.
Do not split a translated group back into visual OCR lines.
Do not summarize, explain, add facts, merge groups, delete groups, or invent IDs.
Never echo or reproduce the input JSON document.
Preserve numeric values and currency meaning. C$ means Canadian dollars and US$ means US dollars.
Keep AIMS and AIMS-Next visible and unchanged when they appear in the source.
Preserve dates, URLs, brands, identifiers, and placeholders exactly unless translation is required by grammar.
For AUTO_BIDIRECTIONAL, translate Chinese groups to English and non-Chinese natural-language groups to Chinese.
Return only JSON: {"results":[{"groupId":"...","translatedText":"...","detectedSourceLanguage":"en","targetLanguage":"zh"}]}.
Every input groupId must appear exactly once."#;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        config::Config,
        contract::{
            Bounds, DocumentContext, OcrRegion, SCHEMA_VERSION, SemanticTranslationRequest,
            TranslationGroup, TranslationOptions, Viewport,
        },
    };
    use axum::{
        Json, Router,
        routing::{get, post},
    };
    use serde_json::{Value, json};
    use tokio::net::TcpListener;

    fn groups() -> Vec<TranslationGroup> {
        vec![TranslationGroup {
            group_id: "group-1".to_owned(),
            role: "BODY".to_owned(),
            translation_unit: "GROUP".to_owned(),
            source_text: "Hello".to_owned(),
            member_region_ids: vec!["region-1".to_owned()],
            reading_order: 0,
            grouping_confidence: 1.0,
            grouping_evidence: vec![],
            source_line_count: None,
            bounds: Bounds {
                left: 0,
                top: 0,
                right: 10,
                bottom: 10,
            },
            render_slots: vec![],
            layout_shape: "RECT".to_owned(),
        }]
    }

    #[tokio::test]
    async fn uses_unauthenticated_local_openai_compatible_qwen() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let router = Router::new()
            .route(
                "/v1/models",
                get(|| async {
                    Json(json!({"data": [{"id": "qwen3.5:9b"}]}))
                }),
            )
            .route(
                "/v1/chat/completions",
                post(|Json(body): Json<Value>| async move {
                    assert_eq!(body["model"], "qwen3.5:9b");
                    assert_eq!(body["reasoning_effort"], "none");
                    assert_eq!(body["temperature"], 0.0);
                    assert_eq!(body["seed"], 0);
                    assert_eq!(body["max_tokens"], 4096);
                    assert_eq!(body["response_format"]["type"], "json_schema");
                    assert_eq!(
                        body["response_format"]["json_schema"]["schema"]["properties"]
                            ["results"]["minItems"],
                        1
                    );
                    assert_eq!(
                        body["response_format"]["json_schema"]["schema"]["properties"]
                            ["results"]["items"]["properties"]["groupId"]["enum"],
                        json!(["group-1"])
                    );
                    Json(json!({
                        "choices": [{
                            "message": {
                                "content": "{\"results\":[{\"groupId\":\"group-1\",\"translatedText\":\"你好\",\"detectedSourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}]}"
                            }
                        }]
                    }))
                }),
            );
        let server = tokio::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        let mut config = Config::for_test(":memory:".to_owned());
        config.qwen_base_url = format!("http://{address}/v1");
        config.qwen_model = "qwen3.5:9b".to_owned();
        let client = QwenClient::new(&config).unwrap();
        let request = semantic_request();
        let saved_request: Value =
            serde_json::from_str(&client.request_json(&request, &request.groups).unwrap()).unwrap();

        let health = client.health().await;
        assert!(health.configured);
        assert!(health.reachable);
        assert!(health.model_available);
        assert_eq!(health.execution_mode, "local");
        assert_eq!(saved_request["model"], "qwen3.5:9b");
        assert_eq!(saved_request["reasoning_effort"], "none");
        assert_eq!(saved_request["max_tokens"], 4096);
        assert_eq!(saved_request["response_format"]["type"], "json_schema");
        let saved_user_payload: Value =
            serde_json::from_str(saved_request["messages"][1]["content"].as_str().unwrap())
                .unwrap();
        assert_eq!(
            saved_user_payload["translateGroups"][0]["groupId"],
            "group-1"
        );
        let translated = client.translate(&request, &request.groups).await.unwrap();
        assert_eq!(translated[0].translated_text, "你好");
        assert_eq!(translated[0].target_language, "zh");
        server.abort();
    }

    fn semantic_request() -> SemanticTranslationRequest {
        SemanticTranslationRequest {
            schema_version: SCHEMA_VERSION,
            request_id: "request-1".to_owned(),
            session_id: "session-1".to_owned(),
            generation: 1,
            translation_revision: 1,
            scene: "STATIC_IMAGE".to_owned(),
            viewport: Viewport {
                width: 1080,
                height: 2400,
                rotation_degrees: 0,
            },
            translation: TranslationOptions {
                mode: "ENGLISH_TO_CHINESE".to_owned(),
                source_language: "auto".to_owned(),
                target_language: "zh".to_owned(),
                preserve_identifiers: true,
                use_document_context: true,
            },
            document_context: DocumentContext {
                text: "Hello".to_owned(),
                source_language: "en".to_owned(),
                reading_order_region_ids: vec!["region-1".to_owned()],
            },
            groups: groups(),
            regions: vec![OcrRegion {
                region_id: "region-1".to_owned(),
                group_id: "group-1".to_owned(),
                source_revision: 1,
                text: "Hello".to_owned(),
                raw_text: None,
                corrections: vec![],
                source_language: Some("en".to_owned()),
                target_language: Some("zh".to_owned()),
                reading_order: 0,
                block_id: Some("block-1".to_owned()),
                line_index: Some(0),
                confidence: 1.0,
                bounds: Bounds {
                    left: 10,
                    top: 20,
                    right: 200,
                    bottom: 80,
                },
                component_bounds: vec![],
            }],
            debug_capture: None,
        }
    }

    #[test]
    fn accepts_fenced_json_and_keeps_input_order() {
        let parsed = parse_model_response(
            "```json\n{\"results\":[{\"groupId\":\"group-1\",\"translatedText\":\"你好\",\"detectedSourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}]}\n```",
            &groups(),
        )
        .unwrap();
        assert_eq!(parsed[0].translated_text, "你好");
    }

    #[test]
    fn reports_context_truncation_before_attempting_to_parse_json() {
        let envelope: ChatResponse = serde_json::from_value(json!({
            "choices": [{
                "finish_reason": "length",
                "message": {"content": "{\"results\":[{\"translatedText\":\""}
            }],
            "usage": {
                "prompt_tokens": 4071,
                "completion_tokens": 25,
                "total_tokens": 4096
            }
        }))
        .unwrap();

        let error = completion_content(&envelope).unwrap_err();
        assert!(error.to_string().contains("Qwen output truncated"));
        assert!(error.to_string().contains("promptTokens=4071"));
        assert!(!error.to_string().contains("invalid Qwen result JSON"));
    }

    #[test]
    fn rejects_unknown_group_ids() {
        let error = parse_model_response(
            "{\"results\":[{\"groupId\":\"other\",\"translatedText\":\"你好\",\"detectedSourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}]}",
            &groups(),
        )
        .unwrap_err();
        assert!(error.to_string().contains("unknown groupId"));
    }

    #[test]
    fn removes_only_unambiguous_protocol_suffixes_from_translation_text() {
        let parsed = parse_model_response(
            "{\"results\":[{\"groupId\":\"group-1\",\"translatedText\":\"你好。”},{\",\"detectedSourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}]}",
            &groups(),
        )
        .unwrap();
        assert_eq!(parsed[0].translated_text, "你好。");

        let parsed = parse_model_response(
            "{\"results\":[{\"groupId\":\"group-1\",\"translatedText\":\"你好。”}]}]}`.user:{\",\"detectedSourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}]}",
            &groups(),
        )
        .unwrap();
        assert_eq!(parsed[0].translated_text, "你好。");
    }

    #[test]
    fn rejects_protocol_text_that_cannot_be_safely_removed_as_a_suffix() {
        let error = sanitize_model_translation("你好 \"groupId\" 世界").unwrap_err();
        assert!(error.to_string().contains("response-protocol text"));
    }

    #[test]
    fn repairs_only_known_spacing_drift_in_preserved_aims_identifiers() {
        let mut group = groups().remove(0);
        group.source_text = "AIMS and AIMS-Next".to_owned();
        assert_eq!(
            normalize_preserved_identifiers(&group, "AIM S 与 AIMS - Next 计划".to_owned()),
            "AIMS 与 AIMS-Next 计划"
        );
    }
}
