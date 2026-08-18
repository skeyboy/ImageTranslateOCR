use std::{
    collections::{HashMap, HashSet},
    hash::{DefaultHasher, Hash, Hasher},
    sync::{Arc, RwLock},
};

use async_trait::async_trait;
pub use ocr_translation_core::model::ModelTranslation;
use ocr_translation_core::{
    contract::ThinkingControlMode as RequestThinkingControlMode,
    model::{adaptive_max_tokens, build_model_prompt},
};
use reqwest::{Client, StatusCode};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::time::{Duration, Instant};

use crate::{
    config::{Config, ThinkingControlMode, TranslationProvider},
    contract::{SemanticTranslationRequest, TranslationGroup},
    error::AppError,
};

pub const PROMPT_VERSION: &str = ocr_translation_core::model::PROMPT_VERSION;

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

    fn take_timing(&self, _request_id: &str) -> Option<Value> {
        None
    }
}

pub struct QwenClient {
    client: Client,
    endpoint: String,
    models_endpoint: String,
    api_key: Option<String>,
    model: String,
    thinking_mode: ThinkingControlMode,
    thinking_level: String,
    max_tokens: u32,
    request_timeout: Duration,
    execution_mode: &'static str,
    provider_name: &'static str,
    api_key_env: &'static str,
    translation_cache: RwLock<HashMap<u64, ModelTranslation>>,
    request_timings: RwLock<HashMap<String, Value>>,
}

impl QwenClient {
    pub fn new(config: &Config) -> Result<Self, AppError> {
        Self::new_qwen_model(config, &config.qwen_model)
    }

    pub fn new_qwen_model(config: &Config, model: &str) -> Result<Self, AppError> {
        Self::from_settings(
            &config.qwen_base_url,
            config.qwen_api_key.clone(),
            model,
            config.qwen_thinking_mode,
            config.qwen_thinking_level.clone(),
            config.qwen_max_tokens,
            config.qwen_timeout,
            "Qwen",
            "QWEN_API_KEY",
        )
    }

    pub fn new_openlux(config: &Config) -> Result<Self, AppError> {
        Self::new_openlux_model(config, config.openlux_model.as_deref().unwrap_or_default())
    }

    pub fn new_openlux_model(config: &Config, model: &str) -> Result<Self, AppError> {
        Self::from_settings(
            &config.openlux_base_url,
            config.openlux_api_key.clone(),
            model,
            config.openlux_thinking_mode,
            config.openlux_thinking_level.clone(),
            config.openlux_max_tokens,
            config.openlux_timeout,
            "OpenLux",
            "OPENLUX_API_KEY",
        )
    }

    fn from_settings(
        base_url: &str,
        api_key: Option<String>,
        model: &str,
        thinking_mode: ThinkingControlMode,
        thinking_level: String,
        max_tokens: u32,
        request_timeout: Duration,
        provider_name: &'static str,
        api_key_env: &'static str,
    ) -> Result<Self, AppError> {
        let client = Client::builder()
            .build()
            .map_err(|error| AppError::configuration(error.to_string()))?;
        let base = base_url.trim_end_matches('/');
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
            api_key,
            model: model.to_owned(),
            thinking_mode,
            thinking_level,
            max_tokens,
            request_timeout,
            execution_mode: if is_loopback_endpoint(base) {
                "local"
            } else {
                "remote"
            },
            provider_name,
            api_key_env,
            translation_cache: RwLock::new(HashMap::new()),
            request_timings: RwLock::new(HashMap::new()),
        })
    }

    fn chat_request(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
    ) -> Result<ChatRequest, AppError> {
        self.chat_request_with_system_prompt(request, groups, SYSTEM_PROMPT.to_owned())
    }

    fn chat_request_with_system_prompt(
        &self,
        request: &SemanticTranslationRequest,
        groups: &[TranslationGroup],
        system_prompt: String,
    ) -> Result<ChatRequest, AppError> {
        let prompt = build_model_prompt(request, groups).map_err(AppError::from)?;
        let user_content = prompt.user;
        let system_prompt = if system_prompt == SYSTEM_PROMPT {
            prompt.system
        } else {
            system_prompt
        };
        let gemini_3 = is_gemini_3_model(&self.model);
        let (thinking_mode, thinking_level) = self.thinking_control(request)?;
        let reasoning_effort = (thinking_mode == ThinkingControlMode::ReasoningEffort)
            .then_some(thinking_level.clone());
        let google = (thinking_mode == ThinkingControlMode::ThinkingLevel).then(|| {
            serde_json::json!({
                "thinking_config": {"thinking_level": thinking_level.to_ascii_uppercase()}
            })
        });
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
            temperature: (!gemini_3).then_some(0.0),
            seed: (!gemini_3).then_some(0),
            max_tokens: adaptive_max_tokens(groups).min(self.max_tokens),
            response_format: prompt.response_format,
            reasoning_effort,
            google,
        })
    }

    fn thinking_control(
        &self,
        request: &SemanticTranslationRequest,
    ) -> Result<(ThinkingControlMode, String), AppError> {
        let mode = request
            .translation
            .thinking_control_mode
            .map(|mode| match mode {
                RequestThinkingControlMode::None => ThinkingControlMode::None,
                RequestThinkingControlMode::ReasoningEffort => ThinkingControlMode::ReasoningEffort,
                RequestThinkingControlMode::ThinkingLevel => ThinkingControlMode::ThinkingLevel,
            })
            .unwrap_or(self.thinking_mode);
        let level = request
            .translation
            .thinking_level
            .clone()
            .unwrap_or_else(|| self.thinking_level.clone());
        if is_gpt_4_1_model(&self.model) {
            return Ok((ThinkingControlMode::None, level));
        }
        if mode == ThinkingControlMode::ThinkingLevel && !is_gemini_3_model(&self.model) {
            return Err(AppError::invalid(
                "thinkingLevel is only supported for configured Gemini 3 models",
            ));
        }
        if mode == ThinkingControlMode::ThinkingLevel
            && !matches!(level.as_str(), "minimal" | "low" | "medium" | "high")
        {
            return Err(AppError::invalid(
                "thinkingLevel must be minimal, low, medium, or high",
            ));
        }
        if mode == ThinkingControlMode::ReasoningEffort
            && !matches!(
                level.as_str(),
                "none" | "minimal" | "low" | "medium" | "high"
            )
        {
            return Err(AppError::invalid(
                "reasoning_effort must be none, minimal, low, medium, or high",
            ));
        }
        Ok((mode, level))
    }

    async fn completion(
        &self,
        body: &ChatRequest,
        group_count: usize,
    ) -> Result<CompletionResult, AppError> {
        let timeout = self.completion_timeout(group_count);
        let started = Instant::now();
        let mut builder = self.client.post(&self.endpoint).timeout(timeout).json(body);
        if let Some(api_key) = &self.api_key {
            builder = builder.bearer_auth(api_key);
        }
        let response = builder
            .send()
            .await
            .map_err(|error| provider_request_error(self.provider_name, error, timeout))?;
        let response_headers_ms = started.elapsed().as_millis() as u64;
        let status = response.status();
        let response_text = response.text().await.map_err(|error| {
            AppError::Upstream(format!("{} response failed: {error}", self.provider_name))
        })?;
        if !status.is_success() {
            return Err(AppError::Upstream(format!(
                "{} returned HTTP {}{}",
                self.provider_name,
                status.as_u16(),
                safe_upstream_suffix(status, &response_text)
            )));
        }
        let total_ms = started.elapsed().as_millis() as u64;
        let envelope: ChatResponse = serde_json::from_str(&response_text).map_err(|error| {
            AppError::Upstream(format!(
                "invalid {} response envelope: {error}",
                self.provider_name
            ))
        })?;
        let checkpoint = provider_latency_checkpoint(&response_text);
        let (ttft_ms, generation_ms) = checkpoint_durations(checkpoint.as_ref());
        Ok(CompletionResult {
            content: completion_content(&envelope)?.to_owned(),
            timing: serde_json::json!({
                "dnsMs": Value::Null,
                "tlsMs": Value::Null,
                "responseHeadersMs": response_headers_ms,
                "responseDownloadMs": total_ms.saturating_sub(response_headers_ms),
                "providerTotalMs": total_ms,
                "ttftMs": ttft_ms,
                "modelGenerationMs": generation_ms,
                "providerLatencyCheckpoint": checkpoint
            }),
        })
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
            return Err(AppError::ModelUnavailable(format!(
                "{} is required for the configured {} endpoint",
                self.api_key_env, self.provider_name
            )));
        }
        let mut translations = {
            let cached = self
                .translation_cache
                .read()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            groups
                .iter()
                .filter_map(|group| {
                    cached
                        .get(&self.semantic_cache_key(request, group))
                        .map(|item| {
                            let mut item = item.clone();
                            item.group_id = group.group_id.clone();
                            item
                        })
                })
                .collect::<Vec<_>>()
        };
        let missing_groups = groups
            .iter()
            .filter(|group| {
                !translations
                    .iter()
                    .any(|item| item.group_id == group.group_id)
            })
            .cloned()
            .collect::<Vec<_>>();
        if missing_groups.is_empty() {
            let (thinking_mode, thinking_level) = self.thinking_control(request)?;
            self.store_timing(
                &request.request_id,
                serde_json::json!({
                    "cacheHitGroups": groups.len(), "cacheMissGroups": 0,
                    "actualModelRequest": Value::Null,
                    "thinkingControlMode": thinking_mode.as_str(),
                    "thinkingLevel": thinking_level,
                    "actualThinkingParameter": "cache",
                    "dnsMs": Value::Null, "tlsMs": Value::Null,
                    "providerTotalMs": 0, "ttftMs": 0, "modelGenerationMs": 0
                }),
            );
            return Ok(translations);
        }
        let body = self.chat_request(request, &missing_groups)?;
        let actual_model_request = serde_json::to_value(&body).ok();
        let completion = self.completion(&body, missing_groups.len()).await?;
        let mut timing = completion.timing;
        let (thinking_mode, thinking_level) = self.thinking_control(request)?;
        timing["actualModelRequest"] = actual_model_request.unwrap_or(Value::Null);
        timing["thinkingControlMode"] = Value::from(thinking_mode.as_str());
        timing["thinkingLevel"] = Value::from(thinking_level);
        timing["actualThinkingParameter"] = Value::from(match thinking_mode {
            ThinkingControlMode::None => "none",
            ThinkingControlMode::ReasoningEffort => "reasoning_effort",
            ThinkingControlMode::ThinkingLevel => "google.thinking_config.thinking_level",
        });
        timing["cacheHitGroups"] = Value::from(groups.len() - missing_groups.len());
        timing["cacheMissGroups"] = Value::from(missing_groups.len());
        let mut fresh =
            parse_model_response_without_critical_validation(&completion.content, &missing_groups)?;
        let repair_groups = missing_groups
            .iter()
            .filter(|group| {
                fresh
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
            let repaired_completion = self.completion(&repair_body, repair_groups.len()).await?;
            timing["repair"] = repaired_completion.timing;
            let repaired = parse_model_response(&repaired_completion.content, &repair_groups)?;
            for repaired_translation in repaired {
                if let Some(translation) = fresh
                    .iter_mut()
                    .find(|translation| translation.group_id == repaired_translation.group_id)
                {
                    *translation = repaired_translation;
                }
            }
        }

        validate_model_translations(&missing_groups, &fresh)?;
        {
            let mut cache = self
                .translation_cache
                .write()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            if cache.len() > 1024 {
                cache.clear();
            }
            for item in &fresh {
                if let Some(group) = missing_groups
                    .iter()
                    .find(|group| group.group_id == item.group_id)
                {
                    cache.insert(self.semantic_cache_key(request, group), item.clone());
                }
            }
        }
        translations.extend(fresh);
        translations.sort_by_key(|item| {
            groups
                .iter()
                .position(|group| group.group_id == item.group_id)
                .unwrap_or(usize::MAX)
        });
        validate_model_translations(groups, &translations)?;
        self.store_timing(&request.request_id, timing);
        Ok(translations)
    }

    async fn health(&self) -> ModelHealth {
        let configured = !self.model.trim().is_empty()
            && (self.api_key.is_some() || !self.endpoint.starts_with("https://"));
        if !configured {
            return ModelHealth {
                configured: false,
                reachable: false,
                model_available: false,
                execution_mode: self.execution_mode,
            };
        }
        let health_timeout = if self.execution_mode == "remote" {
            Duration::from_secs(10)
        } else {
            Duration::from_secs(2)
        };
        let mut builder = self
            .client
            .get(&self.models_endpoint)
            .timeout(health_timeout);
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

    fn take_timing(&self, request_id: &str) -> Option<Value> {
        self.request_timings
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(request_id)
    }
}

impl QwenClient {
    fn store_timing(&self, request_id: &str, timing: Value) {
        self.request_timings
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(request_id.to_owned(), timing);
    }

    fn semantic_cache_key(
        &self,
        request: &SemanticTranslationRequest,
        group: &TranslationGroup,
    ) -> u64 {
        let mut hasher = DefaultHasher::new();
        self.provider_name.hash(&mut hasher);
        self.model.hash(&mut hasher);
        PROMPT_VERSION.hash(&mut hasher);
        request.translation.mode.hash(&mut hasher);
        request
            .translation
            .direct_structured_output
            .hash(&mut hasher);
        request
            .translation
            .compact_provider_prompt
            .hash(&mut hasher);
        let (thinking_mode, thinking_level) = self
            .thinking_control(request)
            .unwrap_or((ThinkingControlMode::None, String::new()));
        thinking_mode.hash(&mut hasher);
        thinking_level.hash(&mut hasher);
        request.translation.source_language.hash(&mut hasher);
        request.translation.target_language.hash(&mut hasher);
        group.role.hash(&mut hasher);
        group.source_text.trim().hash(&mut hasher);
        hasher.finish()
    }
}

struct CompletionResult {
    content: String,
    timing: Value,
}

fn provider_latency_checkpoint(raw: &str) -> Option<Value> {
    let value: Value = serde_json::from_str(raw).ok()?;
    value
        .pointer("/usage/service_tier_details/latency_checkpoint")
        .or_else(|| value.pointer("/usage/latency_checkpoint"))
        .cloned()
}

fn checkpoint_durations(checkpoint: Option<&Value>) -> (Option<u64>, Option<u64>) {
    let Some(checkpoint) = checkpoint.and_then(Value::as_object) else {
        return (None, None);
    };
    let lookup = |suffix: &str| {
        checkpoint.iter().find_map(|(key, value)| {
            key.to_ascii_lowercase()
                .ends_with(suffix)
                .then(|| value.as_f64())
                .flatten()
        })
    };
    let ttft = lookup("ttft");
    let ttlt = lookup("ttlt");
    let milliseconds = |seconds: f64| (seconds.max(0.0) * 1000.0).round() as u64;
    (
        ttft.map(milliseconds),
        ttlt.zip(ttft)
            .map(|(last, first)| milliseconds(last - first)),
    )
}

#[derive(Clone)]
pub struct ActiveTranslationModel {
    pub provider: TranslationProvider,
    pub model_name: String,
    pub model: Arc<dyn TranslationModel>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TranslationProviderStatus {
    pub provider: &'static str,
    pub model: String,
    pub active: bool,
    pub configured: bool,
    pub reachable: bool,
    pub model_available: bool,
    pub execution_mode: &'static str,
}

pub struct TranslationModelRegistry {
    models: HashMap<(TranslationProvider, String), ActiveTranslationModel>,
    active: RwLock<(TranslationProvider, String)>,
}

impl TranslationModelRegistry {
    pub fn qwen(model_name: String, model: Arc<dyn TranslationModel>) -> Self {
        Self::new(
            TranslationProvider::Qwen,
            model_name.clone(),
            vec![(TranslationProvider::Qwen, model_name, model)],
        )
    }

    pub fn new(
        active_provider: TranslationProvider,
        active_model: String,
        models: Vec<(TranslationProvider, String, Arc<dyn TranslationModel>)>,
    ) -> Self {
        let models = models
            .into_iter()
            .map(|(provider, model_name, model)| {
                (
                    (provider, model_name.clone()),
                    ActiveTranslationModel {
                        provider,
                        model_name,
                        model,
                    },
                )
            })
            .collect::<HashMap<_, _>>();
        let active = (active_provider, active_model);
        assert!(
            models.contains_key(&active),
            "active translation provider and model must exist"
        );
        Self {
            models,
            active: RwLock::new(active),
        }
    }

    pub fn active(&self) -> ActiveTranslationModel {
        let selection = self
            .active
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        self.models
            .get(&selection)
            .expect("active translation provider and model must remain registered")
            .clone()
    }

    pub fn resolve(
        &self,
        provider: Option<&str>,
        model: Option<&str>,
    ) -> Result<ActiveTranslationModel, AppError> {
        if provider.is_none() && model.is_none() {
            return Ok(self.active());
        }
        let current = self.active();
        let provider = provider
            .map(|value| {
                TranslationProvider::parse(value).ok_or_else(|| {
                    AppError::invalid(
                        "X-Translation-Provider must be qwen, openlux, or gemini-native",
                    )
                })
            })
            .transpose()?
            .unwrap_or(current.provider);
        let model = model
            .filter(|value| !value.trim().is_empty())
            .map(str::to_owned)
            .or_else(|| (provider == current.provider).then_some(current.model_name))
            .or_else(|| {
                self.models
                    .keys()
                    .filter(|(candidate, _)| *candidate == provider)
                    .map(|(_, model)| model.clone())
                    .min()
            })
            .ok_or_else(|| {
                AppError::configuration(format!(
                    "translation provider {} has no configured models",
                    provider.as_str()
                ))
            })?;
        self.models
            .get(&(provider, model.clone()))
            .cloned()
            .ok_or_else(|| {
                AppError::invalid(format!(
                    "model {model} is not configured for provider {}",
                    provider.as_str()
                ))
            })
    }

    pub async fn select(&self, provider: TranslationProvider, model: &str) -> Result<(), AppError> {
        let key = (provider, model.to_owned());
        let selected = self.models.get(&key).ok_or_else(|| {
            AppError::configuration(format!(
                "model {model} is not configured for provider {}",
                provider.as_str(),
            ))
        })?;
        if !selected.model.health().await.configured {
            return Err(AppError::configuration(format!(
                "model {model} for provider {} is not configured",
                provider.as_str(),
            )));
        }
        *self
            .active
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = key;
        Ok(())
    }

    pub async fn statuses(&self) -> Vec<TranslationProviderStatus> {
        let active = self.active();
        let mut statuses = Vec::new();
        let mut health_checks = tokio::task::JoinSet::new();
        for selected in self.models.values().cloned() {
            health_checks.spawn(async move {
                let health = selected.model.health().await;
                (selected, health)
            });
        }
        while let Some(result) = health_checks.join_next().await {
            if let Ok((selected, health)) = result {
                statuses.push(TranslationProviderStatus {
                    provider: selected.provider.as_str(),
                    model: selected.model_name.clone(),
                    active: selected.provider == active.provider
                        && selected.model_name == active.model_name,
                    configured: health.configured,
                    reachable: health.reachable,
                    model_available: health.model_available,
                    execution_mode: health.execution_mode,
                });
            }
        }
        for provider in TranslationProvider::ALL {
            if !statuses
                .iter()
                .any(|status| status.provider == provider.as_str())
            {
                statuses.push(TranslationProviderStatus {
                    provider: provider.as_str(),
                    model: String::new(),
                    active: false,
                    configured: false,
                    reachable: false,
                    model_available: false,
                    execution_mode: "unconfigured",
                });
            }
        }
        statuses.sort_by(|first, second| {
            let provider_order = |provider: &str| usize::from(provider != "qwen");
            provider_order(first.provider)
                .cmp(&provider_order(second.provider))
                .then_with(|| first.model.cmp(&second.model))
        });
        statuses
    }
}

#[derive(Serialize)]
struct ChatRequest {
    model: String,
    messages: Vec<ChatMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    temperature: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    seed: Option<u64>,
    max_tokens: u32,
    response_format: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    reasoning_effort: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    google: Option<Value>,
}

fn is_gemini_3_model(model: &str) -> bool {
    model.to_ascii_lowercase().starts_with("gemini-3")
}

fn is_gpt_4_1_model(model: &str) -> bool {
    model.to_ascii_lowercase().starts_with("gpt-4.1")
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

#[cfg(test)]
fn model_response_format(groups: &[TranslationGroup]) -> Value {
    build_model_prompt(&semantic_request_for_schema(groups), groups)
        .unwrap()
        .response_format
}

#[cfg(test)]
fn semantic_request_for_schema(groups: &[TranslationGroup]) -> SemanticTranslationRequest {
    let mut request: SemanticTranslationRequest = serde_json::from_str(include_str!(
        "../../ocr-translation-core/examples/v4-minimal-request.json"
    ))
    .unwrap();
    request.groups = groups.to_vec();
    request
}

#[derive(Deserialize)]
struct ModelResponse {
    #[serde(default)]
    translations: TranslationCollection,
    #[serde(default)]
    results: Vec<ModelResult>,
}

#[derive(Deserialize, Default)]
#[serde(untagged)]
enum TranslationCollection {
    Keyed(HashMap<String, KeyedModelResult>),
    Array(Vec<ModelResult>),
    #[default]
    Empty,
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
    let results = match response.translations {
        TranslationCollection::Keyed(items) => items
            .into_iter()
            .map(|(group_id, result)| ModelResult {
                group_id,
                translated_text: result.translated_text,
                detected_source_language: result.detected_source_language,
                target_language: result.target_language,
            })
            .collect(),
        TranslationCollection::Array(items) => items,
        TranslationCollection::Empty => response.results,
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
                failure: None,
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

fn provider_request_error(
    provider_name: &str,
    error: reqwest::Error,
    timeout: Duration,
) -> AppError {
    if error.is_timeout() {
        return AppError::Upstream(format!(
            "{provider_name} request timed out after {} seconds",
            timeout.as_secs()
        ));
    }
    if error.is_connect() {
        return AppError::ModelUnavailable(format!(
            "Cannot connect to the configured {provider_name} endpoint: {error}"
        ));
    }
    AppError::Upstream(format!("{provider_name} request failed: {error}"))
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
Return only JSON: {"translations":[{"groupId":"<groupId>","translatedText":"...","detectedSourceLanguage":"en","targetLanguage":"zh"}]}.
Every input groupId must appear exactly once in the array. Every translatedText must be non-empty."#;

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
        let mut config = Config::for_test(":memory:".to_owned());
        config.qwen_base_url = "http://127.0.0.1:11434/v1".to_owned();
        config.qwen_timeout = Duration::from_secs(210);
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
                    assert_eq!(body["max_tokens"], 1024);
                    assert_eq!(body["response_format"]["type"], "json_schema");
                    assert_eq!(
                        body["response_format"]["json_schema"]["schema"]["properties"]
                            ["translations"]["items"]["properties"]
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
        assert_eq!(saved_request["max_tokens"], 1024);
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
    async fn openlux_uses_its_endpoint_model_and_bearer_token() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let router = Router::new().route(
            "/v1/chat/completions",
            post(
                |headers: axum::http::HeaderMap, Json(body): Json<Value>| async move {
                    assert_eq!(headers["authorization"], "Bearer openlux-secret");
                    assert_eq!(body["model"], "openlux-model-id");
                    Json(json!({
                        "choices": [{
                            "message": {
                                "content": "{\"translations\":{\"group-1\":{\"translatedText\":\"你好\",\"detectedSourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}}}"
                            }
                        }]
                    }))
                },
            ),
        );
        let server = tokio::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        let mut config = Config::for_test(":memory:".to_owned());
        config.openlux_base_url = format!("http://{address}/v1");
        config.openlux_api_key = Some("openlux-secret".to_owned());
        config.openlux_model = Some("openlux-model-id".to_owned());
        let client = QwenClient::new_openlux(&config).unwrap();
        let request = semantic_request();

        let saved_request: Value =
            serde_json::from_str(&client.request_json(&request, &request.groups).unwrap()).unwrap();
        assert_eq!(saved_request["model"], "openlux-model-id");
        let translated = client.translate(&request, &request.groups).await.unwrap();
        assert_eq!(translated[0].translated_text, "你好");
        server.abort();
    }

    #[test]
    fn thinking_control_cases_are_mutually_exclusive_for_all_levels() {
        let mut config = Config::for_test(":memory:".to_owned());
        config.openlux_api_key = Some("test".to_owned());
        config.openlux_model = Some("gemini-3.5-flash-lite".to_owned());
        config.openlux_models = vec!["gemini-3.5-flash-lite".to_owned()];
        let client = QwenClient::new_openlux(&config).unwrap();

        for level in ["minimal", "low", "medium", "high"] {
            let mut baseline = semantic_request();
            baseline.translation.thinking_control_mode = Some(RequestThinkingControlMode::None);
            baseline.translation.thinking_level = Some(level.to_owned());
            let body =
                serde_json::to_value(client.chat_request(&baseline, &baseline.groups).unwrap())
                    .unwrap();
            assert!(body.get("reasoning_effort").is_none());
            assert!(body.get("google").is_none());

            let mut reasoning = baseline.clone();
            reasoning.translation.thinking_control_mode =
                Some(RequestThinkingControlMode::ReasoningEffort);
            let body =
                serde_json::to_value(client.chat_request(&reasoning, &reasoning.groups).unwrap())
                    .unwrap();
            assert_eq!(body["reasoning_effort"], level);
            assert!(body.get("google").is_none());

            let mut native = baseline.clone();
            native.translation.thinking_control_mode =
                Some(RequestThinkingControlMode::ThinkingLevel);
            let body = serde_json::to_value(client.chat_request(&native, &native.groups).unwrap())
                .unwrap();
            assert_eq!(
                body["google"]["thinking_config"]["thinking_level"],
                level.to_ascii_uppercase()
            );
            assert!(body.get("reasoning_effort").is_none());
        }

        let gpt_client = QwenClient::new_openlux_model(&config, "gpt-4.1").unwrap();
        let mut gpt_request = semantic_request();
        gpt_request.translation.thinking_control_mode =
            Some(RequestThinkingControlMode::ReasoningEffort);
        gpt_request.translation.thinking_level = Some("low".to_owned());
        let gpt_body = serde_json::to_value(
            gpt_client
                .chat_request(&gpt_request, &gpt_request.groups)
                .unwrap(),
        )
        .unwrap();
        assert!(gpt_body.get("reasoning_effort").is_none());
        assert!(gpt_body.get("google").is_none());
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
        let mut config = Config::for_test(":memory:".to_owned());
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
                direct_structured_output: false,
                compact_provider_prompt: true,
                thinking_control_mode: None,
                thinking_level: None,
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
    fn fixed_array_schema_requires_group_id_and_non_empty_translation() {
        let schema = model_response_format(&groups());
        assert_eq!(
            schema["json_schema"]["schema"]["properties"]["translations"]["type"],
            "array"
        );
        assert_eq!(
            schema["json_schema"]["schema"]["properties"]["translations"]["items"]["properties"]["translatedText"]
                ["minLength"],
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
                failure: None,
            },
            ModelTranslation {
                group_id: "second".to_owned(),
                translated_text: "第二段的正确译文。".to_owned(),
                detected_source_language: "en".to_owned(),
                target_language: "zh".to_owned(),
                failure: None,
            },
        ];

        remove_adjacent_duplicate_suffixes(&mut translations);

        assert_eq!(translations[0].translated_text, "第一段的正确译文。");
        assert_eq!(translations[1].translated_text, "第二段的正确译文。");
    }
}
