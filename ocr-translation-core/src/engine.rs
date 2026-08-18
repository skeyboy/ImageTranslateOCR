use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::{
    contract::{
        GroupTranslationResult, REGIONS_FIRST_SCHEMA_VERSION, ResponseMetrics,
        SemanticTranslationRequest, SemanticTranslationResponse, TranslationGroup, layout_hint,
        resolved_render_slots, source_cover_slots,
    },
    error::CoreError,
    model::{
        ModelPrompt, ModelTranslation, ModelTranslationFailure, PROMPT_VERSION, build_model_prompt,
        missing_literal_identifiers, parse_completion_envelope_unvalidated,
    },
    planning::DocumentPlan,
    planning_v4::build_regions_first_plan,
};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PreparedTranslation {
    pub request: SemanticTranslationRequest,
    pub document_plan: DocumentPlan,
    pub execution_groups: Vec<TranslationGroup>,
    pub actionable_groups: Vec<TranslationGroup>,
    pub model_prompt: ModelPrompt,
    pub prompt_version: String,
    pub provider: String,
    pub model: String,
    #[serde(default = "embedded_execution")]
    pub execution: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CompletedTranslation {
    pub response: SemanticTranslationResponse,
    pub model_translations: Vec<ModelTranslation>,
}

pub fn prepare_translation(
    request_json: &str,
    provider: &str,
    model: &str,
) -> Result<PreparedTranslation, CoreError> {
    let mut request: SemanticTranslationRequest = serde_json::from_str(request_json)?;
    request.validate_schema(REGIONS_FIRST_SCHEMA_VERSION)?;
    request.debug_capture = None;
    let document_plan = build_regions_first_plan(&request);
    let execution_groups = document_plan.translation_groups();
    let actionable_groups = execution_groups
        .iter()
        .filter(|g| !should_preserve(g))
        .cloned()
        .collect::<Vec<_>>();
    let model_prompt = build_model_prompt(&request, &actionable_groups)?;
    Ok(PreparedTranslation {
        request,
        document_plan,
        execution_groups,
        actionable_groups,
        model_prompt,
        prompt_version: PROMPT_VERSION.to_owned(),
        provider: provider.trim().to_owned(),
        model: model.trim().to_owned(),
        execution: embedded_execution(),
    })
}

pub fn complete_translation(
    prepared_json: &str,
    completion_envelope_json: &str,
    total_ms: u64,
) -> Result<CompletedTranslation, CoreError> {
    let prepared: PreparedTranslation = serde_json::from_str(prepared_json)?;
    let mut translations = parse_completion_envelope_unvalidated(
        completion_envelope_json,
        &prepared.actionable_groups,
    )?;
    if prepared.request.translation.preserve_identifiers {
        for group in &prepared.actionable_groups {
            if let Some(translation) = translations
                .iter_mut()
                .find(|translation| translation.group_id == group.group_id)
            {
                let missing = missing_literal_identifiers(group, &translation.translated_text);
                if !missing.is_empty() {
                    translation.failure = Some(ModelTranslationFailure {
                        code: "IDENTIFIER_PRESERVATION_FAILED".to_owned(),
                        message: format!(
                            "Translation misses identifiers {} for group {}",
                            missing.join(", "),
                            group.group_id
                        ),
                        retryable: true,
                    });
                }
            }
        }
    }
    let response = assemble_translation(&prepared, &translations, total_ms)?;
    Ok(CompletedTranslation {
        response,
        model_translations: translations,
    })
}

pub fn assemble_translation(
    prepared: &PreparedTranslation,
    translations: &[ModelTranslation],
    total_ms: u64,
) -> Result<SemanticTranslationResponse, CoreError> {
    let by_id = translations
        .iter()
        .map(|t| (t.group_id.as_str(), t))
        .collect::<HashMap<_, _>>();
    let regions = prepared
        .request
        .regions
        .iter()
        .map(|r| (r.region_id.as_str(), r))
        .collect::<HashMap<_, _>>();
    let planned = prepared
        .document_plan
        .groups
        .iter()
        .map(|g| (g.group_id.as_str(), g))
        .collect::<HashMap<_, _>>();
    let mut translated_count = 0;
    let mut preserved_count = 0;
    let mut results = Vec::with_capacity(prepared.execution_groups.len());
    for group in &prepared.execution_groups {
        let members = group
            .member_region_ids
            .iter()
            .filter_map(|id| regions.get(id.as_str()).copied())
            .collect::<Vec<_>>();
        let source_group_ids = planned
            .get(group.group_id.as_str())
            .map(|g| g.source_group_ids.clone())
            .unwrap_or_else(|| vec![group.group_id.clone()]);
        let slots = resolved_render_slots(group, &members);
        let cover = source_cover_slots(group, &members);
        if should_preserve(group) {
            preserved_count += 1;
            results.push(GroupTranslationResult {
                group_id: group.group_id.clone(),
                source_group_ids,
                role: group.role.clone(),
                grouping_confidence: group.grouping_confidence,
                status: "PRESERVED".to_owned(),
                render_mode: "NONE".to_owned(),
                translated_text: None,
                member_region_ids: group.member_region_ids.clone(),
                detected_source_language: "auto".to_owned(),
                target_language: prepared.request.translation.target_language.clone(),
                anchor_bounds: group.bounds.clone(),
                layout_hint: layout_hint(group, &group.source_text, slots, cover),
                error: None,
            });
        } else {
            let item = by_id.get(group.group_id.as_str()).ok_or_else(|| {
                CoreError::model(format!(
                    "validated translation missing for {}",
                    group.group_id
                ))
            })?;
            if let Some(failure) = &item.failure {
                results.push(GroupTranslationResult {
                    group_id: group.group_id.clone(),
                    source_group_ids,
                    role: group.role.clone(),
                    grouping_confidence: group.grouping_confidence,
                    status: "FAILED".to_owned(),
                    render_mode: "NONE".to_owned(),
                    translated_text: None,
                    member_region_ids: group.member_region_ids.clone(),
                    detected_source_language: item.detected_source_language.clone(),
                    target_language: item.target_language.clone(),
                    anchor_bounds: group.bounds.clone(),
                    layout_hint: layout_hint(group, &group.source_text, slots, cover),
                    error: Some(crate::contract::ResultError {
                        code: failure.code.clone(),
                        message: failure.message.clone(),
                        retryable: failure.retryable,
                    }),
                });
                continue;
            }
            translated_count += 1;
            results.push(GroupTranslationResult {
                group_id: group.group_id.clone(),
                source_group_ids,
                role: group.role.clone(),
                grouping_confidence: group.grouping_confidence,
                status: "TRANSLATED".to_owned(),
                render_mode: "GROUP".to_owned(),
                translated_text: Some(item.translated_text.clone()),
                member_region_ids: group.member_region_ids.clone(),
                detected_source_language: item.detected_source_language.clone(),
                target_language: item.target_language.clone(),
                anchor_bounds: group.bounds.clone(),
                layout_hint: layout_hint(group, &item.translated_text, slots, cover),
                error: None,
            });
        }
    }
    let failed_count = results
        .iter()
        .filter(|item| item.status == "FAILED")
        .count();
    let response = SemanticTranslationResponse {
        schema_version: REGIONS_FIRST_SCHEMA_VERSION,
        request_id: prepared.request.request_id.clone(),
        session_id: prepared.request.session_id.clone(),
        generation: prepared.request.generation,
        translation_revision: prepared.request.translation_revision,
        provider: if prepared.execution == "self-hosted" {
            format!("self-hosted-{}-regions-first-v4", prepared.provider)
        } else {
            format!("{}-{}-v4", prepared.execution, prepared.provider)
        },
        model_version: prepared.model.clone(),
        prompt_version: PROMPT_VERSION.to_owned(),
        results,
        document_plan: prepared.document_plan.clone(),
        metrics: ResponseMetrics {
            group_count: prepared.execution_groups.len(),
            translated_group_count: translated_count,
            preserved_group_count: preserved_count,
            failed_group_count: failed_count,
            total_ms,
        },
    };
    Ok(response)
}

fn embedded_execution() -> String {
    "embedded".to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prepares_and_completes_a_v4_edge_request_without_server_state() {
        let prepared = prepare_translation(
            include_str!("../examples/v4-minimal-request.json"),
            "openlux",
            "gpt-4.1",
        )
        .unwrap();
        assert_eq!(prepared.execution_groups.len(), 1);
        assert!(!prepared.model_prompt.user.contains("regionLines"));
        assert!(!prepared.model_prompt.user.contains("renderSlots"));
        assert!(!prepared.model_prompt.user.contains("documentContext"));
        assert_eq!(prepared.model_prompt.recommended_max_tokens, 1024);
        let user: serde_json::Value = serde_json::from_str(&prepared.model_prompt.user).unwrap();
        assert_eq!(user["promptProfile"], "COMPACT");
        assert_eq!(
            prepared.model_prompt.response_format["json_schema"]["schema"]["properties"]["translations"]
                ["type"],
            "array"
        );
        let prepared_json = serde_json::to_string(&prepared).unwrap();
        let group_id = &prepared.actionable_groups[0].group_id;
        let completion = serde_json::json!({"choices":[{"message":{"content":serde_json::json!({
            "translations": [{
                "groupId": group_id,
                "translatedText":"Rust 是一种系统编程语言。",
                "detectedSourceLanguage":"en",
                "targetLanguage":"zh"
            }]
        }).to_string()}}]})
        .to_string();
        let completed = complete_translation(&prepared_json, &completion, 25).unwrap();
        assert_eq!(
            completed.response.results[0].translated_text.as_deref(),
            Some("Rust 是一种系统编程语言。")
        );
        assert_eq!(completed.response.provider, "embedded-openlux-v4");
        assert_eq!(completed.response.metrics.total_ms, 25);
    }

    #[test]
    fn compact_prompt_does_not_change_document_plan_or_dsl_geometry() {
        let request_json = include_str!("../examples/v4-minimal-request.json");
        let compact = prepare_translation(request_json, "openlux", "gpt-4.1").unwrap();
        let mut full_request: SemanticTranslationRequest =
            serde_json::from_str(request_json).unwrap();
        full_request.translation.compact_provider_prompt = false;
        let full = prepare_translation(
            &serde_json::to_string(&full_request).unwrap(),
            "openlux",
            "gpt-4.1",
        )
        .unwrap();
        assert!(full.model_prompt.user.contains("regionLines"));
        assert_eq!(
            serde_json::to_value(&compact.document_plan).unwrap(),
            serde_json::to_value(&full.document_plan).unwrap()
        );

        let translations = vec![ModelTranslation {
            group_id: compact.actionable_groups[0].group_id.clone(),
            translated_text: "Rust 是一种系统编程语言。".to_owned(),
            detected_source_language: "en".to_owned(),
            target_language: "zh".to_owned(),
            failure: None,
        }];
        let compact_response = assemble_translation(&compact, &translations, 10).unwrap();
        let full_response = assemble_translation(&full, &translations, 10).unwrap();
        assert_eq!(
            serde_json::to_value(&compact_response.results[0].anchor_bounds).unwrap(),
            serde_json::to_value(&full_response.results[0].anchor_bounds).unwrap()
        );
        assert_eq!(
            serde_json::to_value(&compact_response.results[0].layout_hint).unwrap(),
            serde_json::to_value(&full_response.results[0].layout_hint).unwrap()
        );
    }

    #[test]
    fn assembles_identifier_validation_failure_without_losing_other_groups() {
        let prepared = prepare_translation(
            include_str!("../examples/v4-minimal-request.json"),
            "gemini-native",
            "gemini-test",
        )
        .unwrap();
        let group = &prepared.actionable_groups[0];
        let translations = vec![ModelTranslation {
            group_id: group.group_id.clone(),
            translated_text: group.source_text.clone(),
            detected_source_language: "en".to_owned(),
            target_language: "zh".to_owned(),
            failure: Some(crate::model::ModelTranslationFailure {
                code: "IDENTIFIER_PRESERVATION_FAILED".to_owned(),
                message: "missing CCPA".to_owned(),
                retryable: true,
            }),
        }];

        let response = assemble_translation(&prepared, &translations, 12).unwrap();

        assert_eq!(response.results[0].status, "FAILED");
        assert!(response.results[0].translated_text.is_none());
        assert_eq!(response.metrics.failed_group_count, 1);
    }
}

pub fn should_preserve(group: &TranslationGroup) -> bool {
    let text = group.source_text.trim();
    if text.is_empty() || matches!(group.role.as_str(), "CODE" | "IDENTIFIER" | "CONTROL") {
        return true;
    }
    if group.translation_unit == "PRESERVED" && !matches!(group.role.as_str(), "BODY" | "CAPTION") {
        return true;
    }
    if group.role == "TIMESTAMP" {
        return text
            .chars()
            .all(|c| c.is_ascii_digit() || c.is_ascii_punctuation() || c.is_whitespace());
    }
    false
}
