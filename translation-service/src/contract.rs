use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const MAX_REGIONS_PER_BATCH: usize = 64;
pub const MAX_CODE_POINTS_PER_REGION: usize = 2_000;
pub const MAX_CODE_POINTS_PER_BATCH: usize = 16_000;
pub const MAX_REQUEST_BYTES: usize = 262_144;
pub const MAX_OCR_REQUEST_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_OCR_IMAGE_BASE64_BYTES: usize = 7 * 1024 * 1024;

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V1BatchRequest {
    pub schema_version: u16,
    pub request_id: String,
    pub generation: i64,
    pub scene: String,
    pub translation: V1TranslationOptions,
    pub regions: Vec<V1Region>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V1TranslationOptions {
    pub mode: String,
    pub source_language: Option<String>,
    pub target_language: Option<String>,
    #[serde(default = "default_true")]
    pub preserve_identifiers: bool,
    #[serde(default)]
    pub use_context: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V1Region {
    pub region_id: String,
    pub text: String,
    pub source_language: Option<String>,
    pub target_language: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V1BatchResponse {
    pub request_id: String,
    pub results: Vec<V1RegionResult>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V1RegionResult {
    pub region_id: String,
    pub status: RegionStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub translated_text: Option<String>,
    pub provider: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detected_source_language: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_language: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<RegionError>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrTranslationRequest {
    pub schema_version: u16,
    pub request_id: String,
    pub session_id: String,
    pub generation: i64,
    pub image: OcrImage,
    pub translation: OcrTranslationOptions,
    #[serde(default)]
    pub ocr: OcrOptions,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrImage {
    pub data: String,
    pub media_type: String,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrTranslationOptions {
    pub mode: String,
    #[serde(default = "default_true")]
    pub preserve_identifiers: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrOptions {
    pub text_det_limit_side_len: u32,
    pub text_rec_score_thresh: f32,
}

impl Default for OcrOptions {
    fn default() -> Self {
        Self {
            text_det_limit_side_len: 1_280,
            text_rec_score_thresh: 0.35,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrTranslationResponse {
    pub schema_version: u16,
    pub request_id: String,
    pub session_id: String,
    pub generation: i64,
    pub source_width: u32,
    pub source_height: u32,
    pub status: BatchStatus,
    pub recognized_count: usize,
    pub regions: Vec<OcrTranslationRegion>,
    pub timing: OcrTranslationTiming,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrTranslationRegion {
    pub region_id: String,
    pub status: RegionStatus,
    pub source_text: String,
    pub translated_text: Option<String>,
    pub bounds: Bounds,
    pub confidence: f32,
    pub provider: String,
    pub detected_source_language: Option<String>,
    pub target_language: Option<String>,
    pub error: Option<RegionError>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrTranslationTiming {
    pub ocr_ms: u64,
    pub translation_ms: u64,
    pub total_ms: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2BatchRequest {
    pub schema_version: u16,
    pub request_id: String,
    pub session_id: String,
    pub generation: i64,
    pub translation_revision: i64,
    pub batch_part_index: u32,
    pub batch_part_count: u32,
    pub scene: String,
    pub capture: V2Capture,
    pub translation: V2TranslationOptions,
    pub regions: Vec<V2Region>,
    pub client: V2Client,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2Capture {
    pub source_width: u32,
    pub source_height: u32,
    pub orientation: String,
    pub captured_at_epoch_ms: i64,
    pub segmentation: String,
    pub content_shift_y: Option<i32>,
    pub registration_confidence: Option<f32>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2TranslationOptions {
    pub source: V2SourceSelection,
    pub target: V2TargetSelection,
    pub mixed_language_policy: String,
    #[serde(default = "default_true")]
    pub preserve_identifiers: bool,
    #[serde(default)]
    pub use_context: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2SourceSelection {
    pub mode: String,
    pub language_tag: Option<String>,
    pub fallback_language_tag: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2TargetSelection {
    pub language_tag: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2Region {
    pub region_id: String,
    pub source_revision: i64,
    pub role: RegionRole,
    pub text: String,
    pub script: String,
    pub reading_order: u32,
    pub bounds: Bounds,
    pub ocr: Value,
    pub language: Option<V2RegionLanguage>,
    pub context_group_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Bounds {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2RegionLanguage {
    pub detected_tag: String,
    pub confidence: Option<f32>,
    pub detection_source: String,
    #[serde(default)]
    pub candidates: Vec<Value>,
    #[serde(default)]
    pub user_override: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2Client {
    pub platform: String,
    pub app_version: String,
    pub locale: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2BatchResponse {
    pub schema_version: u16,
    pub request_id: String,
    pub session_id: String,
    pub generation: i64,
    pub translation_revision: i64,
    pub batch_part_index: u32,
    pub status: BatchStatus,
    pub results: Vec<V2RegionResult>,
    pub timing: Timing,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct V2RegionResult {
    pub region_id: String,
    pub source_revision: i64,
    pub status: RegionStatus,
    pub effective_source_language: Option<String>,
    pub detected_source_language: Option<String>,
    pub target_language: String,
    pub translated_text: Option<String>,
    pub provider: String,
    pub model_version: String,
    pub cached: bool,
    pub error: Option<RegionError>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RegionRole {
    Translate,
    ContextOnly,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RegionStatus {
    Translated,
    Preserved,
    Failed,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BatchStatus {
    Completed,
    Partial,
    Failed,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RegionError {
    pub code: String,
    pub message: String,
    pub retryable: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Timing {
    pub queue_ms: u64,
    pub translation_ms: u64,
    pub total_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorEnvelope {
    pub schema_version: u16,
    pub request_id: Option<String>,
    pub error: RegionError,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CapabilitiesResponse {
    pub schema_version: u16,
    pub supports_auto_detection: bool,
    pub supports_per_region_language: bool,
    pub supports_context_only_regions: bool,
    pub supported_languages: Vec<&'static str>,
    pub supported_language_pairs: Vec<LanguagePair>,
    pub limits: CapabilityLimits,
    pub server: ServerCapability,
}

#[derive(Debug, Clone, Serialize)]
pub struct LanguagePair {
    pub source: &'static str,
    pub target: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CapabilityLimits {
    pub max_regions_per_batch: usize,
    pub max_code_points_per_region: usize,
    pub max_code_points_per_batch: usize,
    pub max_request_bytes: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerCapability {
    pub api_version: &'static str,
    pub model_version: String,
}

pub const SUPPORTED_LANGUAGES: &[&str] = &[
    "zh-Hans", "en", "fr", "pt", "es", "ja", "tr", "ru", "ar", "ko", "th", "it", "de", "vi", "ms",
    "id", "tl", "hi", "zh-Hant", "pl", "cs", "nl", "km", "my", "fa", "gu", "ur", "te", "mr", "he",
    "bn", "ta", "uk", "bo", "kk", "mn", "ug", "yue",
];

pub fn normalized_language_tag(tag: &str) -> Option<&'static str> {
    let normalized = match tag.trim().to_ascii_lowercase().as_str() {
        "zh" | "zh-cn" | "zh-hans" => "zh-Hans",
        "zh-tw" | "zh-hk" | "zh-hant" => "zh-Hant",
        "fil" => "tl",
        value => SUPPORTED_LANGUAGES
            .iter()
            .copied()
            .find(|candidate| candidate.to_ascii_lowercase() == value)?,
    };
    Some(normalized)
}

pub fn language_name(tag: &str) -> Option<&'static str> {
    Some(match normalized_language_tag(tag)? {
        "zh-Hans" => "Chinese",
        "zh-Hant" => "Traditional Chinese",
        "en" => "English",
        "fr" => "French",
        "pt" => "Portuguese",
        "es" => "Spanish",
        "ja" => "Japanese",
        "tr" => "Turkish",
        "ru" => "Russian",
        "ar" => "Arabic",
        "ko" => "Korean",
        "th" => "Thai",
        "it" => "Italian",
        "de" => "German",
        "vi" => "Vietnamese",
        "ms" => "Malay",
        "id" => "Indonesian",
        "tl" => "Filipino",
        "hi" => "Hindi",
        "pl" => "Polish",
        "cs" => "Czech",
        "nl" => "Dutch",
        "km" => "Khmer",
        "my" => "Burmese",
        "fa" => "Persian",
        "gu" => "Gujarati",
        "ur" => "Urdu",
        "te" => "Telugu",
        "mr" => "Marathi",
        "he" => "Hebrew",
        "bn" => "Bengali",
        "ta" => "Tamil",
        "uk" => "Ukrainian",
        "bo" => "Tibetan",
        "kk" => "Kazakh",
        "mn" => "Mongolian",
        "ug" => "Uyghur",
        "yue" => "Cantonese",
        _ => return None,
    })
}

pub fn chinese_language_name(tag: &str) -> Option<&'static str> {
    Some(match normalized_language_tag(tag)? {
        "zh-Hans" => "中文",
        "zh-Hant" => "繁体中文",
        "en" => "英语",
        "fr" => "法语",
        "pt" => "葡萄牙语",
        "es" => "西班牙语",
        "ja" => "日语",
        "tr" => "土耳其语",
        "ru" => "俄语",
        "ar" => "阿拉伯语",
        "ko" => "韩语",
        "th" => "泰语",
        "it" => "意大利语",
        "de" => "德语",
        "vi" => "越南语",
        "ms" => "马来语",
        "id" => "印尼语",
        "tl" => "菲律宾语",
        "hi" => "印地语",
        "pl" => "波兰语",
        "cs" => "捷克语",
        "nl" => "荷兰语",
        "km" => "高棉语",
        "my" => "缅甸语",
        "fa" => "波斯语",
        "gu" => "古吉拉特语",
        "ur" => "乌尔都语",
        "te" => "泰卢固语",
        "mr" => "马拉地语",
        "he" => "希伯来语",
        "bn" => "孟加拉语",
        "ta" => "泰米尔语",
        "uk" => "乌克兰语",
        "bo" => "藏语",
        "kk" => "哈萨克语",
        "mn" => "蒙古语",
        "ug" => "维吾尔语",
        "yue" => "粤语",
        _ => return None,
    })
}

pub fn capabilities(model_version: String) -> CapabilitiesResponse {
    let supported_language_pairs = SUPPORTED_LANGUAGES
        .iter()
        .flat_map(|source| {
            SUPPORTED_LANGUAGES
                .iter()
                .filter(move |target| target != &source)
                .map(move |target| LanguagePair { source, target })
        })
        .collect();
    CapabilitiesResponse {
        schema_version: 2,
        supports_auto_detection: true,
        supports_per_region_language: true,
        supports_context_only_regions: true,
        supported_languages: SUPPORTED_LANGUAGES.to_vec(),
        supported_language_pairs,
        limits: CapabilityLimits {
            max_regions_per_batch: MAX_REGIONS_PER_BATCH,
            max_code_points_per_region: MAX_CODE_POINTS_PER_REGION,
            max_code_points_per_batch: MAX_CODE_POINTS_PER_BATCH,
            max_request_bytes: MAX_REQUEST_BYTES,
        },
        server: ServerCapability {
            api_version: "2.0",
            model_version,
        },
    }
}

fn default_true() -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_android_chinese_tags() {
        assert_eq!(normalized_language_tag("zh"), Some("zh-Hans"));
        assert_eq!(normalized_language_tag("zh-TW"), Some("zh-Hant"));
        assert_eq!(normalized_language_tag("unknown"), None);
        assert_eq!(chinese_language_name("zh-TW"), Some("繁体中文"));
    }

    #[test]
    fn capabilities_do_not_advertise_same_language_pairs() {
        let response = capabilities("test-model".to_owned());
        assert!(
            response
                .supported_language_pairs
                .iter()
                .all(|pair| pair.source != pair.target)
        );
    }
}
