use std::{collections::HashMap, sync::Arc, time::Instant};

use crate::{
    contract::{
        GroupTranslationResult, REGIONS_FIRST_SCHEMA_VERSION, ResponseMetrics,
        SemanticTranslationRequest, SemanticTranslationResponse, TranslationGroup, layout_hint,
        resolved_render_slots, source_cover_slots,
    },
    error::V4ServiceError,
    planning::DocumentPlan,
    planning_v4::build_regions_first_plan,
    qwen::{ModelTranslation, PROMPT_VERSION, TranslationModel},
};

pub struct V4TranslationService {
    model: Arc<dyn TranslationModel>,
    model_version: String,
}

pub struct PreparedV4Translation {
    request: SemanticTranslationRequest,
    document_plan: DocumentPlan,
    execution_groups: Vec<TranslationGroup>,
    actionable_groups: Vec<TranslationGroup>,
    pub model_request_json: Option<String>,
}

impl V4TranslationService {
    pub fn new(model: Arc<dyn TranslationModel>, model_version: impl Into<String>) -> Self {
        Self {
            model,
            model_version: model_version.into(),
        }
    }

    pub fn prepare(
        &self,
        request: SemanticTranslationRequest,
    ) -> Result<PreparedV4Translation, V4ServiceError> {
        request.validate_schema(REGIONS_FIRST_SCHEMA_VERSION)?;
        let normalized = normalized_request_for_translation(&request);
        let document_plan = build_regions_first_plan(&normalized);
        let execution_groups = document_plan.translation_groups();
        let actionable_groups = execution_groups
            .iter()
            .filter(|group| !should_preserve(group))
            .cloned()
            .collect::<Vec<_>>();
        let model_request_json = self.model.request_json(&request, &actionable_groups);
        Ok(PreparedV4Translation {
            request,
            document_plan,
            execution_groups,
            actionable_groups,
            model_request_json,
        })
    }

    pub async fn translate(
        &self,
        request: SemanticTranslationRequest,
    ) -> Result<SemanticTranslationResponse, V4ServiceError> {
        let prepared = self.prepare(request)?;
        self.translate_prepared(prepared).await
    }

    pub async fn translate_prepared(
        &self,
        prepared: PreparedV4Translation,
    ) -> Result<SemanticTranslationResponse, V4ServiceError> {
        let started = Instant::now();
        let model_results = self
            .model
            .translate(&prepared.request, &prepared.actionable_groups)
            .await?
            .into_iter()
            .map(|result| (result.group_id.clone(), result))
            .collect::<HashMap<_, _>>();
        build_response(prepared, model_results, &self.model_version, started)
    }
}

fn build_response(
    prepared: PreparedV4Translation,
    model_results: HashMap<String, ModelTranslation>,
    model_version: &str,
    started: Instant,
) -> Result<SemanticTranslationResponse, V4ServiceError> {
    let PreparedV4Translation {
        request,
        document_plan,
        execution_groups,
        ..
    } = prepared;
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
    let mut translated = 0;
    let mut preserved = 0;
    let mut results = Vec::with_capacity(execution_groups.len());

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
            V4ServiceError::Upstream("validated model result was lost during mapping".to_owned())
        })?;
        translated += 1;
        let render_slots = resolved_render_slots(group, &members);
        results.push(GroupTranslationResult {
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
                source_cover_slots(group, &members),
            ),
            error: None,
        });
    }

    Ok(SemanticTranslationResponse {
        schema_version: REGIONS_FIRST_SCHEMA_VERSION,
        request_id: request.request_id,
        session_id: request.session_id,
        generation: request.generation,
        translation_revision: request.translation_revision,
        provider: "embedded-qwen-regions-first-v4".to_owned(),
        model_version: model_version.to_owned(),
        prompt_version: PROMPT_VERSION.to_owned(),
        results,
        document_plan,
        metrics: ResponseMetrics {
            group_count: execution_groups.len(),
            translated_group_count: translated,
            preserved_group_count: preserved,
            failed_group_count: 0,
            total_ms: started.elapsed().as_millis() as u64,
        },
    })
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

fn is_standalone_temporal_value(text: &str) -> bool {
    let mut has_digit = false;
    let mut alphabetic_runs = Vec::new();
    let mut current = String::new();
    for character in text.chars() {
        has_digit |= character.is_ascii_digit();
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
    if is_standalone_temporal_value(text) {
        return true;
    }
    if matches!(text.chars().next(), Some('~' | '-'))
        && text.chars().any(char::is_alphabetic)
        && !text.chars().any(char::is_numeric)
    {
        return true;
    }
    looks_like_author_date_metadata(text)
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
    let Some(month_index) = tokens[..year_index].iter().rposition(is_month_name) else {
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
    !author_tokens.is_empty()
        && author_tokens.len() <= 8
        && author_tokens.iter().all(|token| {
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
    let mut current_latin_run = 0;
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

fn normalized_language(value: &str) -> String {
    let normalized = value.trim().to_lowercase();
    if normalized.is_empty() {
        "auto".to_owned()
    } else {
        normalized
    }
}
