use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::{
    contract::{SemanticTranslationRequest, TranslationGroup, resolved_render_slots},
    error::CoreError,
};

pub const PROMPT_VERSION: &str = "semantic-translation-core-v1-regions-first";

pub const SYSTEM_PROMPT: &str = r#"You are a professional screen OCR translation engine.
The input is one visible screen reconstructed from OCR geometry. Translate each translateGroups item independently and completely, while using document context and neighboring geometry only to disambiguate meaning.
Treat newline-separated OCR lines inside sourceText as one semantic block. Never imitate OCR line breaks, split a block back into lines, merge keys, borrow text from another key, summarize, or add notes.
Preserve URLs, identifiers, names, brands, numbers, dates, units, and currencies. Every requiredLiteralIdentifiers item must remain visible verbatim and in the same semantic role.
For AUTO_BIDIRECTIONAL translate Chinese natural language to English and non-Chinese natural language to Chinese.
Return only the strict JSON object requested by response_format. Every input groupId must occur exactly once and every translatedText must be non-empty."#;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelTranslation {
    pub group_id: String,
    pub translated_text: String,
    pub detected_source_language: String,
    pub target_language: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelPrompt {
    pub system: String,
    pub user: String,
    pub response_format: Value,
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
    let width = request.viewport.width as f32;
    let height = request.viewport.height as f32;
    let translate_groups = groups.iter().map(|group| {
        let members = group.member_region_ids.iter().filter_map(|id| regions.get(id.as_str()).copied()).collect::<Vec<_>>();
        let slots = resolved_render_slots(group, &members);
        json!({
            "groupId": group.group_id,
            "role": group.role,
            "sourceText": group.source_text,
            "sourceLanguage": members.first().and_then(|r| r.source_language.as_deref()).unwrap_or("auto"),
            "targetLanguage": members.first().and_then(|r| r.target_language.as_deref()).unwrap_or(request.translation.target_language.as_str()),
            "requiredLiteralIdentifiers": literal_identifiers(&group.source_text),
            "readingOrder": group.reading_order,
            "normalizedBounds": [group.bounds.left as f32 / width, group.bounds.top as f32 / height, group.bounds.right as f32 / width, group.bounds.bottom as f32 / height],
            "layoutShape": group.layout_shape,
            "renderSlots": slots.iter().map(|slot| [slot.left as f32 / width, slot.top as f32 / height, slot.right as f32 / width, slot.bottom as f32 / height]).collect::<Vec<_>>(),
            "regionLines": members.iter().map(|region| json!({
                "text": region.text,
                "readingOrder": region.reading_order,
                "normalizedBounds": [region.bounds.left as f32 / width, region.bounds.top as f32 / height, region.bounds.right as f32 / width, region.bounds.bottom as f32 / height]
            })).collect::<Vec<_>>()
        })
    }).collect::<Vec<_>>();
    let user = serde_json::to_string(&json!({
        "task": "Translate every translateGroups entry as one complete semantic unit. Geometry is context; the server creates layout.",
        "scene": request.scene,
        "translationMode": request.translation.mode,
        "documentContext": request.translation.use_document_context.then_some(request.document_context.text.as_str()),
        "viewport": {"width": request.viewport.width, "height": request.viewport.height},
        "translateGroups": translate_groups
    }))?;
    Ok(ModelPrompt {
        system: SYSTEM_PROMPT.to_owned(),
        user,
        response_format: response_format(groups),
    })
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

pub fn parse_translation_content(
    raw: &str,
    groups: &[TranslationGroup],
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
    let map = value
        .get("translations")
        .and_then(Value::as_object)
        .ok_or_else(|| CoreError::model("AI response must contain translations"))?;
    let expected = groups
        .iter()
        .map(|g| g.group_id.as_str())
        .collect::<HashSet<_>>();
    if map.len() != expected.len() || map.keys().any(|id| !expected.contains(id.as_str())) {
        return Err(CoreError::model(
            "AI response group IDs do not exactly match the request",
        ));
    }
    groups
        .iter()
        .map(|group| {
            let item = map
                .get(&group.group_id)
                .and_then(Value::as_object)
                .ok_or_else(|| CoreError::model(format!("AI omitted group {}", group.group_id)))?;
            let translated = item
                .get("translatedText")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|v| !v.is_empty())
                .ok_or_else(|| {
                    CoreError::model(format!(
                        "AI returned empty text for group {}",
                        group.group_id
                    ))
                })?;
            for literal in literal_identifiers(&group.source_text) {
                if !translated.contains(&literal) {
                    return Err(CoreError::model(format!(
                        "AI translation lost identifier {literal} for group {}",
                        group.group_id
                    )));
                }
            }
            Ok(ModelTranslation {
                group_id: group.group_id.clone(),
                translated_text: translated.to_owned(),
                detected_source_language: item
                    .get("detectedSourceLanguage")
                    .and_then(Value::as_str)
                    .unwrap_or("auto")
                    .to_owned(),
                target_language: item
                    .get("targetLanguage")
                    .and_then(Value::as_str)
                    .unwrap_or(requested_target(group))
                    .to_owned(),
            })
        })
        .collect()
}

fn requested_target(_group: &TranslationGroup) -> &'static str {
    "auto"
}

fn response_format(groups: &[TranslationGroup]) -> Value {
    let properties = groups
        .iter()
        .map(|group| {
            (
                group.group_id.clone(),
                json!({
                    "type":"object",
                    "properties": {
                        "translatedText":{"type":"string","minLength":1},
                        "detectedSourceLanguage":{"type":"string","minLength":1},
                        "targetLanguage":{"type":"string","minLength":1}
                    },
                    "required":["translatedText","detectedSourceLanguage","targetLanguage"],
                    "additionalProperties":false
                }),
            )
        })
        .collect::<serde_json::Map<_, _>>();
    let required = groups
        .iter()
        .map(|g| g.group_id.as_str())
        .collect::<Vec<_>>();
    json!({"type":"json_schema","json_schema":{"name":"semantic_translation","strict":true,"schema":{
        "type":"object","properties":{"translations":{"type":"object","properties":properties,"required":required,"additionalProperties":false}},"required":["translations"],"additionalProperties":false
    }}})
}

fn literal_identifiers(text: &str) -> Vec<String> {
    text.split(|c: char| {
        c.is_whitespace() || matches!(c, ',' | '.' | ';' | ':' | '(' | ')' | '[' | ']')
    })
    .map(str::trim)
    .filter(|token| {
        token.len() >= 2
            && (token.contains("http")
                || token.contains('.')
                || token.contains('-')
                || token.chars().any(|c| c.is_ascii_digit())
                || token.chars().filter(|c| c.is_ascii_alphabetic()).count() >= 2
                    && token
                        .chars()
                        .filter(|c| c.is_ascii_alphabetic())
                        .all(|c| c.is_ascii_uppercase()))
    })
    .map(ToOwned::to_owned)
    .collect::<HashSet<_>>()
    .into_iter()
    .collect()
}
