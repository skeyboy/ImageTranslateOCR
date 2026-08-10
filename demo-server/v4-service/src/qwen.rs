use std::collections::{HashMap, HashSet};

use async_trait::async_trait;
use reqwest::{Client, StatusCode};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::time::Duration;

use crate::{
    config::QwenConfig,
    contract::{SemanticTranslationRequest, TranslationGroup, resolved_render_slots},
    error::V4ServiceError as AppError,
};

pub const PROMPT_VERSION: &str = "semantic-translation-qwen-v9-semantic-block-flow";

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
    request_timeout: Duration,
    execution_mode: &'static str,
}

impl QwenClient {
    pub fn new(config: &QwenConfig) -> Result<Self, AppError> {
        config.validate()?;
        let client = Client::builder()
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
            request_timeout: config.timeout(),
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
        self.chat_request_with_system_prompt(request, groups, SYSTEM_PROMPT.to_owned())
    }

    fn chat_request_with_system_prompt(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
        system_prompt: String,
    ) -> Result<ChatRequest<'_>, AppError> {
        let payload = ModelPayload::from_request(request, groups);
        let user_content = serde_json::to_string(&payload)
            .map_err(|error| AppError::Upstream(error.to_string()))?;
        Ok(ChatRequest {
            model: self.model.clone(),
            messages: vec![
                ChatMessage {
                    role: "system",
                    content: system_prompt,
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

    async fn completion(
        &self,
        body: &ChatRequest<'_>,
        group_count: usize,
    ) -> Result<String, AppError> {
        let timeout = self.completion_timeout(group_count);
        let mut builder = self.client.post(&self.endpoint).timeout(timeout).json(body);
        if let Some(api_key) = &self.api_key {
            builder = builder.bearer_auth(api_key);
        }
        let response = builder
            .send()
            .await
            .map_err(|error| qwen_request_error(error, timeout))?;
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
        Ok(completion_content(&envelope)?.to_owned())
    }

    fn completion_timeout(&self, group_count: usize) -> Duration {
        if self.execution_mode != "local" {
            return self.request_timeout;
        }
        let adaptive_seconds = 90_u64
            .saturating_add((group_count as u64).saturating_mul(5))
            .min(210);
        self.request_timeout
            .min(Duration::from_secs(adaptive_seconds))
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
        let content = self.completion(&body, groups.len()).await?;
        let mut translations = parse_model_response_without_critical_validation(&content, groups)?;
        let repair_groups = groups
            .iter()
            .filter(|group| {
                translations
                    .iter()
                    .find(|translation| translation.group_id == group.group_id)
                    .and_then(|translation| {
                        critical_invariant_violation(group, &translation.translated_text)
                    })
                    .is_some()
            })
            .cloned()
            .collect::<Vec<_>>();

        if !repair_groups.is_empty() {
            let repair_prompt = critical_repair_prompt(&repair_groups);
            let repair_body =
                self.chat_request_with_system_prompt(request, &repair_groups, repair_prompt)?;
            let repaired_content = self.completion(&repair_body, repair_groups.len()).await?;
            let repaired = parse_model_response(&repaired_content, &repair_groups)?;
            for repaired_translation in repaired {
                if let Some(translation) = translations
                    .iter_mut()
                    .find(|translation| translation.group_id == repaired_translation.group_id)
                {
                    *translation = repaired_translation;
                }
            }
        }

        validate_model_translations(groups, &translations)?;
        Ok(translations)
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
    required_literal_identifiers: Vec<&'static str>,
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
            task: "Translate each translateGroups item independently and completely. Write its non-empty translation only under the translations property whose key exactly equals that item groupId. The value for a key may translate only that group's sourceText. A group's sourceText is an already reconstructed semantic block: treat newline-separated OCR lines as one continuous passage and never translate line by line or imitate the source line breaks. Document context and neighboring groups are disambiguation context only: never copy, move, duplicate, continue, or pre-translate their content into another key. Use regionLines and renderSlots only to understand reading order and protected page geometry; layout is produced deterministically by the server, so do not add visual line breaks. Every requiredLiteralIdentifiers item must occur verbatim in that group's translatedText and keep the same grammatical and semantic role as in sourceText. Never expand, define, parenthesize, rename, or replace an identifier. Do not add parenthetical glosses or retain source-language terms in parentheses unless those parentheses already exist in sourceText. For example, 'funding for AIMS' is '对 AIMS 的资助', and 'AIMS-Next Einstein Initiative' is 'AIMS-Next 爱因斯坦计划'. Preserve currency values, units, numbers, URLs, brands, names, and organization identities. Do not summarize, invent, merge, delete, abbreviate, explain OCR errors, or add translator notes. Output only the required translations schema.",
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
                        required_literal_identifiers: required_literal_identifiers(
                            &group.source_text,
                        ),
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
    let translation_properties = groups
        .iter()
        .map(|group| {
            (
                group.group_id.clone(),
                json!({
                    "type": "object",
                    "properties": {
                        "translatedText": { "type": "string", "minLength": 1 },
                        "detectedSourceLanguage": { "type": "string", "minLength": 1 },
                        "targetLanguage": { "type": "string", "minLength": 1 }
                    },
                    "required": [
                        "translatedText",
                        "detectedSourceLanguage",
                        "targetLanguage"
                    ],
                    "additionalProperties": false
                }),
            )
        })
        .collect::<serde_json::Map<_, _>>();
    json!({
        "type": "json_schema",
        "json_schema": {
            "name": "semantic_translation",
            "strict": true,
            "schema": {
                "type": "object",
                "properties": {
                    "translations": {
                        "type": "object",
                        "properties": translation_properties,
                        "required": group_ids,
                        "additionalProperties": false
                    }
                },
                "required": ["translations"],
                "additionalProperties": false
            }
        }
    })
}

#[derive(Deserialize)]
struct ModelResponse {
    #[serde(default)]
    translations: HashMap<String, KeyedModelResult>,
    #[serde(default)]
    results: Vec<ModelResult>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct KeyedModelResult {
    translated_text: String,
    detected_source_language: String,
    target_language: String,
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
    let translations = parse_model_response_without_critical_validation(raw, groups)?;
    validate_model_translations(groups, &translations)?;
    Ok(translations)
}

fn parse_model_response_without_critical_validation(
    raw: &str,
    groups: &[TranslationGroup],
) -> Result<Vec<ModelTranslation>, AppError> {
    let json = strip_json_fence(raw);
    let response: ModelResponse = serde_json::from_str(json)
        .map_err(|error| AppError::Upstream(format!("invalid Qwen result JSON: {error}")))?;
    let results = if response.translations.is_empty() {
        response.results
    } else {
        response
            .translations
            .into_iter()
            .map(|(group_id, result)| ModelResult {
                group_id,
                translated_text: result.translated_text,
                detected_source_language: result.detected_source_language,
                target_language: result.target_language,
            })
            .collect()
    };
    let expected = groups
        .iter()
        .map(|group| group.group_id.as_str())
        .collect::<HashSet<_>>();
    let mut seen = HashSet::with_capacity(results.len());
    let mut by_id = HashMap::with_capacity(results.len());
    for result in results {
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
        let translated_text = strip_unsourced_annotation(
            group,
            normalize_preserved_identifiers(group, translated_text),
        );
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
    let mut ordered = groups
        .iter()
        .filter_map(|group| by_id.remove(&group.group_id))
        .collect::<Vec<_>>();
    remove_adjacent_duplicate_suffixes(&mut ordered);
    Ok(ordered)
}

fn remove_adjacent_duplicate_suffixes(translations: &mut [ModelTranslation]) {
    for index in 0..translations.len().saturating_sub(1) {
        let next = translations[index + 1].translated_text.trim().to_owned();
        if next.is_empty() {
            continue;
        }
        let current = translations[index].translated_text.trim_end();
        if let Some(prefix) = current.strip_suffix(&next) {
            let prefix = prefix.trim_end();
            if !prefix.is_empty() {
                translations[index].translated_text = prefix.to_owned();
            }
        }
    }
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

fn strip_unsourced_annotation(group: &TranslationGroup, translated: String) -> String {
    let source_lower = group.source_text.to_lowercase();
    if source_lower.contains("note:") || group.source_text.contains("注：") {
        return translated;
    }
    let markers = ["（注：", "（注:", "(注：", "(注:", "(Note:", "(note:"];
    let Some(index) = markers
        .iter()
        .filter_map(|marker| translated.find(marker))
        .min()
    else {
        return translated;
    };
    let prefix = translated[..index].trim().to_owned();
    if prefix.is_empty() {
        translated
    } else {
        prefix
    }
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
    if let Some(violation) = critical_invariant_violation(group, translated) {
        return Err(AppError::Upstream(violation));
    }
    Ok(())
}

fn validate_model_translations(
    groups: &[TranslationGroup],
    translations: &[ModelTranslation],
) -> Result<(), AppError> {
    for group in groups {
        let translation = translations
            .iter()
            .find(|translation| translation.group_id == group.group_id)
            .ok_or_else(|| AppError::Upstream("Qwen omitted one or more groupIds".to_owned()))?;
        validate_critical_invariants(group, &translation.translated_text)?;
    }
    Ok(())
}

fn critical_invariant_violation(group: &TranslationGroup, translated: &str) -> Option<String> {
    let source = group.source_text.as_str();
    let missing_currency = (source.contains("C$")
        && !(translated.contains("C$") || translated.contains("加元")))
        || (source.contains("US$") && !(translated.contains("US$") || translated.contains("美元")));
    if missing_currency {
        return Some(format!(
            "Qwen translation lost a currency unit for group {}",
            group.group_id
        ));
    }
    if source.contains("AIMS-Next") && !translated.contains("AIMS-Next") {
        return Some(format!(
            "Qwen translation lost the AIMS-Next identifier for group {}",
            group.group_id
        ));
    }
    let standalone_aims_required = source
        .replace("AIMS-Next", "")
        .split(|character: char| !character.is_ascii_alphanumeric())
        .any(|token| token == "AIMS");
    if standalone_aims_required && !translated.contains("AIMS") {
        return Some(format!(
            "Qwen translation lost the standalone AIMS identifier for group {}",
            group.group_id
        ));
    }
    None
}

fn required_literal_identifiers(source: &str) -> Vec<&'static str> {
    let mut identifiers = Vec::with_capacity(2);
    if source.contains("AIMS-Next") {
        identifiers.push("AIMS-Next");
    }
    if source
        .replace("AIMS-Next", "")
        .split(|character: char| !character.is_ascii_alphanumeric())
        .any(|token| token == "AIMS")
    {
        identifiers.push("AIMS");
    }
    identifiers
}

fn critical_repair_prompt(groups: &[TranslationGroup]) -> String {
    let requirements = groups
        .iter()
        .filter_map(|group| {
            let identifiers = required_literal_identifiers(&group.source_text);
            if identifiers.is_empty() {
                None
            } else {
                Some(format!(
                    "- groupId {} must contain these exact literal identifiers: {}",
                    group.group_id,
                    identifiers.join(", ")
                ))
            }
        })
        .collect::<Vec<_>>()
        .join("\n");
    format!(
        "{SYSTEM_PROMPT}\n\nCORRECTION RETRY: The previous translation violated critical source invariants. Translate only the supplied retry groups. Preserve every required literal identifier exactly, visibly, and with the same spelling. Keep each identifier in the same grammatical and semantic role as the source. Do not expand, define, parenthesize, rename, or replace an identifier with another organization or a target-language paraphrase. Examples: 'funding for AIMS' means '对 AIMS 的资助'; 'founder of AIMS' means 'AIMS 的创始人'; 'AIMS-Next Einstein Initiative' means 'AIMS-Next 爱因斯坦计划'.\n{requirements}"
    )
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

fn qwen_request_error(error: reqwest::Error, timeout: Duration) -> AppError {
    if error.is_timeout() {
        return AppError::Upstream(format!(
            "Qwen request timed out after {} seconds",
            timeout.as_secs()
        ));
    }
    if error.is_connect() {
        return AppError::ModelUnavailable(format!(
            "Cannot connect to the configured Qwen endpoint: {error}"
        ));
    }
    AppError::Upstream(format!("Qwen request failed: {error}"))
}

const SYSTEM_PROMPT: &str = r#"You are a professional screen OCR translation engine.
The user supplies a JSON document containing the complete visible context and translateGroups.
Use documentContext, reading order, roles, and normalized bounds only to disambiguate meaning.
The output is a translations object keyed by groupId. Treat every key as an isolated translation cell and bind it strictly to the sourceText with the identical groupId. Neighboring groups and documentContext are context only.
Never borrow, copy, move, duplicate, or continue text from another group into the current group.
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
Never add translator notes, parenthetical annotations, OCR corrections, guesses, or phrases such as "note" or "the source may mean".
Never echo or reproduce the input JSON document.
Preserve numeric values and currency meaning. C$ means Canadian dollars and US$ means US dollars.
Keep AIMS and AIMS-Next visible and unchanged when they appear in the source.
Every requiredLiteralIdentifiers entry is a machine-checked constraint and must appear verbatim in translatedText.
Keep each identifier in the same grammatical and semantic role as the source. Never expand, define, parenthesize, rename, or associate it with another organization. For example, translate "funding for AIMS" as "对 AIMS 的资助" and "AIMS-Next Einstein Initiative" as "AIMS-Next 爱因斯坦计划".
Preserve dates, URLs, brands, identifiers, and placeholders exactly unless translation is required by grammar.
For AUTO_BIDIRECTIONAL, translate Chinese groups to English and non-Chinese natural-language groups to Chinese.
Return only JSON: {"translations":{"<groupId>":{"translatedText":"...","detectedSourceLanguage":"en","targetLanguage":"zh"}}}.
Every input groupId must appear exactly once as a property key. Every translatedText must be non-empty."#;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        config::QwenConfig,
        contract::{
            Bounds, DocumentContext, OcrRegion, SCHEMA_VERSION, SemanticTranslationRequest,
            TranslationGroup, TranslationOptions, Viewport,
        },
    };
    use axum::{
        Json, Router,
        extract::State,
        routing::{get, post},
    };
    use serde_json::{Value, json};
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };
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

    #[test]
    fn local_timeout_scales_with_group_count_up_to_configured_limit() {
        let mut config = QwenConfig::for_test();
        config.qwen_base_url = "http://127.0.0.1:11434/v1".to_owned();
        config.qwen_timeout_seconds = 210;
        let client = QwenClient::new(&config).unwrap();

        assert_eq!(client.completion_timeout(1), Duration::from_secs(95));
        assert_eq!(client.completion_timeout(22), Duration::from_secs(200));
        assert_eq!(client.completion_timeout(30), Duration::from_secs(210));
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
                            ["translations"]["required"],
                        json!(["group-1"])
                    );
                    assert_eq!(
                        body["response_format"]["json_schema"]["schema"]["properties"]
                            ["translations"]["properties"]["group-1"]["properties"]
                            ["translatedText"]["minLength"],
                        1
                    );
                    Json(json!({
                        "choices": [{
                            "message": {
                                "content": "{\"translations\":{\"group-1\":{\"translatedText\":\"你好\",\"detectedSourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}}}"
                            }
                        }]
                    }))
                }),
            );
        let server = tokio::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        let mut config = QwenConfig::for_test();
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

    #[tokio::test]
    async fn retries_only_groups_that_lose_required_literal_identifiers() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let attempts = Arc::new(AtomicUsize::new(0));
        let router = Router::new()
            .route(
                "/v1/chat/completions",
                post(
                    |State(attempts): State<Arc<AtomicUsize>>, Json(body): Json<Value>| async move {
                        let attempt = attempts.fetch_add(1, Ordering::SeqCst);
                        let user_payload: Value =
                            serde_json::from_str(body["messages"][1]["content"].as_str().unwrap())
                                .unwrap();
                        assert_eq!(
                            user_payload["translateGroups"][0]["requiredLiteralIdentifiers"],
                            json!(["AIMS-Next"])
                        );
                        if attempt == 1 {
                            assert!(
                                body["messages"][0]["content"]
                                    .as_str()
                                    .unwrap()
                                    .contains("CORRECTION RETRY")
                            );
                        }
                        let translated = if attempt == 0 {
                            "下一代爱因斯坦中心计划"
                        } else {
                            "AIMS-Next 下一代爱因斯坦中心计划"
                        };
                        Json(json!({
                            "choices": [{
                                "message": {
                                    "content": json!({
                                        "translations": {
                                            "group-1": {
                                                "translatedText": translated,
                                                "detectedSourceLanguage": "en",
                                                "targetLanguage": "zh"
                                            }
                                        }
                                    }).to_string()
                                }
                            }]
                        }))
                    },
                ),
            )
            .with_state(attempts.clone());
        let server = tokio::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        let mut config = QwenConfig::for_test();
        config.qwen_base_url = format!("http://{address}/v1");
        let client = QwenClient::new(&config).unwrap();
        let mut request = semantic_request();
        request.groups[0].source_text = "AIMS-Next Einstein Initiative".to_owned();

        let translated = client.translate(&request, &request.groups).await.unwrap();

        assert_eq!(attempts.load(Ordering::SeqCst), 2);
        assert_eq!(
            translated[0].translated_text,
            "AIMS-Next 下一代爱因斯坦中心计划"
        );
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
    fn accepts_group_id_keyed_json() {
        let parsed = parse_model_response(
            "{\"translations\":{\"group-1\":{\"translatedText\":\"你好\",\"detectedSourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}}}",
            &groups(),
        )
        .unwrap();
        assert_eq!(parsed[0].group_id, "group-1");
        assert_eq!(parsed[0].translated_text, "你好");
    }

    #[test]
    fn keyed_schema_requires_non_empty_translation_for_every_group_id() {
        let schema = model_response_format(&groups());
        assert_eq!(
            schema["json_schema"]["schema"]["properties"]["translations"]["required"],
            json!(["group-1"])
        );
        assert_eq!(
            schema["json_schema"]["schema"]["properties"]["translations"]["properties"]["group-1"]
                ["properties"]["translatedText"]["minLength"],
            1
        );
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

    #[test]
    fn distinguishes_aims_next_from_standalone_aims_requirements() {
        assert_eq!(
            required_literal_identifiers("run through the AIMS-Next initiative"),
            vec!["AIMS-Next"]
        );
        assert_eq!(
            required_literal_identifiers("AIMS founded the AIMS-Next initiative"),
            vec!["AIMS-Next", "AIMS"]
        );

        let mut group = groups().remove(0);
        group.source_text = "run through the AIMS-Next initiative".to_owned();
        let error = validate_critical_invariants(&group, "下一代爱因斯坦中心计划")
            .unwrap_err()
            .to_string();
        assert!(error.contains("lost the AIMS-Next identifier"));
        assert!(!error.contains("standalone AIMS"));
    }

    #[test]
    fn removes_model_added_ocr_note_without_changing_the_translation() {
        let mut group = groups().remove(0);
        group.source_text = "weet Whatsapp".to_owned();

        let cleaned = strip_unsourced_annotation(
            &group,
            "WhatsApp 消息（注：原文可能存在 OCR 错误）".to_owned(),
        );

        assert_eq!(cleaned, "WhatsApp 消息");
    }

    #[test]
    fn removes_translation_copied_from_the_immediately_following_group() {
        let mut translations = vec![
            ModelTranslation {
                group_id: "first".to_owned(),
                translated_text: "第一段的正确译文。第二段的正确译文。".to_owned(),
                detected_source_language: "en".to_owned(),
                target_language: "zh".to_owned(),
            },
            ModelTranslation {
                group_id: "second".to_owned(),
                translated_text: "第二段的正确译文。".to_owned(),
                detected_source_language: "en".to_owned(),
                target_language: "zh".to_owned(),
            },
        ];

        remove_adjacent_duplicate_suffixes(&mut translations);

        assert_eq!(translations[0].translated_text, "第一段的正确译文。");
        assert_eq!(translations[1].translated_text, "第二段的正确译文。");
    }
}
