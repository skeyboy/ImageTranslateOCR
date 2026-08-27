use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::{
    contract::{SemanticTranslationRequest, TranslationGroup, resolved_render_slots},
    error::CoreError,
};

pub const PROMPT_VERSION: &str = "semantic-translation-core-v5-provider-compact";

pub const SYSTEM_PROMPT: &str = r#"You are a screen OCR translation engine. Input is JSON with mode and ordered groups. Each group has id, text, src, dst, role, box, scale, type, and optional keep. Translate every text independently and completely; use order, role, box, scale, and type only to resolve context. Newlines inside text are OCR lines of one semantic unit: reflow naturally, but never split, merge, omit, summarize, move, or borrow content across IDs. Preserve URLs, identifiers, names, brands, numbers, dates, units, and currencies. Copy every keep value verbatim into the same group's translated text; missing or moving one is invalid. For AUTO_BIDIRECTIONAL, translate Chinese natural language to English and other natural language to Chinese. Return strict JSON matching response_format with every input id exactly once and every translated text non-empty."#;

const FULL_GEOMETRY_SYSTEM_PROMPT: &str = r#"You are a professional screen OCR translation engine.
The input is one visible screen reconstructed from OCR geometry. Translate each translateGroups item independently and completely, while using documentOutline and neighboring geometry only to disambiguate meaning.
Treat newline-separated OCR lines inside sourceText as one semantic block. Never imitate OCR line breaks, split a block back into lines, merge keys, borrow text from another key, summarize, or add notes.
Preserve URLs, identifiers, names, brands, numbers, dates, units, and currencies. Every requiredLiteralIdentifiers item must remain visible verbatim and in the same semantic role.
Typography tier and relative scale are context for document hierarchy only. Never use them to split, merge, omit, or rename a groupId.
For AUTO_BIDIRECTIONAL translate Chinese natural language to English and non-Chinese natural language to Chinese.
Return only the strict JSON object requested by response_format. Return translations as an array. Every input groupId must occur exactly once and every translatedText must be non-empty."#;

pub const DIRECT_STRUCTURED_OUTPUT_PROMPT: &str = "Skip analysis, reasoning exposition, and preamble. Execute the JSON instructions directly and return only the requested structured translation result.";

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelTranslation {
    pub group_id: String,
    pub translated_text: String,
    pub detected_source_language: String,
    pub target_language: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub failure: Option<ModelTranslationFailure>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelTranslationFailure {
    pub code: String,
    pub message: String,
    pub retryable: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelPrompt {
    pub system: String,
    pub user: String,
    pub response_format: Value,
    pub recommended_max_tokens: u32,
    pub bindings: Vec<ModelGroupBinding>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelGroupBinding {
    pub alias_id: String,
    pub group_id: String,
    pub source_language: String,
    pub target_language: String,
}

#[derive(Serialize)]
struct CompactProviderPayload<'a> {
    mode: &'a str,
    groups: Vec<CompactProviderGroup<'a>>,
}

#[derive(Serialize)]
struct CompactProviderGroup<'a> {
    id: &'a str,
    text: &'a str,
    src: &'a str,
    dst: &'a str,
    role: &'a str,
    #[serde(rename = "box")]
    normalized_bounds: [u16; 4],
    scale: u16,
    #[serde(rename = "type")]
    typography_tier: &'static str,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    keep: Vec<String>,
}

pub fn build_model_prompt(
    request: &SemanticTranslationRequest,
    groups: &[TranslationGroup],
) -> Result<ModelPrompt, CoreError> {
    let regions = request
        .regions
        .iter()
        .map(|r| (r.region_id.as_str(), r))
        .collect::<HashMap<_, _>>();
    let bindings = model_group_bindings(request, groups);
    let width = request.viewport.width as f32;
    let height = request.viewport.height as f32;
    let viewport_text_height = median_text_height(request.regions.iter()).unwrap_or(1.0);
    if request.translation.compact_provider_prompt {
        let provider_groups = groups
            .iter()
            .zip(&bindings)
            .map(|(group, binding)| {
                let members = group
                    .member_region_ids
                    .iter()
                    .filter_map(|id| regions.get(id.as_str()).copied())
                    .collect::<Vec<_>>();
                let group_text_height =
                    median_text_height(members.iter().copied()).unwrap_or(viewport_text_height);
                let relative_text_scale = group_text_height / viewport_text_height.max(1.0);
                CompactProviderGroup {
                    id: &binding.alias_id,
                    text: &group.source_text,
                    src: &binding.source_language,
                    dst: &binding.target_language,
                    role: &group.role,
                    normalized_bounds: quantized_provider_bounds(
                        &group.bounds,
                        request.viewport.width,
                        request.viewport.height,
                    ),
                    scale: (relative_text_scale * 100.0).round().clamp(1.0, 1000.0) as u16,
                    typography_tier: typography_tier(relative_text_scale),
                    keep: if request.translation.preserve_identifiers {
                        literal_identifiers(&group.source_text)
                    } else {
                        Vec::new()
                    },
                }
            })
            .collect();
        let payload = CompactProviderPayload {
            mode: &request.translation.mode,
            groups: provider_groups,
        };
        return Ok(ModelPrompt {
            system: prompt_system(SYSTEM_PROMPT, request.translation.direct_structured_output),
            user: serde_json::to_string(&payload)?,
            response_format: response_format(true),
            recommended_max_tokens: adaptive_max_tokens(groups),
            bindings,
        });
    }
    let translate_groups = groups.iter().map(|group| {
        let members = group.member_region_ids.iter().filter_map(|id| regions.get(id.as_str()).copied()).collect::<Vec<_>>();
        let group_text_height = median_text_height(members.iter().copied()).unwrap_or(viewport_text_height);
        let relative_text_scale = group_text_height / viewport_text_height.max(1.0);
        let common = json!({
            "groupId": group.group_id,
            "role": group.role,
            "sourceText": group.source_text,
            "sourceLanguage": members.first().and_then(|r| r.source_language.as_deref()).unwrap_or("auto"),
            "targetLanguage": members.first().and_then(|r| r.target_language.as_deref()).unwrap_or(request.translation.target_language.as_str()),
            "requiredLiteralIdentifiers": if request.translation.preserve_identifiers {
                literal_identifiers(&group.source_text)
            } else {
                Vec::new()
            },
            "readingOrder": group.reading_order,
            "typographyTier": typography_tier(relative_text_scale),
            "relativeTextScale": (relative_text_scale * 100.0).round() / 100.0,
        });
        let slots = resolved_render_slots(group, &members);
        let mut full = common;
        full["normalizedBounds"] = json!([
            group.bounds.left as f32 / width,
            group.bounds.top as f32 / height,
            group.bounds.right as f32 / width,
            group.bounds.bottom as f32 / height,
        ]);
        full["layoutShape"] = json!(group.layout_shape);
        full["renderSlots"] = json!(slots.iter().map(|slot| [
            slot.left as f32 / width,
            slot.top as f32 / height,
            slot.right as f32 / width,
            slot.bottom as f32 / height,
        ]).collect::<Vec<_>>());
        full["regionLines"] = json!(members.iter().map(|region| json!({
            "regionId": region.region_id,
            "readingOrder": region.reading_order,
            "normalizedBounds": [
                region.bounds.left as f32 / width,
                region.bounds.top as f32 / height,
                region.bounds.right as f32 / width,
                region.bounds.bottom as f32 / height,
            ]
        })).collect::<Vec<_>>());
        full
    }).collect::<Vec<_>>();
    let document_outline = groups
        .iter()
        .map(|group| {
            json!({
                "groupId": group.group_id,
                "role": group.role,
                "readingOrder": group.reading_order,
                "sourcePreview": compact_preview(&group.source_text, 72)
            })
        })
        .collect::<Vec<_>>();
    let mut payload = json!({
        "task": "Translate every translateGroups entry as one complete semantic unit. Geometry is context; the caller creates layout.",
        "promptProfile": if request.translation.compact_provider_prompt { "COMPACT" } else { "FULL_GEOMETRY" },
        "scene": request.scene,
        "translationMode": request.translation.mode,
        "documentOutline": request.translation.use_document_context.then_some(document_outline),
        "translateGroups": translate_groups
    });
    if !request.translation.compact_provider_prompt {
        payload["viewport"] = json!({
            "width": request.viewport.width,
            "height": request.viewport.height,
        });
    }
    let user = serde_json::to_string(&payload)?;
    Ok(ModelPrompt {
        system: prompt_system(
            FULL_GEOMETRY_SYSTEM_PROMPT,
            request.translation.direct_structured_output,
        ),
        user,
        response_format: response_format(false),
        recommended_max_tokens: adaptive_max_tokens(groups),
        bindings,
    })
}

fn prompt_system(base: &str, direct_structured_output: bool) -> String {
    if direct_structured_output {
        format!("{base}\n{DIRECT_STRUCTURED_OUTPUT_PROMPT}")
    } else {
        base.to_owned()
    }
}

pub fn model_group_bindings(
    request: &SemanticTranslationRequest,
    groups: &[TranslationGroup],
) -> Vec<ModelGroupBinding> {
    let regions = request
        .regions
        .iter()
        .map(|region| (region.region_id.as_str(), region))
        .collect::<HashMap<_, _>>();
    groups
        .iter()
        .enumerate()
        .map(|(index, group)| {
            let first_region = group
                .member_region_ids
                .iter()
                .find_map(|id| regions.get(id.as_str()).copied());
            ModelGroupBinding {
                alias_id: format!("g{index}"),
                group_id: group.group_id.clone(),
                source_language: first_region
                    .and_then(|region| region.source_language.clone())
                    .unwrap_or_else(|| request.translation.source_language.clone()),
                target_language: first_region
                    .and_then(|region| region.target_language.clone())
                    .unwrap_or_else(|| request.translation.target_language.clone()),
            }
        })
        .collect()
}

fn median_text_height<'a>(
    regions: impl Iterator<Item = &'a crate::contract::OcrRegion>,
) -> Option<f32> {
    let mut heights = regions
        .map(|region| {
            region
                .estimated_text_height_px
                .filter(|height| height.is_finite() && *height > 0.0)
                .unwrap_or_else(|| region.bounds.height().max(1) as f32)
        })
        .collect::<Vec<_>>();
    if heights.is_empty() {
        return None;
    }
    heights.sort_by(f32::total_cmp);
    Some(heights[heights.len() / 2])
}

fn typography_tier(relative_scale: f32) -> &'static str {
    if relative_scale < 0.82 {
        "SMALL"
    } else if relative_scale > 1.25 {
        "LARGE"
    } else {
        "NORMAL"
    }
}

fn quantized_provider_bounds(
    bounds: &crate::contract::Bounds,
    width: i32,
    height: i32,
) -> [u16; 4] {
    let quantize = |value: i32, extent: i32| {
        ((value as f64 / extent as f64) * 1_000.0)
            .round()
            .clamp(0.0, 1_000.0) as u16
    };
    [
        quantize(bounds.left, width),
        quantize(bounds.top, height),
        quantize(bounds.right, width),
        quantize(bounds.bottom, height),
    ]
}

pub fn parse_completion_envelope(
    raw: &str,
    groups: &[TranslationGroup],
) -> Result<Vec<ModelTranslation>, CoreError> {
    let envelope: Value = serde_json::from_str(raw)?;
    let content = envelope
        .pointer("/choices/0/message/content")
        .and_then(Value::as_str)
        .unwrap_or(raw);
    parse_translation_content(content, groups)
}

pub fn parse_completion_envelope_unvalidated(
    raw: &str,
    groups: &[TranslationGroup],
) -> Result<Vec<ModelTranslation>, CoreError> {
    let envelope: Value = serde_json::from_str(raw)?;
    let content = envelope
        .pointer("/choices/0/message/content")
        .and_then(Value::as_str)
        .unwrap_or(raw);
    parse_translation_content_unvalidated(content, groups)
}

pub fn parse_completion_envelope_for_request_unvalidated(
    raw: &str,
    request: &SemanticTranslationRequest,
    groups: &[TranslationGroup],
) -> Result<Vec<ModelTranslation>, CoreError> {
    let envelope: Value = serde_json::from_str(raw)?;
    let content = envelope
        .pointer("/choices/0/message/content")
        .and_then(Value::as_str)
        .unwrap_or(raw);
    parse_translation_content_for_request_unvalidated(content, request, groups)
}

pub fn parse_translation_content(
    raw: &str,
    groups: &[TranslationGroup],
) -> Result<Vec<ModelTranslation>, CoreError> {
    let translations = parse_translation_content_unvalidated(raw, groups)?;
    validate_literal_identifiers(groups, &translations)?;
    Ok(translations)
}

pub fn parse_translation_content_unvalidated(
    raw: &str,
    groups: &[TranslationGroup],
) -> Result<Vec<ModelTranslation>, CoreError> {
    let bindings = groups
        .iter()
        .enumerate()
        .map(|(index, group)| ModelGroupBinding {
            alias_id: format!("g{index}"),
            group_id: group.group_id.clone(),
            source_language: "auto".to_owned(),
            target_language: requested_target(group).to_owned(),
        })
        .collect::<Vec<_>>();
    parse_translation_content_with_bindings(raw, groups, &bindings)
}

pub fn parse_translation_content_for_request_unvalidated(
    raw: &str,
    request: &SemanticTranslationRequest,
    groups: &[TranslationGroup],
) -> Result<Vec<ModelTranslation>, CoreError> {
    let bindings = model_group_bindings(request, groups);
    parse_translation_content_with_bindings(raw, groups, &bindings)
}

fn parse_translation_content_with_bindings(
    raw: &str,
    groups: &[TranslationGroup],
    bindings: &[ModelGroupBinding],
) -> Result<Vec<ModelTranslation>, CoreError> {
    let cleaned = raw
        .trim()
        .strip_prefix("```json")
        .or_else(|| raw.trim().strip_prefix("```"))
        .unwrap_or(raw.trim())
        .strip_suffix("```")
        .unwrap_or(raw.trim())
        .trim();
    let value: Value = serde_json::from_str(cleaned)
        .map_err(|e| CoreError::model(format!("invalid AI translation JSON: {e}")))?;
    let translations = value
        .get("translations")
        .or_else(|| value.get("results"))
        .ok_or_else(|| CoreError::model("AI response must contain translations"))?;
    if bindings.len() != groups.len() {
        return Err(CoreError::model(
            "model prompt bindings do not match translation groups",
        ));
    }
    let bindings_by_id = bindings
        .iter()
        .flat_map(|binding| {
            [
                (binding.alias_id.as_str(), binding),
                (binding.group_id.as_str(), binding),
            ]
        })
        .collect::<HashMap<_, _>>();
    let raw_items = if let Some(array) = translations.as_array() {
        array
            .iter()
            .map(|item| {
                let id = item
                    .get("id")
                    .or_else(|| item.get("groupId"))
                    .and_then(Value::as_str)
                    .ok_or_else(|| CoreError::model("AI translation is missing id"))?;
                Ok((id, item))
            })
            .collect::<Result<Vec<_>, CoreError>>()?
    } else if let Some(map) = translations.as_object() {
        map.iter().map(|(id, item)| (id.as_str(), item)).collect()
    } else {
        return Err(CoreError::model("translations must be an array"));
    };
    let mut items = HashMap::with_capacity(raw_items.len());
    for (returned_id, item) in raw_items {
        let binding = bindings_by_id
            .get(returned_id)
            .ok_or_else(|| CoreError::model(format!("AI returned unknown id {returned_id}")))?;
        if items.insert(binding.group_id.as_str(), item).is_some() {
            return Err(CoreError::model(format!(
                "AI returned duplicate id {returned_id}"
            )));
        }
    }
    if items.len() != groups.len() {
        return Err(CoreError::model(
            "AI response group IDs do not exactly match the request",
        ));
    }
    groups
        .iter()
        .map(|group| {
            let item = items
                .get(group.group_id.as_str())
                .copied()
                .and_then(Value::as_object)
                .ok_or_else(|| CoreError::model(format!("AI omitted group {}", group.group_id)))?;
            let translated = item
                .get("text")
                .or_else(|| item.get("translatedText"))
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|v| !v.is_empty())
                .ok_or_else(|| {
                    CoreError::model(format!(
                        "AI returned empty text for group {}",
                        group.group_id
                    ))
                })?;
            Ok(ModelTranslation {
                group_id: group.group_id.clone(),
                translated_text: translated.to_owned(),
                detected_source_language: item
                    .get("src")
                    .or_else(|| item.get("detectedSourceLanguage"))
                    .and_then(Value::as_str)
                    .unwrap_or_else(|| {
                        bindings
                            .iter()
                            .find(|binding| binding.group_id == group.group_id)
                            .map(|binding| binding.source_language.as_str())
                            .unwrap_or("auto")
                    })
                    .to_owned(),
                target_language: item
                    .get("dst")
                    .or_else(|| item.get("targetLanguage"))
                    .and_then(Value::as_str)
                    .unwrap_or_else(|| {
                        bindings
                            .iter()
                            .find(|binding| binding.group_id == group.group_id)
                            .map(|binding| binding.target_language.as_str())
                            .unwrap_or_else(|| requested_target(group))
                    })
                    .to_owned(),
                failure: None,
            })
        })
        .collect()
}

pub fn validate_literal_identifiers(
    groups: &[TranslationGroup],
    translations: &[ModelTranslation],
) -> Result<(), CoreError> {
    for group in groups {
        let translated = translations
            .iter()
            .find(|item| item.group_id == group.group_id)
            .ok_or_else(|| CoreError::model(format!("AI omitted group {}", group.group_id)))?;
        if translated.failure.is_some() {
            continue;
        }
        if let Some(literal) = missing_literal_identifiers(group, &translated.translated_text)
            .into_iter()
            .next()
        {
            return Err(CoreError::model(format!(
                "AI translation lost identifier {literal} for group {}",
                group.group_id
            )));
        }
    }
    Ok(())
}

pub fn missing_literal_identifiers(group: &TranslationGroup, translated: &str) -> Vec<String> {
    literal_identifiers(&group.source_text)
        .into_iter()
        .filter(|literal| !translated.contains(literal))
        .collect()
}

fn requested_target(_group: &TranslationGroup) -> &'static str {
    "auto"
}

fn response_format(compact: bool) -> Value {
    if compact {
        json!({"type":"json_schema","json_schema":{"name":"semantic_translation","strict":true,"schema":{
            "type":"object","properties":{"translations":{"type":"array","items":{"type":"object","properties":{
                "id":{"type":"string","minLength":2},
                "text":{"type":"string","minLength":1}
            },"required":["id","text"],"additionalProperties":false}}},"required":["translations"],"additionalProperties":false
        }}})
    } else {
        json!({"type":"json_schema","json_schema":{"name":"semantic_translation","strict":true,"schema":{
            "type":"object","properties":{"translations":{"type":"array","items":{"type":"object","properties":{
                "groupId":{"type":"string","minLength":1},
                "translatedText":{"type":"string","minLength":1},
                "detectedSourceLanguage":{"type":"string","minLength":1},
                "targetLanguage":{"type":"string","minLength":1}
            },"required":["groupId","translatedText","detectedSourceLanguage","targetLanguage"],"additionalProperties":false}}},"required":["translations"],"additionalProperties":false
        }}})
    }
}

pub fn adaptive_max_tokens(groups: &[TranslationGroup]) -> u32 {
    let source_chars = groups
        .iter()
        .map(|group| group.source_text.chars().count())
        .sum::<usize>();
    let estimated = source_chars
        .saturating_add(groups.len().saturating_mul(96))
        .saturating_add(384);
    estimated.clamp(1024, 4096) as u32
}

fn compact_preview(text: &str, max_chars: usize) -> String {
    let normalized = text.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut preview = normalized.chars().take(max_chars).collect::<String>();
    if normalized.chars().count() > max_chars {
        preview.push_str("...");
    }
    preview
}

pub fn literal_identifiers(text: &str) -> Vec<String> {
    let mut identifiers = HashSet::new();
    let has_lowercase = text.chars().any(|character| character.is_ascii_lowercase());
    for token in text
        .split(|c: char| {
            c.is_whitespace()
                || matches!(
                    c,
                    ',' | '.' | ';' | ':' | '(' | ')' | '[' | ']' | '|' | '·' | '•'
                )
        })
        .map(|token| {
            token.trim().trim_matches(|character| {
                matches!(character, '\'' | '"' | '‘' | '’' | '“' | '”' | '«' | '»')
            })
        })
        .filter(|token| !token.is_empty())
    {
        // OCR occasionally joins words and a digit across a quote, for example
        // `been"1found`. That is prose corruption, not a stable identifier the
        // translation model can be required to reproduce verbatim.
        if token
            .chars()
            .any(|character| matches!(character, '\'' | '"' | '‘' | '’' | '“' | '”' | '«' | '»'))
        {
            continue;
        }
        if let Some(number) = compact_quantity_number(token) {
            if number.len() >= 2 {
                identifiers.insert(number.to_owned());
            }
            continue;
        }
        let alphabetic = token
            .chars()
            .filter(|c| c.is_ascii_alphabetic())
            .collect::<String>();
        let uppercase_identifier =
            alphabetic.len() >= 2 && alphabetic.chars().all(|c| c.is_ascii_uppercase());
        let uppercase_hyphen_segment = token.contains('-')
            && token.split('-').any(|segment| {
                segment.len() >= 2 && segment.chars().all(|c| c.is_ascii_uppercase())
            });
        if token.len() >= 2
            && (token.contains("http")
                || token.contains('.')
                || token.chars().any(|c| c.is_ascii_digit())
                || uppercase_identifier && has_lowercase
                || uppercase_hyphen_segment)
        {
            identifiers.insert(token.to_owned());
        }
    }
    let mut identifiers = identifiers.into_iter().collect::<Vec<_>>();
    identifiers.sort();
    identifiers
}

fn compact_quantity_number(token: &str) -> Option<&str> {
    let digit_bytes = token.bytes().take_while(u8::is_ascii_digit).count();
    if digit_bytes == 0 || digit_bytes == token.len() {
        return None;
    }
    let (number, suffix) = token.split_at(digit_bytes);
    matches!(
        suffix.to_ascii_lowercase().as_str(),
        "point"
            | "points"
            | "comment"
            | "comments"
            | "minute"
            | "minutes"
            | "hour"
            | "hours"
            | "day"
            | "days"
            | "second"
            | "seconds"
            | "vote"
            | "votes"
            | "item"
            | "items"
    )
    .then_some(number)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn provider_test_group(request: &mut SemanticTranslationRequest) -> TranslationGroup {
        request.regions[0].region_id = "r1".to_owned();
        request.regions[0].text = "Rust is a systems programming language.".to_owned();
        request.document_context.reading_order_region_ids = vec!["r1".to_owned()];
        serde_json::from_value(json!({
            "groupId":"server-v4-0-c56b4a9c84e57578",
            "role":"BODY",
            "translationUnit":"GROUP",
            "sourceText":"Rust is a systems programming language.",
            "memberRegionIds":["r1"],
            "readingOrder":0,
            "groupingConfidence":1.0,
            "bounds":{"left":10,"top":20,"right":500,"bottom":80}
        }))
        .unwrap()
    }

    #[test]
    fn identifier_requirements_follow_the_request_option() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/v4-minimal-request.json")).unwrap();
        let group: TranslationGroup = serde_json::from_value(json!({
            "groupId":"g", "role":"BODY", "translationUnit":"GROUP",
            "sourceText":"This CCPA notice applies.", "memberRegionIds":["r1"],
            "readingOrder":0, "groupingConfidence":1.0,
            "bounds":{"left":0,"top":0,"right":100,"bottom":20}
        }))
        .unwrap();
        request.regions[0].region_id = "r1".to_owned();
        request.regions[0].text = group.source_text.clone();

        let preserving = build_model_prompt(&request, std::slice::from_ref(&group)).unwrap();
        let preserving_user: Value = serde_json::from_str(&preserving.user).unwrap();
        assert_eq!(preserving_user["groups"][0]["keep"], json!(["CCPA"]));
        assert!(preserving.system.contains("Copy every keep value verbatim"));

        request.translation.preserve_identifiers = false;
        let relaxed = build_model_prompt(&request, &[group]).unwrap();
        let relaxed_user: Value = serde_json::from_str(&relaxed.user).unwrap();
        assert!(relaxed_user["groups"][0].get("keep").is_none());
    }

    #[test]
    fn direct_structured_output_instruction_is_strictly_opt_in() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/v4-minimal-request.json")).unwrap();
        let group: TranslationGroup = serde_json::from_value(json!({
            "groupId":"g", "role":"BODY", "translationUnit":"GROUP",
            "sourceText":"Hello", "memberRegionIds":[], "readingOrder":0,
            "groupingConfidence":1.0, "bounds":{"left":0,"top":0,"right":1,"bottom":1}
        }))
        .unwrap();
        let regular = build_model_prompt(&request, std::slice::from_ref(&group)).unwrap();
        assert!(!regular.system.contains(DIRECT_STRUCTURED_OUTPUT_PROMPT));

        request.translation.direct_structured_output = true;
        let direct = build_model_prompt(&request, &[group]).unwrap();
        assert!(direct.system.ends_with(DIRECT_STRUCTURED_OUTPUT_PROMPT));
    }

    #[test]
    fn compact_prompt_omits_render_geometry_and_quantizes_group_bounds() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/v4-minimal-request.json")).unwrap();
        request.translation.compact_provider_prompt = true;
        let group: TranslationGroup = serde_json::from_value(json!({
            "groupId":"g", "role":"BODY", "translationUnit":"GROUP",
            "sourceText":"Hello world", "memberRegionIds":["r1"], "readingOrder":0,
            "groupingConfidence":1.0, "bounds":{"left":13,"top":27,"right":301,"bottom":119},
            "renderSlots":[{"left":13,"top":27,"right":301,"bottom":119}],
            "layoutShape":"RECT"
        }))
        .unwrap();
        request.groups = vec![group.clone()];
        request.regions[0].region_id = "r1".to_owned();
        request.regions[0].text = "Hello world".to_owned();
        request.regions[0].bounds = group.bounds.clone();
        request.document_context.reading_order_region_ids = vec!["r1".to_owned()];

        let compact = build_model_prompt(&request, std::slice::from_ref(&group)).unwrap();
        let compact_user: Value = serde_json::from_str(&compact.user).unwrap();
        let compact_group = &compact_user["groups"][0];
        assert_eq!(compact_user["mode"], "AUTO_BIDIRECTIONAL");
        assert_eq!(compact_group["id"], "g0");
        assert_eq!(compact_group["text"], "Hello world");
        assert_eq!(compact_group["type"], "NORMAL");
        assert_eq!(compact_group["scale"], 100);
        assert!(compact_user.get("viewport").is_none());
        assert!(compact_group.get("regionLines").is_none());
        assert!(compact_group.get("renderSlots").is_none());
        assert_eq!(compact_group["box"], json!([12, 11, 279, 50]));

        request.translation.compact_provider_prompt = false;
        let full = build_model_prompt(&request, &[group]).unwrap();
        let full_user: Value = serde_json::from_str(&full.user).unwrap();
        assert_eq!(full_user["promptProfile"], "FULL_GEOMETRY");
        assert!(full_user.get("viewport").is_some());
        assert!(full_user["translateGroups"][0].get("regionLines").is_some());
        assert!(full_user["translateGroups"][0].get("renderSlots").is_some());
        assert!(compact.user.len() < full.user.len());
    }

    #[test]
    fn compact_response_restores_canonical_id_and_input_languages() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/v4-minimal-request.json")).unwrap();
        let group = provider_test_group(&mut request);
        request.regions[0].source_language = Some("en".to_owned());
        request.regions[0].target_language = Some("zh".to_owned());

        let parsed = parse_translation_content_for_request_unvalidated(
            r#"{"translations":[{"id":"g0","text":"Rust 是一种系统编程语言。"}]}"#,
            &request,
            std::slice::from_ref(&group),
        )
        .unwrap();

        assert_eq!(parsed[0].group_id, group.group_id);
        assert_eq!(parsed[0].detected_source_language, "en");
        assert_eq!(parsed[0].target_language, "zh");
    }

    #[test]
    fn compact_response_rejects_duplicate_or_unknown_aliases() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/v4-minimal-request.json")).unwrap();
        let group = provider_test_group(&mut request);
        let groups = std::slice::from_ref(&group);

        let duplicate = parse_translation_content_for_request_unvalidated(
            r#"{"translations":[{"id":"g0","text":"一"},{"id":"g0","text":"二"}]}"#,
            &request,
            groups,
        )
        .unwrap_err();
        assert!(duplicate.to_string().contains("duplicate id"));

        let unknown = parse_translation_content_for_request_unvalidated(
            r#"{"translations":[{"id":"g9","text":"一"}]}"#,
            &request,
            groups,
        )
        .unwrap_err();
        assert!(unknown.to_string().contains("unknown id"));
    }

    #[test]
    fn full_geometry_prompt_keeps_the_legacy_schema_as_a_rollback_path() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/v4-minimal-request.json")).unwrap();
        let group = provider_test_group(&mut request);
        request.translation.compact_provider_prompt = false;

        let prompt = build_model_prompt(&request, std::slice::from_ref(&group)).unwrap();
        let user: Value = serde_json::from_str(&prompt.user).unwrap();
        let properties = &prompt.response_format["json_schema"]["schema"]["properties"]["translations"]
            ["items"]["properties"];

        assert_eq!(user["promptProfile"], "FULL_GEOMETRY");
        assert!(user["translateGroups"][0].get("regionLines").is_some());
        assert!(properties.get("groupId").is_some());
        assert!(properties.get("translatedText").is_some());
    }

    #[test]
    fn adaptive_budget_stays_small_for_a_typical_screen_and_scales_for_long_pages() {
        let mut group: TranslationGroup = serde_json::from_value(json!({
            "groupId":"g", "role":"BODY", "translationUnit":"GROUP",
            "sourceText":"short paragraph", "memberRegionIds":[], "readingOrder":0,
            "groupingConfidence":1.0, "bounds":{"left":0,"top":0,"right":1,"bottom":1}
        }))
        .unwrap();
        assert_eq!(adaptive_max_tokens(&[group.clone()]), 1024);
        group.source_text = "a".repeat(1500);
        assert_eq!(adaptive_max_tokens(&[group.clone()]), 1980);
        group.source_text = "a".repeat(10_000);
        assert_eq!(adaptive_max_tokens(&[group]), 4096);
    }

    #[test]
    fn fixed_array_parser_still_accepts_legacy_keyed_responses() {
        let group: TranslationGroup = serde_json::from_value(json!({
            "groupId":"g", "role":"BODY", "translationUnit":"GROUP",
            "sourceText":"Hello", "memberRegionIds":[], "readingOrder":0,
            "groupingConfidence":1.0, "bounds":{"left":0,"top":0,"right":1,"bottom":1}
        }))
        .unwrap();
        let parsed = parse_translation_content(
            r#"{"translations":{"g":{"translatedText":"你好","detectedSourceLanguage":"en","targetLanguage":"zh"}}}"#,
            &[group],
        ).unwrap();
        assert_eq!(parsed[0].translated_text, "你好");
    }

    #[test]
    fn compact_hacker_news_metadata_is_not_a_literal_identifier() {
        let identifiers =
            literal_identifiers("1point by hamper653 | O minutes ago | hide | l past|1comment")
                .into_iter()
                .collect::<HashSet<_>>();

        assert_eq!(identifiers, HashSet::from(["hamper653".to_owned()]));
    }

    #[test]
    fn preserves_real_identifiers_without_protecting_natural_hyphenated_words() {
        let identifiers =
            literal_identifiers("AIMS-Next GHOST M26-061 W3000 t5 range-based adult-size")
                .into_iter()
                .collect::<HashSet<_>>();

        assert!(identifiers.contains("AIMS-Next"));
        assert!(identifiers.contains("GHOST"));
        assert!(identifiers.contains("M26-061"));
        assert!(identifiers.contains("W3000"));
        assert!(identifiers.contains("t5"));
        assert!(!identifiers.contains("range-based"));
        assert!(!identifiers.contains("adult-size"));
    }

    #[test]
    fn strips_presentation_quotes_from_defined_acronyms() {
        assert_eq!(
            literal_identifiers(
                "California Consumer Privacy Act, as amended from time to time,\"CCPA\"."
            ),
            vec!["CCPA".to_owned()]
        );
    }

    #[test]
    fn all_caps_natural_language_headings_are_not_literal_identifiers() {
        assert!(literal_identifiers("PERSONAL INFORMATION WE").is_empty());
        assert!(literal_identifiers("COLLECT").is_empty());
    }

    #[test]
    fn quoted_ocr_word_digit_join_is_not_a_literal_identifier() {
        assert!(literal_identifiers("It has been\"1found in the archive").is_empty());
    }

    #[test]
    fn parser_accepts_natural_translation_of_compact_hacker_news_metadata() {
        let group: TranslationGroup = serde_json::from_value(json!({
            "groupId":"server-v4-4-c1a64c0825e4bd41", "role":"BODY",
            "translationUnit":"GROUP",
            "sourceText":"1point by hamper653 | O minutes ago | hide | l past|1comment",
            "memberRegionIds":[], "readingOrder":0, "groupingConfidence":1.0,
            "bounds":{"left":0,"top":0,"right":1,"bottom":1}
        }))
        .unwrap();
        let parsed = parse_translation_content(
            r#"{"translations":[{"groupId":"server-v4-4-c1a64c0825e4bd41","translatedText":"hamper653 发布于 0 分钟前 | 隐藏 | 历史 | 1 条评论","detectedSourceLanguage":"en","targetLanguage":"zh"}]}"#,
            &[group],
        )
        .unwrap();

        assert!(parsed[0].translated_text.contains("hamper653"));
    }
}
