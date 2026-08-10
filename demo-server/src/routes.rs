use std::{collections::HashMap, sync::Arc, time::Instant};

use axum::{
    Json,
    extract::{Path, State},
    http::{HeaderMap, StatusCode, header::AUTHORIZATION},
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use tokio::sync::{Mutex, watch};
use tracing::{info, warn};
use uuid::Uuid;

use crate::{
    config::Config,
    contract::{
        DebugCapture, GroupTranslationResult, LAYOUT_PLAN_SCHEMA_VERSION,
        REGIONS_FIRST_SCHEMA_VERSION, ResponseMetrics, SCHEMA_VERSION, SemanticTranslationRequest,
        SemanticTranslationResponse, TranslationGroup, layout_hint, resolved_render_slots,
        source_cover_slots,
    },
    database::{
        Database, NewRenderedRequestImage, NewRequestAudit, NewRequestImage, NewRequestPayload,
    },
    error::{AppError, RequestError},
    planning::DocumentPlan,
    planning_v4::build_regions_first_plan,
    qwen::{ModelTranslation, PROMPT_VERSION, TranslationModelRegistry},
};

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<Config>,
    pub database: Database,
    pub models: Arc<TranslationModelRegistry>,
    pub cancellations: RequestCancellationRegistry,
}

#[derive(Clone, Default)]
pub struct RequestCancellationRegistry {
    active: Arc<Mutex<HashMap<String, watch::Sender<bool>>>>,
}

impl RequestCancellationRegistry {
    async fn register(&self, request_id: &str) -> Result<watch::Receiver<bool>, AppError> {
        let mut active = self.active.lock().await;
        if active.contains_key(request_id) {
            return Err(AppError::invalid("requestId is already active"));
        }
        let (sender, receiver) = watch::channel(false);
        active.insert(request_id.to_owned(), sender);
        Ok(receiver)
    }

    async fn cancel(&self, request_id: &str) -> bool {
        let mut active = self.active.lock().await;
        let Some(sender) = active.get(request_id) else {
            return false;
        };
        if sender.send(true).is_ok() {
            true
        } else {
            active.remove(request_id);
            false
        }
    }

    async fn remove(&self, request_id: &str) {
        self.active.lock().await.remove(request_id);
    }
}

struct ActiveRequestGuard {
    registry: RequestCancellationRegistry,
    request_id: String,
}

impl ActiveRequestGuard {
    fn new(registry: RequestCancellationRegistry, request_id: String) -> Self {
        Self {
            registry,
            request_id,
        }
    }
}

impl Drop for ActiveRequestGuard {
    fn drop(&mut self) {
        let registry = self.registry.clone();
        let request_id = self.request_id.clone();
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            runtime.spawn(async move { registry.remove(&request_id).await });
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CancellationResponse {
    request_id: String,
    status: &'static str,
    active: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RenderedCaptureUpload {
    session_id: String,
    generation: i64,
    translation_revision: i64,
    #[serde(default = "default_rendered_capture_outcome")]
    outcome: String,
    #[serde(default)]
    stage: Option<String>,
    #[serde(default)]
    failure_code: Option<String>,
    #[serde(default)]
    failure_message: Option<String>,
    #[serde(default)]
    layout_diagnostics: Option<serde_json::Value>,
    capture: DebugCapture,
}

fn default_rendered_capture_outcome() -> String {
    "PRESENTED".to_owned()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RenderedCaptureResponse {
    request_id: String,
    audit_id: String,
    status: &'static str,
}

struct SavedCapture {
    path: String,
    mime_type: String,
    pixel_width: i32,
    pixel_height: i32,
    byte_size: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthResponse {
    status: &'static str,
    schema_version: u32,
    model: String,
    model_provider: &'static str,
    model_configured: bool,
    model_reachable: bool,
    model_available: bool,
    model_execution_mode: &'static str,
}

pub async fn health(State(state): State<AppState>) -> Json<HealthResponse> {
    let active = state.models.active();
    let model_health = active.model.health().await;
    Json(HealthResponse {
        status: "ok",
        schema_version: REGIONS_FIRST_SCHEMA_VERSION,
        model: active.model_name,
        model_provider: active.provider.as_str(),
        model_configured: model_health.configured,
        model_reachable: model_health.reachable,
        model_available: model_health.model_available,
        model_execution_mode: model_health.execution_mode,
    })
}

pub async fn cancel_translation(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(request_id): Path<String>,
) -> Result<(StatusCode, Json<CancellationResponse>), RequestError> {
    authorize(&headers, state.config.bearer_token.as_deref())
        .map_err(|error| error.with_request_id(&request_id))?;
    let active = state.cancellations.cancel(&request_id).await;
    Ok((
        StatusCode::ACCEPTED,
        Json(CancellationResponse {
            request_id,
            status: "CANCEL_REQUESTED",
            active,
        }),
    ))
}

pub async fn upload_rendered_capture(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(request_id): Path<String>,
    Json(upload): Json<RenderedCaptureUpload>,
) -> Result<(StatusCode, Json<RenderedCaptureResponse>), RequestError> {
    authorize(&headers, state.config.bearer_token.as_deref())
        .map_err(|error| error.with_request_id(&request_id))?;
    if upload.session_id.trim().is_empty()
        || upload.generation < 0
        || upload.translation_revision < 0
    {
        return Err(AppError::invalid(
            "sessionId must be non-empty and generation/revision must be non-negative",
        )
        .with_request_id(request_id));
    }
    if !matches!(upload.outcome.as_str(), "PRESENTED" | "RENDER_FAILED") {
        return Err(
            AppError::invalid("outcome must be PRESENTED or RENDER_FAILED")
                .with_request_id(request_id),
        );
    }
    if upload.outcome == "RENDER_FAILED"
        && upload.stage.as_deref().map(str::is_empty).unwrap_or(true)
    {
        return Err(
            AppError::invalid("stage is required when outcome is RENDER_FAILED")
                .with_request_id(request_id),
        );
    }
    validate_capture(&upload.capture, "capture")
        .map_err(|error| error.with_request_id(&request_id))?;
    let record = state
        .database
        .matching_request_record(&request_id, &upload.session_id, upload.generation)
        .await
        .map_err(|error| error.with_request_id(&request_id))?
        .ok_or_else(|| {
            AppError::invalid("matching translation request was not found")
                .with_request_id(&request_id)
        })?;
    if record.audit.scene != "LIVE_SCREEN" {
        return Err(AppError::invalid(
            "rendered capture is only accepted for LIVE_SCREEN requests",
        )
        .with_request_id(request_id));
    }
    let stored_revision = record
        .payload
        .as_ref()
        .and_then(|payload| serde_json::from_str::<serde_json::Value>(&payload.request_json).ok())
        .and_then(|request| request.get("translationRevision")?.as_i64());
    if stored_revision != Some(upload.translation_revision) {
        return Err(
            AppError::invalid("translationRevision does not match the request")
                .with_request_id(request_id),
        );
    }
    let saved = save_capture(
        &state,
        &upload.capture,
        &format!("{}.rendered", record.audit.id),
        "capture",
    )
    .await
    .map_err(|error| error.with_request_id(&request_id))?;
    let layout_diagnostics_json = upload
        .layout_diagnostics
        .as_ref()
        .and_then(|value| serde_json::to_string(value).ok());
    let image = NewRenderedRequestImage {
        audit_id: &record.audit.id,
        image_path: &saved.path,
        mime_type: &saved.mime_type,
        pixel_width: saved.pixel_width,
        pixel_height: saved.pixel_height,
        byte_size: saved.byte_size,
        outcome: &upload.outcome,
        stage: upload.stage.as_deref(),
        failure_code: upload.failure_code.as_deref(),
        failure_message: upload.failure_message.as_deref(),
        layout_diagnostics_json: layout_diagnostics_json.as_deref(),
    };
    match state.database.replace_rendered_image(image).await {
        Ok(old_path) => {
            if let Some(old_path) = old_path.filter(|path| path != &saved.path) {
                remove_path(&old_path, "replaced rendered capture").await;
            }
        }
        Err(error) => {
            remove_capture(Some(&saved)).await;
            return Err(error.with_request_id(request_id));
        }
    }
    Ok((
        StatusCode::CREATED,
        Json(RenderedCaptureResponse {
            request_id,
            audit_id: record.audit.id,
            status: "SAVED",
        }),
    ))
}

pub async fn translate_groups(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<SemanticTranslationRequest>,
) -> Result<Json<SemanticTranslationResponse>, RequestError> {
    translate_request(state, headers, request, TranslationApiVersion::V2).await
}

pub async fn translate_layout_plan(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<SemanticTranslationRequest>,
) -> Result<Json<SemanticTranslationResponse>, RequestError> {
    translate_request(state, headers, request, TranslationApiVersion::V3).await
}

pub async fn translate_regions_first_layout_plan(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<SemanticTranslationRequest>,
) -> Result<Json<SemanticTranslationResponse>, RequestError> {
    translate_request(state, headers, request, TranslationApiVersion::V4).await
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum TranslationApiVersion {
    V2,
    V3,
    V4,
}

async fn translate_request(
    state: AppState,
    headers: HeaderMap,
    request: SemanticTranslationRequest,
    api_version: TranslationApiVersion,
) -> Result<Json<SemanticTranslationResponse>, RequestError> {
    let request_id = request.request_id.clone();
    authorize(&headers, state.config.bearer_token.as_deref())
        .map_err(|error| error.with_request_id(&request_id))?;
    let expected_schema = match api_version {
        TranslationApiVersion::V2 => SCHEMA_VERSION,
        TranslationApiVersion::V3 => LAYOUT_PLAN_SCHEMA_VERSION,
        TranslationApiVersion::V4 => REGIONS_FIRST_SCHEMA_VERSION,
    };
    request
        .validate_schema(expected_schema)
        .map_err(|error| error.with_request_id(&request_id))?;
    let normalized_request = normalized_request_for_translation(&request);
    let document_plan = match api_version {
        TranslationApiVersion::V4 => build_regions_first_plan(&normalized_request),
        TranslationApiVersion::V2 | TranslationApiVersion::V3 => DocumentPlan::build(
            &normalized_request,
            api_version == TranslationApiVersion::V3,
        ),
    };
    let execution_groups = if api_version != TranslationApiVersion::V2 {
        document_plan.translation_groups()
    } else {
        request.groups.clone()
    };
    let started = Instant::now();
    let audit_id = Uuid::new_v4().to_string();
    let saved_capture = save_debug_capture(&state, &request, &audit_id)
        .await
        .map_err(|error| error.with_request_id(&request_id))?;
    let mut cancellation = match state.cancellations.register(&request_id).await {
        Ok(receiver) => receiver,
        Err(error) => {
            remove_capture(saved_capture.as_ref()).await;
            return Err(error.with_request_id(request_id));
        }
    };
    let _active_request = ActiveRequestGuard::new(state.cancellations.clone(), request_id.clone());
    let actionable = execution_groups
        .iter()
        .filter(|group| !should_preserve(group))
        .cloned()
        .collect::<Vec<_>>();
    let requested_provider = optional_header(&headers, "x-translation-provider")
        .map_err(|error| error.with_request_id(&request_id))?;
    let requested_model = optional_header(&headers, "x-translation-model")
        .map_err(|error| error.with_request_id(&request_id))?;
    let active_model = state
        .models
        .resolve(requested_provider, requested_model)
        .map_err(|error| error.with_request_id(&request_id))?;
    let audit_model = format!(
        "{}:{}",
        active_model.provider.as_str(),
        active_model.model_name
    );
    let model_request_json = active_model.model.request_json(&request, &actionable);
    let model_outcome = tokio::select! {
        result = active_model.model.translate(&request, &actionable) => result,
        _ = cancellation.changed() => Err(AppError::Cancelled(
            "translation request cancelled by client".to_owned(),
        )),
    };
    state.cancellations.remove(&request_id).await;
    let model_results = match model_outcome {
        Ok(results) => results,
        Err(error) => {
            let elapsed = started.elapsed().as_millis() as i64;
            let status = if matches!(error, AppError::Cancelled(_)) {
                "CANCELLED"
            } else {
                "FAILED"
            };
            warn!(
                request_id = %request_id,
                group_count = execution_groups.len(),
                elapsed_ms = elapsed,
                error = %error,
                "semantic translation failed"
            );
            record_audit(
                &state,
                &audit_id,
                &request,
                status,
                elapsed,
                model_request_json.as_deref(),
                None,
                Some(&error.to_string()),
                saved_capture.as_ref(),
                &audit_model,
            )
            .await;
            return Err(error.with_request_id(request_id));
        }
    };
    let model_results = model_results
        .into_iter()
        .map(|result| (result.group_id.clone(), result))
        .collect::<HashMap<_, _>>();
    let mut translated = 0usize;
    let mut preserved = 0usize;
    let mut results = Vec::with_capacity(execution_groups.len());
    let planned_by_id = document_plan
        .groups
        .iter()
        .map(|group| (group.group_id.as_str(), group))
        .collect::<HashMap<_, _>>();
    let regions_by_id = request
        .regions
        .iter()
        .map(|region| (region.region_id.as_str(), region))
        .collect::<HashMap<_, _>>();
    for group in &execution_groups {
        let members = group
            .member_region_ids
            .iter()
            .filter_map(|id| regions_by_id.get(id.as_str()).copied())
            .collect::<Vec<_>>();
        let source_group_ids = planned_by_id
            .get(group.group_id.as_str())
            .map(|planned| planned.source_group_ids.clone())
            .unwrap_or_else(|| vec![group.group_id.clone()]);
        let source_language = members
            .first()
            .and_then(|region| region.source_language.as_deref())
            .map(normalized_language)
            .unwrap_or_else(|| "auto".to_owned());
        let target_language = members
            .first()
            .and_then(|region| region.target_language.as_deref())
            .map(normalized_language)
            .unwrap_or_else(|| normalized_language(&request.translation.target_language));
        if should_preserve(group) {
            let render_slots = resolved_render_slots(group, &members);
            preserved += 1;
            results.push(GroupTranslationResult {
                group_id: group.group_id.clone(),
                source_group_ids,
                role: group.role.clone(),
                grouping_confidence: group.grouping_confidence,
                status: "PRESERVED".to_owned(),
                render_mode: "NONE".to_owned(),
                translated_text: None,
                member_region_ids: group.member_region_ids.clone(),
                detected_source_language: source_language,
                target_language,
                anchor_bounds: group.bounds.clone(),
                layout_hint: layout_hint(
                    group,
                    &group.source_text,
                    render_slots,
                    source_cover_slots(group, &members),
                ),
                error: None,
            });
            continue;
        }
        let model_result = model_results.get(&group.group_id).ok_or_else(|| {
            AppError::Upstream("validated model result was lost during mapping".to_owned())
                .with_request_id(&request_id)
        })?;
        translated += 1;
        results.push(translated_result(
            group,
            model_result,
            &members,
            source_group_ids,
        ));
    }

    let total_ms = started.elapsed().as_millis() as u64;
    let response = SemanticTranslationResponse {
        schema_version: expected_schema,
        request_id: request_id.clone(),
        session_id: request.session_id.clone(),
        generation: request.generation,
        translation_revision: request.translation_revision,
        provider: format!(
            "self-hosted-{}-{}",
            active_model.provider.as_str(),
            match api_version {
                TranslationApiVersion::V2 => "v2",
                TranslationApiVersion::V3 => "layout-plan-v3",
                TranslationApiVersion::V4 => "regions-first-v4",
            }
        ),
        model_version: active_model.model_name.clone(),
        prompt_version: PROMPT_VERSION.to_owned(),
        results,
        document_plan,
        metrics: ResponseMetrics {
            group_count: execution_groups.len(),
            translated_group_count: translated,
            preserved_group_count: preserved,
            failed_group_count: 0,
            total_ms,
        },
    };
    record_audit(
        &state,
        &audit_id,
        &request,
        "SUCCEEDED",
        total_ms as i64,
        model_request_json.as_deref(),
        Some(&response),
        None,
        saved_capture.as_ref(),
        &audit_model,
    )
    .await;
    info!(
        request_id = %request_id,
        group_count = execution_groups.len(),
        region_count = request.regions.len(),
        translated_group_count = translated,
        preserved_group_count = preserved,
        total_ms,
        "semantic translation completed"
    );
    Ok(Json(response))
}

fn translated_result(
    group: &TranslationGroup,
    model_result: &ModelTranslation,
    regions: &[&crate::contract::OcrRegion],
    source_group_ids: Vec<String>,
) -> GroupTranslationResult {
    let render_slots = resolved_render_slots(group, regions);
    GroupTranslationResult {
        group_id: group.group_id.clone(),
        source_group_ids,
        role: group.role.clone(),
        grouping_confidence: group.grouping_confidence,
        status: "TRANSLATED".to_owned(),
        render_mode: "GROUP".to_owned(),
        translated_text: Some(model_result.translated_text.clone()),
        member_region_ids: group.member_region_ids.clone(),
        detected_source_language: model_result.detected_source_language.clone(),
        target_language: model_result.target_language.clone(),
        anchor_bounds: group.bounds.clone(),
        layout_hint: layout_hint(
            group,
            &model_result.translated_text,
            render_slots,
            source_cover_slots(group, regions),
        ),
        error: None,
    }
}

fn should_preserve(group: &TranslationGroup) -> bool {
    let text = group.source_text.trim();
    if text.is_empty() || looks_like_code(text) {
        return true;
    }
    match group.role.as_str() {
        "CODE" | "IDENTIFIER" | "CONTROL" => true,
        "TIMESTAMP" => is_standalone_temporal_value(text),
        "METADATA" => is_standalone_metadata(text),
        _ => is_standalone_numeric_identifier(text),
    }
}

fn normalized_request_for_translation(
    request: &SemanticTranslationRequest,
) -> SemanticTranslationRequest {
    let mut normalized = request.clone();
    for group in &mut normalized.groups {
        let preserve = should_preserve(group);
        group.translation_unit = if preserve { "PRESERVED" } else { "GROUP" }.to_owned();
        if !preserve && matches!(group.role.as_str(), "TIMESTAMP" | "METADATA") {
            group.role = "BODY".to_owned();
            group
                .grouping_evidence
                .push("SERVER_TRANSLATION_ELIGIBILITY_OVERRIDE".to_owned());
            group.grouping_evidence.sort();
            group.grouping_evidence.dedup();
        }
    }
    normalized
}

fn is_standalone_temporal_value(text: &str) -> bool {
    let mut has_digit = false;
    let mut alphabetic_runs = Vec::new();
    let mut current = String::new();
    for character in text.chars() {
        if character.is_ascii_digit() {
            has_digit = true;
        }
        if character.is_alphabetic() {
            current.push(character.to_ascii_lowercase());
        } else if !current.is_empty() {
            alphabetic_runs.push(std::mem::take(&mut current));
        }
        if !character.is_alphanumeric()
            && !character.is_whitespace()
            && !matches!(character, ':' | '-' | '/' | '.' | ',')
        {
            return false;
        }
    }
    if !current.is_empty() {
        alphabetic_runs.push(current);
    }
    has_digit
        && alphabetic_runs.iter().all(|token| {
            matches!(
                token.as_str(),
                "am" | "pm"
                    | "t"
                    | "jan"
                    | "january"
                    | "feb"
                    | "february"
                    | "mar"
                    | "march"
                    | "apr"
                    | "april"
                    | "may"
                    | "jun"
                    | "june"
                    | "jul"
                    | "july"
                    | "aug"
                    | "august"
                    | "sep"
                    | "september"
                    | "oct"
                    | "october"
                    | "nov"
                    | "november"
                    | "dec"
                    | "december"
                    | "st"
                    | "nd"
                    | "rd"
                    | "th"
            )
        })
}

fn is_standalone_metadata(text: &str) -> bool {
    let trimmed = text.trim();
    if is_standalone_temporal_value(trimmed) {
        return true;
    }
    if matches!(trimmed.chars().next(), Some('~' | '-'))
        && trimmed.chars().any(char::is_alphabetic)
        && !trimmed.chars().any(char::is_numeric)
    {
        return true;
    }
    looks_like_author_date_metadata(trimmed)
}

fn looks_like_author_date_metadata(text: &str) -> bool {
    let tokens = text.split_whitespace().collect::<Vec<_>>();
    if tokens.len() < 3 {
        return false;
    }
    let Some(year_index) = tokens.iter().rposition(|token| {
        token
            .trim_matches(|character: char| !character.is_ascii_digit())
            .parse::<u16>()
            .is_ok_and(|year| (1900..=2200).contains(&year))
    }) else {
        return false;
    };
    if year_index != tokens.len() - 1 {
        return false;
    }
    let Some(month_index) = tokens[..year_index]
        .iter()
        .rposition(|token| is_month_name(token))
    else {
        return false;
    };
    if month_index == 0 || year_index - month_index > 2 {
        return false;
    }
    let author_end = if month_index > 0 && is_day_number(tokens[month_index - 1]) {
        month_index - 1
    } else {
        month_index
    };
    let author_tokens = &tokens[..author_end];
    if author_tokens.is_empty() || author_tokens.len() > 8 {
        return false;
    }
    author_tokens.iter().all(|token| {
        let letters = token
            .chars()
            .filter(|character| character.is_alphabetic())
            .collect::<String>();
        !letters.is_empty()
            && (is_lowercase_name_particle(&letters)
                || letters.chars().next().is_some_and(char::is_uppercase))
    })
}

fn is_day_number(token: &str) -> bool {
    token
        .trim_matches(|character: char| !character.is_ascii_digit())
        .parse::<u8>()
        .is_ok_and(|day| (1..=31).contains(&day))
}

fn is_month_name(token: &&str) -> bool {
    matches!(
        token
            .trim_matches(|character: char| !character.is_alphabetic())
            .to_ascii_lowercase()
            .as_str(),
        "jan"
            | "january"
            | "feb"
            | "february"
            | "mar"
            | "march"
            | "apr"
            | "april"
            | "may"
            | "jun"
            | "june"
            | "jul"
            | "july"
            | "aug"
            | "august"
            | "sep"
            | "september"
            | "oct"
            | "october"
            | "nov"
            | "november"
            | "dec"
            | "december"
    )
}

fn is_lowercase_name_particle(token: &str) -> bool {
    matches!(
        token.to_ascii_lowercase().as_str(),
        "and" | "bin" | "da" | "de" | "del" | "la" | "van" | "von"
    )
}

fn is_standalone_numeric_identifier(text: &str) -> bool {
    let mut has_digit = false;
    let mut current_latin_run = 0usize;
    for character in text.chars() {
        if character.is_ascii_digit() {
            has_digit = true;
        }
        if character.is_ascii_alphabetic() {
            current_latin_run += 1;
            if current_latin_run > 1 {
                return false;
            }
        } else {
            current_latin_run = 0;
        }
        if matches!(character as u32, 0x2E80..=0x9FFF | 0xF900..=0xFAFF) {
            return false;
        }
    }
    has_digit
}

fn looks_like_code(text: &str) -> bool {
    text.contains("//") || text.contains('_') || text.contains('@') || text.contains("://")
}

fn authorize(headers: &HeaderMap, expected: Option<&str>) -> Result<(), AppError> {
    let Some(expected) = expected else {
        return Ok(());
    };
    let supplied = headers
        .get(AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    if supplied == Some(expected) {
        Ok(())
    } else {
        Err(AppError::Unauthorized(
            "valid bearer token required".to_owned(),
        ))
    }
}

fn optional_header<'a>(headers: &'a HeaderMap, name: &str) -> Result<Option<&'a str>, AppError> {
    headers
        .get(name)
        .map(|value| {
            value
                .to_str()
                .map(str::trim)
                .map_err(|_| AppError::invalid(format!("{name} must be valid UTF-8")))
        })
        .transpose()
        .map(|value| value.filter(|item| !item.is_empty()))
}

async fn record_audit(
    state: &AppState,
    id: &str,
    request: &SemanticTranslationRequest,
    status: &str,
    duration_ms: i64,
    model_request_json: Option<&str>,
    response: Option<&SemanticTranslationResponse>,
    error_message: Option<&str>,
    image: Option<&SavedCapture>,
    model_name: &str,
) {
    let created_at = Utc::now().to_rfc3339();
    let input_chars = request
        .regions
        .iter()
        .map(|region| region.text.chars().count())
        .sum::<usize>() as i32;
    let audit = NewRequestAudit {
        id,
        request_id: &request.request_id,
        session_id: &request.session_id,
        generation: request.generation,
        scene: &request.scene,
        group_count: request.groups.len() as i32,
        region_count: request.regions.len() as i32,
        input_chars,
        status,
        model: model_name,
        duration_ms,
        created_at: &created_at,
    };
    let request_json = match serde_json::to_string(request) {
        Ok(value) => value,
        Err(error) => {
            warn!(request_id = %request.request_id, error = %error, "failed to serialize request audit");
            remove_capture(image).await;
            return;
        }
    };
    let response_json = match response.map(serde_json::to_string).transpose() {
        Ok(value) => value,
        Err(error) => {
            warn!(request_id = %request.request_id, error = %error, "failed to serialize response audit");
            remove_capture(image).await;
            return;
        }
    };
    let payload = NewRequestPayload {
        audit_id: id,
        request_json: &request_json,
        response_json: response_json.as_deref(),
        error_message,
        model_request_json,
    };
    let image_record = image.map(|image| NewRequestImage {
        audit_id: id,
        image_path: &image.path,
        mime_type: &image.mime_type,
        pixel_width: image.pixel_width,
        pixel_height: image.pixel_height,
        byte_size: image.byte_size,
    });
    match state
        .database
        .insert_record(
            audit,
            payload,
            image_record,
            state.config.request_history_limit,
        )
        .await
    {
        Ok(stale_paths) => {
            for path in stale_paths {
                if let Err(error) = tokio::fs::remove_file(&path).await
                    && error.kind() != std::io::ErrorKind::NotFound
                {
                    warn!(path = %path, error = %error, "failed to prune request image");
                }
            }
        }
        Err(error) => {
            remove_capture(image).await;
            warn!(request_id = %request.request_id, error = %error, "failed to record audit metadata");
        }
    }
}

async fn save_debug_capture(
    state: &AppState,
    request: &SemanticTranslationRequest,
    audit_id: &str,
) -> Result<Option<SavedCapture>, AppError> {
    let Some(capture) = &request.debug_capture else {
        return Ok(None);
    };
    save_capture(state, capture, audit_id, "debugCapture")
        .await
        .map(Some)
}

fn validate_capture(capture: &DebugCapture, field: &str) -> Result<(), AppError> {
    if !matches!(capture.mime_type.as_str(), "image/jpeg" | "image/png") {
        return Err(AppError::invalid(format!(
            "{field}.mimeType must be image/jpeg or image/png"
        )));
    }
    if capture.data_base64.is_empty()
        || capture.data_base64.len() > MAXIMUM_DEBUG_CAPTURE_BASE64_CHARS
    {
        return Err(AppError::invalid(format!(
            "{field}.dataBase64 must contain at most 8 MiB"
        )));
    }
    if capture.pixel_width <= 0
        || capture.pixel_height <= 0
        || capture.pixel_width > 32_768
        || capture.pixel_height > 32_768
    {
        return Err(AppError::invalid(format!(
            "{field} dimensions must be between 1 and 32768"
        )));
    }
    Ok(())
}

async fn save_capture(
    state: &AppState,
    capture: &DebugCapture,
    file_stem: &str,
    field: &str,
) -> Result<SavedCapture, AppError> {
    validate_capture(capture, field)?;
    let bytes = STANDARD
        .decode(&capture.data_base64)
        .map_err(|_| AppError::invalid(format!("{field}.dataBase64 is invalid")))?;
    if bytes.is_empty() || bytes.len() > MAXIMUM_DEBUG_CAPTURE_BYTES {
        return Err(AppError::invalid(format!(
            "decoded {field} must contain at most 4 MiB"
        )));
    }
    let extension = match capture.mime_type.as_str() {
        "image/jpeg" if bytes.starts_with(&[0xff, 0xd8, 0xff]) => "jpg",
        "image/png" if bytes.starts_with(b"\x89PNG\r\n\x1a\n") => "png",
        _ => {
            return Err(AppError::invalid(format!(
                "{field} bytes do not match the declared image type"
            )));
        }
    };
    tokio::fs::create_dir_all(&state.config.request_image_dir)
        .await
        .map_err(AppError::database)?;
    let path = state
        .config
        .request_image_dir
        .join(format!("{file_stem}.{extension}"));
    tokio::fs::write(&path, &bytes)
        .await
        .map_err(AppError::database)?;
    Ok(SavedCapture {
        path: path.to_string_lossy().into_owned(),
        mime_type: capture.mime_type.clone(),
        pixel_width: capture.pixel_width,
        pixel_height: capture.pixel_height,
        byte_size: bytes.len() as i64,
    })
}

async fn remove_capture(capture: Option<&SavedCapture>) {
    let Some(capture) = capture else {
        return;
    };
    if let Err(error) = tokio::fs::remove_file(&capture.path).await
        && error.kind() != std::io::ErrorKind::NotFound
    {
        warn!(path = %capture.path, error = %error, "failed to remove request image");
    }
}

async fn remove_path(path: &str, description: &str) {
    if let Err(error) = tokio::fs::remove_file(path).await
        && error.kind() != std::io::ErrorKind::NotFound
    {
        warn!(path, error = %error, description, "failed to remove image");
    }
}

const MAXIMUM_DEBUG_CAPTURE_BYTES: usize = 4 * 1024 * 1024;
const MAXIMUM_DEBUG_CAPTURE_BASE64_CHARS: usize = 8 * 1024 * 1024;

fn normalized_language(value: &str) -> String {
    let normalized = value.trim().to_lowercase();
    if normalized.is_empty() {
        "auto".to_owned()
    } else {
        normalized
    }
}

#[cfg(test)]
mod tests {
    use super::should_preserve;
    use crate::contract::{Bounds, TranslationGroup};

    #[test]
    fn translates_legacy_preserved_groups_when_time_or_numbers_are_part_of_a_sentence() {
        assert!(!should_preserve(&group(
            "METADATA",
            "The March ended in 1956 but the consequences remained"
        )));
        assert!(!should_preserve(&group(
            "TIMESTAMP",
            "At 17:27 the meeting started"
        )));
        assert!(!should_preserve(&group("BODY", "Version 3 is ready")));
        assert!(!should_preserve(&group("BODY", "Awarded C$20 million")));
    }

    #[test]
    fn preserves_only_structural_time_metadata_and_numeric_identifiers() {
        assert!(should_preserve(&group("TIMESTAMP", "22:43")));
        assert!(should_preserve(&group("TIMESTAMP", "2026-08-04")));
        assert!(should_preserve(&group(
            "METADATA",
            "Ngotho Gichuru and Byaruhanga Rukooko 06 August 2026"
        )));
        assert!(should_preserve(&group("BODY", "M26-061")));
        assert!(should_preserve(&group("BODY", "W3000 t5")));
    }

    #[test]
    fn normalizes_legacy_mixed_time_roles_before_building_the_document_plan() {
        let mut request = crate::contract::SemanticTranslationRequest {
            schema_version: crate::contract::LAYOUT_PLAN_SCHEMA_VERSION,
            request_id: "request".to_owned(),
            session_id: "session".to_owned(),
            generation: 1,
            translation_revision: 1,
            scene: "LIVE_SCREEN".to_owned(),
            viewport: crate::contract::Viewport {
                width: 100,
                height: 100,
                rotation_degrees: 0,
            },
            translation: crate::contract::TranslationOptions {
                mode: "AUTO_BIDIRECTIONAL".to_owned(),
                source_language: "auto".to_owned(),
                target_language: "zh".to_owned(),
                preserve_identifiers: true,
                use_document_context: true,
            },
            document_context: crate::contract::DocumentContext {
                text: String::new(),
                source_language: "auto".to_owned(),
                reading_order_region_ids: Vec::new(),
            },
            groups: vec![group(
                "METADATA",
                "The March ended in 1956 but the consequences remained",
            )],
            regions: Vec::new(),
            debug_capture: None,
        };
        request.groups[0].translation_unit = "PRESERVED".to_owned();

        let normalized = super::normalized_request_for_translation(&request);

        assert_eq!(normalized.groups[0].role, "BODY");
        assert_eq!(normalized.groups[0].translation_unit, "GROUP");
        assert!(
            normalized.groups[0]
                .grouping_evidence
                .contains(&"SERVER_TRANSLATION_ELIGIBILITY_OVERRIDE".to_owned())
        );
    }

    fn group(role: &str, source_text: &str) -> TranslationGroup {
        TranslationGroup {
            group_id: "test-group".to_owned(),
            role: role.to_owned(),
            translation_unit: "PRESERVED".to_owned(),
            source_text: source_text.to_owned(),
            member_region_ids: vec!["test-region".to_owned()],
            reading_order: 0,
            grouping_confidence: 1.0,
            grouping_evidence: Vec::new(),
            source_line_count: Some(1),
            bounds: Bounds {
                left: 0,
                top: 0,
                right: 100,
                bottom: 20,
            },
            render_slots: Vec::new(),
            layout_shape: "RECT".to_owned(),
        }
    }
}
