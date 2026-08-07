use std::{collections::HashSet, sync::Arc, time::Instant};

use axum::{
    Json, Router,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{get, post},
};
use futures::{StreamExt, stream};
use serde::Serialize;
use sha2::{Digest, Sha256};
use tower_http::{limit::RequestBodyLimitLayer, trace::TraceLayer};

use crate::{
    config::Config,
    contract::{
        self, BatchStatus, Bounds, OcrTranslationRegion, OcrTranslationRequest,
        OcrTranslationResponse, OcrTranslationTiming, RegionError, RegionRole, RegionStatus,
        Timing, V1BatchRequest, V1BatchResponse, V1RegionResult, V2BatchRequest, V2BatchResponse,
        V2RegionResult, normalized_language_tag,
    },
    db::{self, DbPool},
    error::ApiError,
    model::{ModelError, TranslationInput, TranslationModel},
    ocr::{OcrInput, OcrProvider},
};

#[derive(Clone)]
pub struct AppState {
    pub config: Config,
    pub db: DbPool,
    pub model: Arc<dyn TranslationModel>,
    pub ocr: Arc<dyn OcrProvider>,
}

pub fn router(state: AppState) -> Router {
    let translation_routes = Router::new()
        .route("/healthz", get(health))
        .route("/api/v1/translation-batches", post(translate_v1))
        .route("/api/v2/translation-batches", post(translate_v2))
        .route("/api/v2/translation/capabilities", get(capabilities))
        .layer(RequestBodyLimitLayer::new(contract::MAX_REQUEST_BYTES));
    let ocr_routes = Router::new()
        .route("/api/v1/ocr-translations", post(ocr_translate_v1))
        .layer(RequestBodyLimitLayer::new(contract::MAX_OCR_REQUEST_BYTES));
    Router::new()
        .merge(translation_routes)
        .merge(ocr_routes)
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

async fn health(State(state): State<AppState>) -> impl IntoResponse {
    let database = db::health(&state.db).await.is_ok();
    let (model, ocr) = tokio::join!(state.model.health(), state.ocr.health());
    let model = model.is_ok();
    let ocr = ocr.is_ok();
    let status = if database && model {
        StatusCode::OK
    } else {
        StatusCode::SERVICE_UNAVAILABLE
    };
    (
        status,
        Json(serde_json::json!({
            "status": if status == StatusCode::OK { "ok" } else { "degraded" },
            "database": database,
            "model": model,
            "ocr": ocr,
        })),
    )
}

async fn ocr_translate_v1(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<OcrTranslationRequest>,
) -> Result<Json<OcrTranslationResponse>, ApiError> {
    authorize(&state.config, &headers, 1, Some(request.request_id.clone()))?;
    validate_headers(&headers, &request.request_id, 1)?;
    validate_ocr_translation(&request)?;

    let started = Instant::now();
    let ocr_output = state
        .ocr
        .recognize(OcrInput {
            image_base64: request.image.data.clone(),
            options: request.ocr.clone(),
        })
        .await
        .map_err(|error| {
            ApiError::service_unavailable(
                1,
                Some(request.request_id.clone()),
                error.code(),
                error.to_string(),
            )
        })?;
    if ocr_output.width != request.image.width || ocr_output.height != request.image.height {
        return Err(ApiError::service_unavailable(
            1,
            Some(request.request_id.clone()),
            "OCR_DIMENSION_MISMATCH",
            "PaddleOCR returned dimensions that do not match the captured image",
        ));
    }

    let recognized_count = ocr_output.regions.len();
    let translation_started = Instant::now();
    let jobs = ocr_output
        .regions
        .into_iter()
        .take(contract::MAX_REGIONS_PER_BATCH)
        .enumerate()
        .map(|(index, region)| {
            let state = state.clone();
            let mode = request.translation.mode.clone();
            let preserve_identifiers = request.translation.preserve_identifiers;
            async move {
                let detected_source_language =
                    detected_source_language(&region.text).map(str::to_owned);
                let target_language =
                    translation_target(&mode, detected_source_language.as_deref())
                        .map(str::to_owned);
                let execution = if target_language.is_some() {
                    translate_region(
                        state.model.as_ref(),
                        &region.text,
                        detected_source_language.clone(),
                        target_language.clone(),
                        preserve_identifiers,
                    )
                    .await
                } else {
                    RegionExecution::Preserved
                };
                let (status, translated_text, error) = match execution {
                    RegionExecution::Translated(text) => {
                        (RegionStatus::Translated, Some(text), None)
                    }
                    RegionExecution::Preserved => {
                        (RegionStatus::Preserved, Some(region.text.clone()), None)
                    }
                    RegionExecution::Failed(error) => {
                        (RegionStatus::Failed, None, Some(model_region_error(error)))
                    }
                };
                let response = OcrTranslationRegion {
                    region_id: format!("ocr-{index}"),
                    status,
                    source_text: region.text,
                    translated_text,
                    bounds: Bounds {
                        left: region.bounds[0],
                        top: region.bounds[1],
                        right: region.bounds[2],
                        bottom: region.bounds[3],
                    },
                    confidence: region.confidence,
                    provider: format!("paddleocr+{}", state.config.provider_id),
                    detected_source_language,
                    target_language,
                    error,
                };
                (index, response)
            }
        });
    let mut indexed_regions: Vec<_> = stream::iter(jobs)
        .buffer_unordered(state.config.model_max_concurrency)
        .collect()
        .await;
    indexed_regions.sort_by_key(|(index, _)| *index);
    let regions: Vec<_> = indexed_regions
        .into_iter()
        .map(|(_, region)| region)
        .collect();
    let failed_count = regions
        .iter()
        .filter(|region| matches!(region.status, RegionStatus::Failed))
        .count();
    let status = if failed_count == 0 {
        BatchStatus::Completed
    } else if failed_count == regions.len() {
        BatchStatus::Failed
    } else {
        BatchStatus::Partial
    };
    let translation_ms = translation_started.elapsed().as_millis() as u64;
    let response = OcrTranslationResponse {
        schema_version: 1,
        request_id: request.request_id.clone(),
        session_id: request.session_id,
        generation: request.generation,
        source_width: ocr_output.width,
        source_height: ocr_output.height,
        status,
        recognized_count,
        regions,
        timing: OcrTranslationTiming {
            ocr_ms: ocr_output.latency_ms,
            translation_ms,
            total_ms: started.elapsed().as_millis() as u64,
        },
    };
    tracing::info!(
        request_id = %request.request_id,
        session_id = %response.session_id,
        generation = response.generation,
        recognized_count,
        returned_region_count = response.regions.len(),
        failed_count,
        ocr_ms = response.timing.ocr_ms,
        translation_ms,
        total_ms = response.timing.total_ms,
        "OCR translation completed"
    );
    Ok(Json(response))
}

async fn capabilities(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<contract::CapabilitiesResponse>, ApiError> {
    authorize(&state.config, &headers, 2, None)?;
    Ok(Json(contract::capabilities(state.config.model_id)))
}

async fn translate_v1(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<V1BatchRequest>,
) -> Result<Json<V1BatchResponse>, ApiError> {
    authorize(&state.config, &headers, 1, Some(request.request_id.clone()))?;
    validate_headers(&headers, &request.request_id, 1)?;
    validate_v1(&request)?;
    let request_hash = request_hash(&request)?;
    if let Some(response) = replay::<V1BatchResponse>(
        &state,
        &request.request_id,
        &request_hash,
        1,
        request.regions.len(),
    )
    .await?
    {
        return Ok(Json(response));
    }

    let started = Instant::now();
    let provider = state.config.provider_id.clone();
    let jobs = request
        .regions
        .iter()
        .cloned()
        .enumerate()
        .map(|(index, region)| {
            let state = state.clone();
            let options = request.translation.clone();
            async move {
                let source = region
                    .source_language
                    .as_deref()
                    .or(options.source_language.as_deref())
                    .filter(|value| *value != "auto")
                    .and_then(normalized_language_tag)
                    .map(str::to_owned);
                let target = region
                    .target_language
                    .as_deref()
                    .or(options.target_language.as_deref())
                    .filter(|value| *value != "auto")
                    .and_then(normalized_language_tag)
                    .map(str::to_owned);
                let result = translate_region(
                    state.model.as_ref(),
                    &region.text,
                    source.clone(),
                    target.clone(),
                    options.preserve_identifiers,
                )
                .await;
                let response = match result {
                    RegionExecution::Translated(text) => V1RegionResult {
                        region_id: region.region_id,
                        status: RegionStatus::Translated,
                        translated_text: Some(text),
                        provider: state.config.provider_id,
                        detected_source_language: source,
                        target_language: target,
                        error: None,
                    },
                    RegionExecution::Preserved => V1RegionResult {
                        region_id: region.region_id,
                        status: RegionStatus::Preserved,
                        translated_text: None,
                        provider: state.config.provider_id,
                        detected_source_language: source,
                        target_language: target,
                        error: None,
                    },
                    RegionExecution::Failed(error) => V1RegionResult {
                        region_id: region.region_id,
                        status: RegionStatus::Failed,
                        translated_text: None,
                        provider: state.config.provider_id,
                        detected_source_language: source,
                        target_language: target,
                        error: Some(model_region_error(error)),
                    },
                };
                (index, response)
            }
        });
    let mut indexed: Vec<_> = stream::iter(jobs)
        .buffer_unordered(state.config.model_max_concurrency)
        .collect()
        .await;
    indexed.sort_by_key(|(index, _)| *index);
    let response = V1BatchResponse {
        request_id: request.request_id.clone(),
        results: indexed.into_iter().map(|(_, result)| result).collect(),
    };
    persist_response(&state, &request.request_id, &response).await?;
    tracing::info!(
        request_id = %request.request_id,
        api_version = 1,
        region_count = request.regions.len(),
        elapsed_ms = started.elapsed().as_millis() as u64,
        provider = %provider,
        "translation batch completed"
    );
    Ok(Json(response))
}

async fn translate_v2(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<V2BatchRequest>,
) -> Result<Json<V2BatchResponse>, ApiError> {
    authorize(&state.config, &headers, 2, Some(request.request_id.clone()))?;
    validate_headers(&headers, &request.request_id, 2)?;
    validate_v2(&request)?;
    let request_hash = request_hash(&request)?;
    if let Some(response) = replay::<V2BatchResponse>(
        &state,
        &request.request_id,
        &request_hash,
        2,
        request.regions.len(),
    )
    .await?
    {
        return Ok(Json(response));
    }

    let started = Instant::now();
    let target = normalized_language_tag(&request.translation.target.language_tag)
        .expect("target was validated")
        .to_owned();
    let translatable_regions: Vec<_> = request
        .regions
        .iter()
        .filter(|region| matches!(region.role, RegionRole::Translate))
        .cloned()
        .collect();
    let jobs = translatable_regions
        .into_iter()
        .enumerate()
        .map(|(index, region)| {
            let state = state.clone();
            let request = request.clone();
            let target = target.clone();
            async move {
                let source = effective_source_language(&request, &region);
                let result = translate_region(
                    state.model.as_ref(),
                    &region.text,
                    source.clone(),
                    Some(target.clone()),
                    request.translation.preserve_identifiers,
                )
                .await;
                let response = match result {
                    RegionExecution::Translated(text) => V2RegionResult {
                        region_id: region.region_id,
                        source_revision: region.source_revision,
                        status: RegionStatus::Translated,
                        effective_source_language: source.clone(),
                        detected_source_language: source,
                        target_language: request.translation.target.language_tag.clone(),
                        translated_text: Some(text),
                        provider: state.config.provider_id,
                        model_version: state.config.model_id,
                        cached: false,
                        error: None,
                    },
                    RegionExecution::Preserved => V2RegionResult {
                        region_id: region.region_id,
                        source_revision: region.source_revision,
                        status: RegionStatus::Preserved,
                        effective_source_language: source.clone(),
                        detected_source_language: source,
                        target_language: request.translation.target.language_tag.clone(),
                        translated_text: Some(region.text),
                        provider: state.config.provider_id,
                        model_version: state.config.model_id,
                        cached: false,
                        error: None,
                    },
                    RegionExecution::Failed(error) => V2RegionResult {
                        region_id: region.region_id,
                        source_revision: region.source_revision,
                        status: RegionStatus::Failed,
                        effective_source_language: source.clone(),
                        detected_source_language: source,
                        target_language: request.translation.target.language_tag.clone(),
                        translated_text: None,
                        provider: state.config.provider_id,
                        model_version: state.config.model_id,
                        cached: false,
                        error: Some(model_region_error(error)),
                    },
                };
                (index, response)
            }
        });
    let mut indexed: Vec<_> = stream::iter(jobs)
        .buffer_unordered(state.config.model_max_concurrency)
        .collect()
        .await;
    indexed.sort_by_key(|(index, _)| *index);
    let results: Vec<_> = indexed.into_iter().map(|(_, result)| result).collect();
    let failed = results
        .iter()
        .filter(|result| matches!(result.status, RegionStatus::Failed))
        .count();
    let status = if failed == 0 {
        BatchStatus::Completed
    } else if failed == results.len() {
        BatchStatus::Failed
    } else {
        BatchStatus::Partial
    };
    let elapsed_ms = started.elapsed().as_millis() as u64;
    let response = V2BatchResponse {
        schema_version: 2,
        request_id: request.request_id.clone(),
        session_id: request.session_id.clone(),
        generation: request.generation,
        translation_revision: request.translation_revision,
        batch_part_index: request.batch_part_index,
        status,
        results,
        timing: Timing {
            queue_ms: 0,
            translation_ms: elapsed_ms,
            total_ms: elapsed_ms,
        },
    };
    persist_response(&state, &request.request_id, &response).await?;
    tracing::info!(
        request_id = %request.request_id,
        session_id = %request.session_id,
        generation = request.generation,
        translation_revision = request.translation_revision,
        api_version = 2,
        region_count = request.regions.len(),
        failed_count = failed,
        elapsed_ms,
        "translation batch completed"
    );
    Ok(Json(response))
}

async fn replay<T>(
    state: &AppState,
    request_id: &str,
    request_hash: &str,
    api_version: i16,
    region_count: usize,
) -> Result<Option<T>, ApiError>
where
    T: serde::de::DeserializeOwned,
{
    let reserved = db::reserve(
        &state.db,
        request_id,
        api_version,
        request_hash,
        &state.config.model_id,
        region_count as i32,
    )
    .await
    .map_err(ApiError::Internal)?;
    if reserved {
        return Ok(None);
    }
    let existing = db::load(&state.db, request_id)
        .await
        .map_err(ApiError::Internal)?
        .ok_or_else(|| anyhow::anyhow!("idempotency reservation disappeared"))?;
    if existing.request_hash != request_hash {
        return Err(ApiError::conflict(
            api_version as u16,
            Some(request_id.to_owned()),
            "IDEMPOTENCY_CONFLICT",
            "The request ID was already used with different content",
            false,
        ));
    }
    if existing.status != "COMPLETED" {
        return Err(ApiError::conflict(
            api_version as u16,
            Some(request_id.to_owned()),
            "REQUEST_IN_PROGRESS",
            "The same request is still being processed",
            true,
        ));
    }
    let value = existing
        .response_json
        .ok_or_else(|| anyhow::anyhow!("completed idempotency record has no response"))?;
    Ok(Some(serde_json::from_value(value).map_err(|error| {
        ApiError::Internal(anyhow::Error::new(error))
    })?))
}

async fn persist_response<T: Serialize>(
    state: &AppState,
    request_id: &str,
    response: &T,
) -> Result<(), ApiError> {
    let value = serde_json::to_value(response)
        .map_err(|error| ApiError::Internal(anyhow::Error::new(error)))?;
    db::complete(&state.db, request_id, &value)
        .await
        .map_err(ApiError::Internal)
}

fn request_hash<T: Serialize>(request: &T) -> Result<String, ApiError> {
    let bytes = serde_json::to_vec(request)
        .map_err(|error| ApiError::Internal(anyhow::Error::new(error)))?;
    Ok(hex::encode(Sha256::digest(bytes)))
}

fn authorize(
    config: &Config,
    headers: &HeaderMap,
    schema_version: u16,
    request_id: Option<String>,
) -> Result<(), ApiError> {
    let Some(expected) = &config.api_bearer_token else {
        return Ok(());
    };
    let supplied = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    if supplied == Some(expected.as_str()) {
        Ok(())
    } else {
        Err(ApiError::unauthorized(schema_version, request_id))
    }
}

fn validate_headers(
    headers: &HeaderMap,
    request_id: &str,
    schema_version: u16,
) -> Result<(), ApiError> {
    for name in ["x-request-id", "idempotency-key"] {
        if let Some(value) = headers.get(name) {
            let value = value.to_str().map_err(|_| {
                ApiError::bad_request(
                    schema_version,
                    Some(request_id.to_owned()),
                    format!("{name} is invalid"),
                )
            })?;
            if value != request_id {
                return Err(ApiError::bad_request(
                    schema_version,
                    Some(request_id.to_owned()),
                    format!("{name} must match requestId"),
                ));
            }
        }
    }
    Ok(())
}

fn validate_v1(request: &V1BatchRequest) -> Result<(), ApiError> {
    if request.schema_version != 1 {
        return Err(ApiError::bad_request(
            1,
            Some(request.request_id.clone()),
            "schemaVersion must be 1",
        ));
    }
    validate_regions(
        1,
        &request.request_id,
        request
            .regions
            .iter()
            .map(|region| (&region.region_id, &region.text)),
    )
}

fn validate_ocr_translation(request: &OcrTranslationRequest) -> Result<(), ApiError> {
    let request_id = Some(request.request_id.clone());
    if request.schema_version != 1 {
        return Err(ApiError::bad_request(
            1,
            request_id,
            "schemaVersion must be 1",
        ));
    }
    if request.request_id.trim().is_empty()
        || request.session_id.trim().is_empty()
        || request.generation < 0
    {
        return Err(ApiError::bad_request(
            1,
            request_id,
            "requestId, sessionId, and generation are invalid",
        ));
    }
    if request.image.width == 0
        || request.image.height == 0
        || request.image.width > 8_192
        || request.image.height > 8_192
    {
        return Err(ApiError::bad_request(
            1,
            request_id,
            "image dimensions must be between 1 and 8192 pixels",
        ));
    }
    if !matches!(
        request.image.media_type.as_str(),
        "image/png" | "image/jpeg" | "image/webp"
    ) {
        return Err(ApiError::bad_request(
            1,
            request_id,
            "image mediaType is unsupported",
        ));
    }
    if request.image.data.is_empty()
        || request.image.data.len() > contract::MAX_OCR_IMAGE_BASE64_BYTES
    {
        return Err(ApiError::bad_request(
            1,
            request_id,
            "image data is empty or exceeds the limit",
        ));
    }
    if !matches!(
        request.translation.mode.as_str(),
        "CHINESE_TO_ENGLISH" | "ENGLISH_TO_CHINESE" | "AUTO_BIDIRECTIONAL"
    ) {
        return Err(ApiError::bad_request(
            1,
            request_id,
            "translation mode is unsupported",
        ));
    }
    if !(320..=4_096).contains(&request.ocr.text_det_limit_side_len)
        || !(0.0..=1.0).contains(&request.ocr.text_rec_score_thresh)
    {
        return Err(ApiError::bad_request(
            1,
            request_id,
            "OCR options are outside the supported range",
        ));
    }
    Ok(())
}

fn validate_v2(request: &V2BatchRequest) -> Result<(), ApiError> {
    if request.schema_version != 2 {
        return Err(ApiError::bad_request(
            2,
            Some(request.request_id.clone()),
            "schemaVersion must be 2",
        ));
    }
    if request.session_id.trim().is_empty()
        || request.generation < 0
        || request.translation_revision < 0
    {
        return Err(ApiError::bad_request(
            2,
            Some(request.request_id.clone()),
            "session and revision fields are invalid",
        ));
    }
    if request.batch_part_count == 0 || request.batch_part_index >= request.batch_part_count {
        return Err(ApiError::bad_request(
            2,
            Some(request.request_id.clone()),
            "batch part fields are invalid",
        ));
    }
    if normalized_language_tag(&request.translation.target.language_tag).is_none() {
        return Err(ApiError::bad_request(
            2,
            Some(request.request_id.clone()),
            "target language is unsupported",
        ));
    }
    if request.translation.source.mode == "FIXED"
        && request
            .translation
            .source
            .language_tag
            .as_deref()
            .and_then(normalized_language_tag)
            .is_none()
    {
        return Err(ApiError::bad_request(
            2,
            Some(request.request_id.clone()),
            "fixed source language is required and must be supported",
        ));
    }
    for region in &request.regions {
        let bounds = &region.bounds;
        if bounds.left < 0
            || bounds.top < 0
            || bounds.left >= bounds.right
            || bounds.top >= bounds.bottom
            || bounds.right as u32 > request.capture.source_width
            || bounds.bottom as u32 > request.capture.source_height
        {
            return Err(ApiError::bad_request(
                2,
                Some(request.request_id.clone()),
                format!("region {} has invalid bounds", region.region_id),
            ));
        }
    }
    validate_regions(
        2,
        &request.request_id,
        request
            .regions
            .iter()
            .map(|region| (&region.region_id, &region.text)),
    )
}

fn validate_regions<'a>(
    schema_version: u16,
    request_id: &str,
    regions: impl Iterator<Item = (&'a String, &'a String)>,
) -> Result<(), ApiError> {
    let regions: Vec<_> = regions.collect();
    if request_id.trim().is_empty() {
        return Err(ApiError::bad_request(
            schema_version,
            None,
            "requestId is required",
        ));
    }
    if regions.is_empty() || regions.len() > contract::MAX_REGIONS_PER_BATCH {
        return Err(ApiError::bad_request(
            schema_version,
            Some(request_id.to_owned()),
            "regions must contain between 1 and 64 entries",
        ));
    }
    let mut ids = HashSet::new();
    let mut total_code_points = 0;
    for (region_id, text) in regions {
        if region_id.trim().is_empty() || !ids.insert(region_id) {
            return Err(ApiError::bad_request(
                schema_version,
                Some(request_id.to_owned()),
                "region IDs must be non-empty and unique",
            ));
        }
        let code_points = text.chars().count();
        if code_points == 0 || code_points > contract::MAX_CODE_POINTS_PER_REGION {
            return Err(ApiError::bad_request(
                schema_version,
                Some(request_id.to_owned()),
                format!("region {region_id} has an invalid text length"),
            ));
        }
        total_code_points += code_points;
    }
    if total_code_points > contract::MAX_CODE_POINTS_PER_BATCH {
        return Err(ApiError::bad_request(
            schema_version,
            Some(request_id.to_owned()),
            "batch text is too long",
        ));
    }
    Ok(())
}

fn effective_source_language(
    request: &V2BatchRequest,
    region: &contract::V2Region,
) -> Option<String> {
    if request.translation.source.mode == "FIXED" {
        request.translation.source.language_tag.as_deref()
    } else {
        region
            .language
            .as_ref()
            .map(|language| language.detected_tag.as_str())
            .filter(|tag| *tag != "und")
            .or(request.translation.source.fallback_language_tag.as_deref())
    }
    .and_then(normalized_language_tag)
    .map(str::to_owned)
}

fn detected_source_language(text: &str) -> Option<&'static str> {
    if text.chars().any(is_han_character) {
        Some("zh-Hans")
    } else if text
        .chars()
        .any(|character| character.is_ascii_alphabetic())
    {
        Some("en")
    } else {
        None
    }
}

fn translation_target(mode: &str, source: Option<&str>) -> Option<&'static str> {
    match (mode, source) {
        ("CHINESE_TO_ENGLISH", Some("zh-Hans")) => Some("en"),
        ("ENGLISH_TO_CHINESE", Some("en")) => Some("zh-Hans"),
        ("AUTO_BIDIRECTIONAL", Some("zh-Hans")) => Some("en"),
        ("AUTO_BIDIRECTIONAL", Some("en")) => Some("zh-Hans"),
        _ => None,
    }
}

fn is_han_character(character: char) -> bool {
    matches!(
        character,
        '\u{3400}'..='\u{4DBF}' | '\u{4E00}'..='\u{9FFF}' | '\u{F900}'..='\u{FAFF}'
    )
}

enum RegionExecution {
    Translated(String),
    Preserved,
    Failed(ModelError),
}

async fn translate_region(
    model: &dyn TranslationModel,
    text: &str,
    source_language: Option<String>,
    target_language: Option<String>,
    preserve_identifiers: bool,
) -> RegionExecution {
    let Some(target_language) = target_language else {
        return RegionExecution::Failed(ModelError::InvalidResponse(
            "target language is missing or unsupported".to_owned(),
        ));
    };
    if source_language.as_deref() == Some(target_language.as_str()) || should_preserve(text) {
        return RegionExecution::Preserved;
    }
    match model
        .translate(TranslationInput {
            text: text.to_owned(),
            source_language,
            target_language,
            preserve_identifiers,
        })
        .await
    {
        Ok(output) if output.text.trim() == text.trim() => RegionExecution::Preserved,
        Ok(output) => RegionExecution::Translated(output.text),
        Err(error) => RegionExecution::Failed(error),
    }
}

fn should_preserve(text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return true;
    }
    let code_markers = [
        "suspend fun ",
        " fun ",
        "class ",
        "const ",
        "val ",
        "var ",
        "=>",
        "==",
        "{\n",
        "();",
    ];
    let marker_count = code_markers
        .iter()
        .filter(|marker| trimmed.contains(**marker))
        .count();
    let brace_count = trimmed
        .chars()
        .filter(|ch| matches!(ch, '{' | '}' | '(' | ')' | ';'))
        .count();
    marker_count >= 2 || (trimmed.contains('\n') && brace_count >= 4)
}

fn model_region_error(error: ModelError) -> RegionError {
    RegionError {
        code: error.code().to_owned(),
        message: error.to_string(),
        retryable: !matches!(error, ModelError::InvalidResponse(_)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_mock_kotlin_code_region() {
        assert!(should_preserve(
            "suspend fun commit(frame: Frame) {\n  require(frame.generation == current)\n}"
        ));
        assert!(!should_preserve(
            "Visible completion is the real delivery point"
        ));
    }

    #[test]
    fn resolves_ocr_translation_direction_from_mode_and_script() {
        assert_eq!(detected_source_language("你好 Paddle"), Some("zh-Hans"));
        assert_eq!(detected_source_language("Hello 2026"), Some("en"));
        assert_eq!(detected_source_language("2026"), None);
        assert_eq!(
            translation_target("AUTO_BIDIRECTIONAL", Some("zh-Hans")),
            Some("en")
        );
        assert_eq!(
            translation_target("ENGLISH_TO_CHINESE", Some("en")),
            Some("zh-Hans")
        );
        assert_eq!(translation_target("CHINESE_TO_ENGLISH", Some("en")), None);
    }
}
