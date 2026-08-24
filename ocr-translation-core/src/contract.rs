use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};

use crate::error::CoreError as AppError;

pub const SCHEMA_VERSION: u32 = 2;
pub const LAYOUT_PLAN_SCHEMA_VERSION: u32 = 3;
pub const REGIONS_FIRST_SCHEMA_VERSION: u32 = 4;
const MAX_GROUPS: usize = 100;
const MAX_REGIONS: usize = 300;
const MAX_TOTAL_CHARS: usize = 30_000;
const MAX_CONTEXT_CHARS: usize = 12_000;
const MAX_VIEWPORT_EDGE: i32 = 32_768;
const MAX_DEBUG_CAPTURE_BASE64_CHARS: usize = 8 * 1024 * 1024;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SemanticTranslationRequest {
    pub schema_version: u32,
    pub request_id: String,
    pub session_id: String,
    pub generation: i64,
    pub translation_revision: i64,
    pub scene: String,
    pub viewport: Viewport,
    pub translation: TranslationOptions,
    pub document_context: DocumentContext,
    #[serde(default)]
    pub groups: Vec<TranslationGroup>,
    pub regions: Vec<OcrRegion>,
    #[serde(default)]
    pub debug_capture: Option<DebugCapture>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DebugCapture {
    pub mime_type: String,
    #[serde(skip_serializing)]
    pub data_base64: String,
    pub pixel_width: i32,
    pub pixel_height: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Viewport {
    pub width: i32,
    pub height: i32,
    #[serde(default)]
    pub rotation_degrees: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TranslationOptions {
    pub mode: String,
    #[serde(default = "auto_language")]
    pub source_language: String,
    #[serde(default = "auto_language")]
    pub target_language: String,
    #[serde(default = "default_true")]
    pub preserve_identifiers: bool,
    #[serde(default = "default_true")]
    pub use_document_context: bool,
    #[serde(default)]
    pub direct_structured_output: bool,
    #[serde(default = "default_true")]
    pub compact_provider_prompt: bool,
    #[serde(default)]
    pub thinking_control_mode: Option<ThinkingControlMode>,
    #[serde(default)]
    pub thinking_level: Option<String>,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ThinkingControlMode {
    None,
    ReasoningEffort,
    ThinkingLevel,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DocumentContext {
    pub text: String,
    #[serde(default = "auto_language")]
    pub source_language: String,
    pub reading_order_region_ids: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TranslationGroup {
    pub group_id: String,
    pub role: String,
    pub translation_unit: String,
    pub source_text: String,
    pub member_region_ids: Vec<String>,
    pub reading_order: i32,
    pub grouping_confidence: f32,
    #[serde(default)]
    pub grouping_evidence: Vec<String>,
    #[serde(default)]
    pub source_line_count: Option<i32>,
    pub bounds: Bounds,
    #[serde(default)]
    pub render_slots: Vec<Bounds>,
    #[serde(default = "rect_layout_shape")]
    pub layout_shape: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrRegion {
    pub region_id: String,
    #[serde(default)]
    pub group_id: String,
    pub source_revision: i64,
    pub text: String,
    #[serde(default)]
    pub raw_text: Option<String>,
    #[serde(default)]
    pub corrections: Vec<TextCorrection>,
    #[serde(default)]
    pub source_language: Option<String>,
    #[serde(default)]
    pub target_language: Option<String>,
    pub reading_order: i32,
    pub block_id: Option<String>,
    pub line_index: Option<i32>,
    pub confidence: f32,
    pub bounds: Bounds,
    #[serde(default)]
    pub component_bounds: Vec<Bounds>,
    #[serde(default)]
    pub estimated_text_height_px: Option<f32>,
    #[serde(default)]
    pub typography_confidence: f32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TextCorrection {
    pub code: String,
    pub original: String,
    pub replacement: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Bounds {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SemanticTranslationResponse {
    pub schema_version: u32,
    pub request_id: String,
    pub session_id: String,
    pub generation: i64,
    pub translation_revision: i64,
    pub provider: String,
    pub model_version: String,
    pub prompt_version: String,
    pub results: Vec<GroupTranslationResult>,
    pub document_plan: crate::planning::DocumentPlan,
    pub metrics: ResponseMetrics,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GroupTranslationResult {
    pub group_id: String,
    pub source_group_ids: Vec<String>,
    pub role: String,
    pub grouping_confidence: f32,
    pub status: String,
    pub render_mode: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub translated_text: Option<String>,
    pub member_region_ids: Vec<String>,
    pub detected_source_language: String,
    pub target_language: String,
    pub anchor_bounds: Bounds,
    pub layout_hint: LayoutHint,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ResultError>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LayoutHint {
    pub preferred_max_lines: i32,
    pub minimum_text_scale: f32,
    pub maximum_text_scale: f32,
    pub line_spacing_multiplier: f32,
    pub alignment: String,
    pub overflow_strategy: String,
    pub allow_more: bool,
    pub source_line_count: i32,
    pub layout_shape: String,
    pub render_slots: Vec<Bounds>,
    pub source_cover_slots: Vec<Bounds>,
    pub vertical_alignment: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResultError {
    pub code: String,
    pub message: String,
    pub retryable: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResponseMetrics {
    pub group_count: usize,
    pub translated_group_count: usize,
    pub preserved_group_count: usize,
    pub failed_group_count: usize,
    pub total_ms: u64,
}

impl SemanticTranslationRequest {
    pub fn validate(&self) -> Result<(), AppError> {
        self.validate_schema(SCHEMA_VERSION)
    }

    pub fn validate_schema(&self, expected_schema: u32) -> Result<(), AppError> {
        if self.schema_version != expected_schema {
            return Err(AppError::invalid(format!(
                "schemaVersion must be {expected_schema}"
            )));
        }
        require_non_empty("requestId", &self.request_id)?;
        require_non_empty("sessionId", &self.session_id)?;
        require_non_empty("scene", &self.scene)?;
        if self.generation < 0 || self.translation_revision < 0 {
            return Err(AppError::invalid(
                "generation and translationRevision must be non-negative",
            ));
        }
        self.viewport.validate()?;
        if let Some(level) = self.translation.thinking_level.as_deref()
            && !matches!(level, "minimal" | "low" | "medium" | "high" | "none")
        {
            return Err(AppError::invalid(
                "translation.thinkingLevel must be minimal, low, medium, or high",
            ));
        }
        if self.translation.thinking_control_mode == Some(ThinkingControlMode::ThinkingLevel)
            && self.translation.thinking_level.as_deref() == Some("none")
        {
            return Err(AppError::invalid("thinkingLevel does not support none"));
        }
        if let Some(capture) = &self.debug_capture {
            if self.scene != "LIVE_SCREEN" {
                return Err(AppError::invalid(
                    "debugCapture is only accepted for LIVE_SCREEN requests",
                ));
            }
            if !matches!(capture.mime_type.as_str(), "image/jpeg" | "image/png") {
                return Err(AppError::invalid(
                    "debugCapture.mimeType must be image/jpeg or image/png",
                ));
            }
            if capture.data_base64.is_empty()
                || capture.data_base64.len() > MAX_DEBUG_CAPTURE_BASE64_CHARS
            {
                return Err(AppError::invalid(
                    "debugCapture.dataBase64 must contain at most 8 MiB",
                ));
            }
            if capture.pixel_width <= 0
                || capture.pixel_height <= 0
                || capture.pixel_width > MAX_VIEWPORT_EDGE
                || capture.pixel_height > MAX_VIEWPORT_EDGE
            {
                return Err(AppError::invalid(
                    "debugCapture dimensions must be between 1 and 32768",
                ));
            }
        }
        if (expected_schema != REGIONS_FIRST_SCHEMA_VERSION && self.groups.is_empty())
            || self.groups.len() > MAX_GROUPS
        {
            return Err(AppError::invalid(
                if expected_schema == REGIONS_FIRST_SCHEMA_VERSION {
                    "groups must contain at most 100 advisory items"
                } else {
                    "groups must contain between 1 and 100 items"
                },
            ));
        }
        if self.regions.is_empty() || self.regions.len() > MAX_REGIONS {
            return Err(AppError::invalid(
                "regions must contain between 1 and 300 items",
            ));
        }
        if self.document_context.text.chars().count() > MAX_CONTEXT_CHARS {
            return Err(AppError::invalid(
                "documentContext.text exceeds 12000 characters",
            ));
        }

        let mut total_chars = 0usize;
        let mut regions = HashMap::with_capacity(self.regions.len());
        for region in &self.regions {
            require_non_empty("regions[].regionId", &region.region_id)?;
            if expected_schema != REGIONS_FIRST_SCHEMA_VERSION {
                require_non_empty("regions[].groupId", &region.group_id)?;
            }
            require_non_empty("regions[].text", &region.text)?;
            if region.source_revision < 0 || region.reading_order < 0 {
                return Err(AppError::invalid(
                    "region sourceRevision and readingOrder must be non-negative",
                ));
            }
            if !region.confidence.is_finite() || !(0.0..=1.0).contains(&region.confidence) {
                return Err(AppError::invalid(
                    "region confidence must be between 0 and 1",
                ));
            }
            region.bounds.validate(&self.viewport)?;
            for component in &region.component_bounds {
                component.validate(&self.viewport)?;
                if !region.bounds.contains(component) {
                    return Err(AppError::invalid(
                        "region componentBounds must stay inside region bounds",
                    ));
                }
            }
            if regions.insert(region.region_id.as_str(), region).is_some() {
                return Err(AppError::invalid("regionId must be unique"));
            }
            total_chars += region.text.chars().count();
        }
        if total_chars > MAX_TOTAL_CHARS {
            return Err(AppError::invalid(
                "all region text exceeds 30000 characters",
            ));
        }

        let mut group_ids = HashSet::with_capacity(self.groups.len());
        let mut assigned_regions = HashSet::with_capacity(self.regions.len());
        for group in &self.groups {
            require_non_empty("groups[].groupId", &group.group_id)?;
            require_non_empty("groups[].sourceText", &group.source_text)?;
            if !group_ids.insert(group.group_id.as_str()) {
                return Err(AppError::invalid("groupId must be unique"));
            }
            if group.member_region_ids.is_empty() || group.reading_order < 0 {
                return Err(AppError::invalid(
                    "each group needs members and a non-negative readingOrder",
                ));
            }
            if !group.grouping_confidence.is_finite()
                || !(0.0..=1.0).contains(&group.grouping_confidence)
            {
                return Err(AppError::invalid(
                    "groupingConfidence must be between 0 and 1",
                ));
            }
            if !matches!(group.translation_unit.as_str(), "GROUP" | "PRESERVED") {
                return Err(AppError::invalid(
                    "translationUnit must be GROUP or PRESERVED",
                ));
            }
            group.bounds.validate(&self.viewport)?;
            if !matches!(group.layout_shape.as_str(), "RECT" | "FLOW_SLOTS") {
                return Err(AppError::invalid("layoutShape must be RECT or FLOW_SLOTS"));
            }
            for slot in &group.render_slots {
                slot.validate(&self.viewport)?;
                if !group.bounds.contains(slot) {
                    return Err(AppError::invalid(
                        "renderSlots must stay inside their group bounds",
                    ));
                }
            }
            let mut member_text = Vec::with_capacity(group.member_region_ids.len());
            let mut local_members = HashSet::new();
            for member_id in &group.member_region_ids {
                if !local_members.insert(member_id) {
                    return Err(AppError::invalid(
                        "group contains duplicate memberRegionIds",
                    ));
                }
                let region = regions
                    .get(member_id.as_str())
                    .ok_or_else(|| AppError::invalid("group references an unknown regionId"))?;
                if !region.group_id.is_empty() && region.group_id != group.group_id {
                    return Err(AppError::invalid("region groupId does not match its group"));
                }
                if !assigned_regions.insert(member_id.as_str()) {
                    return Err(AppError::invalid(
                        "a region cannot belong to multiple groups",
                    ));
                }
                member_text.push(region.text.trim());
            }
            if normalize_text(&group.source_text) != normalize_text(&member_text.join("\n")) {
                return Err(AppError::invalid(
                    "group sourceText must match its member region text",
                ));
            }
        }
        if expected_schema != REGIONS_FIRST_SCHEMA_VERSION
            && assigned_regions.len() != self.regions.len()
        {
            return Err(AppError::invalid(
                "every region must belong to exactly one group",
            ));
        }

        let expected_order = self
            .regions
            .iter()
            .map(|region| region.region_id.as_str())
            .collect::<HashSet<_>>();
        let actual_order = self
            .document_context
            .reading_order_region_ids
            .iter()
            .map(String::as_str)
            .collect::<HashSet<_>>();
        if expected_order != actual_order
            || actual_order.len() != self.document_context.reading_order_region_ids.len()
        {
            return Err(AppError::invalid(
                "documentContext.readingOrderRegionIds must contain every region exactly once",
            ));
        }
        Ok(())
    }
}

impl Viewport {
    fn validate(&self) -> Result<(), AppError> {
        if self.width <= 0
            || self.height <= 0
            || self.width > MAX_VIEWPORT_EDGE
            || self.height > MAX_VIEWPORT_EDGE
        {
            return Err(AppError::invalid(
                "viewport dimensions must be between 1 and 32768",
            ));
        }
        if !matches!(self.rotation_degrees, 0 | 90 | 180 | 270) {
            return Err(AppError::invalid(
                "viewport rotationDegrees must be 0, 90, 180, or 270",
            ));
        }
        Ok(())
    }
}

impl Bounds {
    fn validate(&self, viewport: &Viewport) -> Result<(), AppError> {
        if self.left < 0
            || self.top < 0
            || self.right <= self.left
            || self.bottom <= self.top
            || self.right > viewport.width
            || self.bottom > viewport.height
        {
            return Err(AppError::invalid(
                "bounds must be non-empty and inside viewport",
            ));
        }
        Ok(())
    }

    pub(crate) fn width(&self) -> i32 {
        self.right - self.left
    }

    pub(crate) fn height(&self) -> i32 {
        self.bottom - self.top
    }

    pub(crate) fn contains(&self, other: &Self) -> bool {
        other.left >= self.left
            && other.top >= self.top
            && other.right <= self.right
            && other.bottom <= self.bottom
    }

    pub(crate) fn union(&self, other: &Self) -> Self {
        Self {
            left: self.left.min(other.left),
            top: self.top.min(other.top),
            right: self.right.max(other.right),
            bottom: self.bottom.max(other.bottom),
        }
    }

    pub(crate) fn horizontal_overlap(&self, other: &Self) -> i32 {
        self.right.min(other.right) - self.left.max(other.left)
    }
}

pub fn layout_hint(
    group: &TranslationGroup,
    translated: &str,
    render_slots: Vec<Bounds>,
    source_cover_slots: Vec<Bounds>,
) -> LayoutHint {
    let text_lines = group
        .source_text
        .lines()
        .filter(|line| !line.trim().is_empty())
        .count()
        .max(1) as i32;
    let source_lines = group
        .source_line_count
        .unwrap_or_default()
        .max(group.member_region_ids.len().max(1) as i32)
        .max(text_lines);
    let expansion = visual_width_units(translated) / visual_width_units(&group.source_text);
    let preferred_max_lines = if expansion <= 1.15 {
        source_lines
    } else if expansion <= 1.8 {
        source_lines + 1
    } else {
        source_lines + 2
    }
    .max(source_lines)
    .clamp(1, 24);
    let minimum_text_scale = if expansion <= 1.2 {
        0.86
    } else if expansion <= 1.8 {
        0.72
    } else {
        0.68
    };
    let alignment = if group.bounds.width() < group.bounds.height() * 3 && source_lines == 1 {
        "CENTER"
    } else {
        "START"
    };
    LayoutHint {
        preferred_max_lines,
        minimum_text_scale,
        maximum_text_scale: 1.0,
        // The contract must never ask a renderer to trade glyph safety for fit.
        line_spacing_multiplier: 1.0,
        alignment: alignment.to_owned(),
        overflow_strategy: if group.role == "BODY" && source_lines >= 4 {
            "REFLOW_THEN_SCALE_THEN_MORE".to_owned()
        } else {
            "REFLOW_THEN_SCALE".to_owned()
        },
        allow_more: group.role == "BODY" && source_lines >= 4,
        source_line_count: source_lines,
        layout_shape: if render_slots.len() > 1 {
            "FLOW_SLOTS".to_owned()
        } else {
            "RECT".to_owned()
        },
        render_slots,
        source_cover_slots,
        vertical_alignment: if group.role == "BODY" && source_lines >= 3 {
            "TOP"
        } else {
            "AUTO"
        }
        .to_owned(),
    }
}

fn visual_width_units(text: &str) -> f32 {
    text.chars()
        .map(|character| {
            if character.is_whitespace() {
                0.3
            } else if is_full_width_character(character) {
                1.0
            } else if character.is_ascii_punctuation() {
                0.45
            } else if character.is_ascii_digit() {
                0.58
            } else {
                0.56
            }
        })
        .sum::<f32>()
        .max(1.0)
}

fn is_full_width_character(character: char) -> bool {
    matches!(
        character as u32,
        0x2E80..=0x9FFF | 0xAC00..=0xD7AF | 0xF900..=0xFAFF | 0xFF01..=0xFF60
    )
}

pub fn source_cover_slots(group: &TranslationGroup, regions: &[&OcrRegion]) -> Vec<Bounds> {
    let slots = regions
        .iter()
        .flat_map(|region| {
            if region.component_bounds.is_empty() {
                vec![region.bounds.clone()]
            } else {
                region.component_bounds.clone()
            }
        })
        .collect::<Vec<_>>();
    if slots.is_empty() {
        vec![group.bounds.clone()]
    } else {
        slots
    }
}

pub fn resolved_render_slots(group: &TranslationGroup, regions: &[&OcrRegion]) -> Vec<Bounds> {
    if !group.render_slots.is_empty() {
        return group.render_slots.clone();
    }
    let mut ordered = regions
        .iter()
        .map(|region| region.bounds.clone())
        .collect::<Vec<_>>();
    ordered.sort_by_key(|bounds| (bounds.top, bounds.left));
    if ordered.is_empty() {
        return vec![group.bounds.clone()];
    }
    let mut heights = ordered.iter().map(Bounds::height).collect::<Vec<_>>();
    heights.sort_unstable();
    let median_height = heights[(heights.len() - 1) / 2].max(1);
    let lane_tolerance = median_height.max(6);
    let mut lanes = Vec::<Vec<Bounds>>::new();
    for bounds in ordered {
        let current_left = lanes.last().map(|lane| {
            let mut lefts = lane.iter().map(|item| item.left).collect::<Vec<_>>();
            lefts.sort_unstable();
            lefts[(lefts.len() - 1) / 2]
        });
        if current_left.is_none_or(|left| (bounds.left - left).abs() > lane_tolerance) {
            lanes.push(vec![bounds]);
        } else if let Some(lane) = lanes.last_mut() {
            lane.push(bounds);
        }
    }
    let right_tolerance = (median_height * 2).max(12);
    lanes
        .into_iter()
        .map(|lane| {
            let lane_right = lane
                .iter()
                .map(|item| item.right)
                .max()
                .unwrap_or(group.bounds.right);
            Bounds {
                left: lane
                    .iter()
                    .map(|item| item.left)
                    .min()
                    .unwrap_or(group.bounds.left),
                top: lane
                    .iter()
                    .map(|item| item.top)
                    .min()
                    .unwrap_or(group.bounds.top),
                right: if group.bounds.right - lane_right <= right_tolerance {
                    group.bounds.right
                } else {
                    lane_right
                },
                bottom: lane
                    .iter()
                    .map(|item| item.bottom)
                    .max()
                    .unwrap_or(group.bounds.bottom),
            }
        })
        .collect()
}

fn require_non_empty(field: &str, value: &str) -> Result<(), AppError> {
    if value.trim().is_empty() {
        Err(AppError::invalid(format!("{field} must not be empty")))
    } else {
        Ok(())
    }
}

fn normalize_text(value: &str) -> String {
    value.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn auto_language() -> String {
    "auto".to_owned()
}

fn default_true() -> bool {
    true
}

fn rect_layout_shape() -> String {
    "RECT".to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_hints_expand_line_budget_before_scaling() {
        let bounds = Bounds {
            left: 0,
            top: 0,
            right: 400,
            bottom: 80,
        };
        let group = TranslationGroup {
            group_id: "group".to_owned(),
            role: "BODY".to_owned(),
            translation_unit: "GROUP".to_owned(),
            source_text: "Save".to_owned(),
            member_region_ids: vec!["region".to_owned()],
            reading_order: 0,
            grouping_confidence: 1.0,
            grouping_evidence: vec![],
            source_line_count: None,
            bounds: bounds.clone(),
            render_slots: vec![bounds],
            layout_shape: "RECT".to_owned(),
        };
        let hint = layout_hint(
            &group,
            "保存当前修改并返回上一页",
            group.render_slots.clone(),
            group.render_slots.clone(),
        );
        assert!(hint.preferred_max_lines > 1);
        assert!(hint.minimum_text_scale < 0.8);
        assert_eq!(hint.vertical_alignment, "AUTO");
    }

    #[test]
    fn multi_line_body_defaults_to_top_alignment() {
        let bounds = Bounds {
            left: 0,
            top: 0,
            right: 400,
            bottom: 180,
        };
        let group = TranslationGroup {
            group_id: "body".to_owned(),
            role: "BODY".to_owned(),
            translation_unit: "GROUP".to_owned(),
            source_text: "line one\nline two\nline three".to_owned(),
            member_region_ids: vec!["r1".to_owned(), "r2".to_owned(), "r3".to_owned()],
            reading_order: 0,
            grouping_confidence: 1.0,
            grouping_evidence: vec![],
            source_line_count: Some(3),
            bounds: bounds.clone(),
            render_slots: vec![bounds.clone()],
            layout_shape: "RECT".to_owned(),
        };

        let hint = layout_hint(
            &group,
            "第一行\n第二行\n第三行",
            vec![bounds.clone()],
            vec![bounds],
        );

        assert_eq!(hint.vertical_alignment, "TOP");
        assert_eq!(hint.line_spacing_multiplier, 1.0);
    }

    #[test]
    fn layout_hints_account_for_full_width_translation_glyphs() {
        let bounds = Bounds {
            left: 0,
            top: 0,
            right: 320,
            bottom: 48,
        };
        let group = TranslationGroup {
            group_id: "group".to_owned(),
            role: "BODY".to_owned(),
            translation_unit: "GROUP".to_owned(),
            source_text: "silent struggle has been".to_owned(),
            member_region_ids: vec!["region".to_owned()],
            reading_order: 0,
            grouping_confidence: 1.0,
            grouping_evidence: vec![],
            source_line_count: Some(1),
            bounds: bounds.clone(),
            render_slots: vec![bounds.clone()],
            layout_shape: "RECT".to_owned(),
        };

        let hint = layout_hint(
            &group,
            "一场无声的斗争一直在进行着，它并非",
            vec![bounds.clone()],
            vec![bounds],
        );

        assert_eq!(hint.preferred_max_lines, 2);
        assert_eq!(hint.minimum_text_scale, 0.72);
    }

    #[test]
    fn reconstructs_three_render_slots_for_image_wrapped_text() {
        let group = TranslationGroup {
            group_id: "body".to_owned(),
            role: "BODY".to_owned(),
            translation_unit: "GROUP".to_owned(),
            source_text: "wrapped".to_owned(),
            member_region_ids: (0..6).map(|index| format!("r{index}")).collect(),
            reading_order: 0,
            grouping_confidence: 1.0,
            grouping_evidence: vec!["WRAPPED_FLOW".to_owned()],
            source_line_count: None,
            bounds: Bounds {
                left: 7,
                top: 461,
                right: 373,
                bottom: 642,
            },
            render_slots: vec![],
            layout_shape: "FLOW_SLOTS".to_owned(),
        };
        let regions = vec![
            region("r0", 178, 461, 355, 475),
            region("r1", 176, 497, 350, 511),
            region("r2", 131, 517, 373, 530),
            region("r3", 130, 554, 347, 569),
            region("r4", 8, 573, 359, 587),
            region("r5", 7, 629, 65, 642),
        ];
        let references = regions.iter().collect::<Vec<_>>();

        let slots = resolved_render_slots(&group, &references);

        assert_eq!(slots.len(), 3);
        assert_eq!(
            slots[0],
            Bounds {
                left: 176,
                top: 461,
                right: 373,
                bottom: 511
            }
        );
        assert_eq!(
            slots[2],
            Bounds {
                left: 7,
                top: 573,
                right: 373,
                bottom: 642
            }
        );
    }

    fn region(id: &str, left: i32, top: i32, right: i32, bottom: i32) -> OcrRegion {
        OcrRegion {
            region_id: id.to_owned(),
            group_id: "body".to_owned(),
            source_revision: 1,
            text: id.to_owned(),
            raw_text: None,
            corrections: vec![],
            source_language: Some("en".to_owned()),
            target_language: Some("zh".to_owned()),
            reading_order: 0,
            block_id: None,
            line_index: None,
            confidence: 1.0,
            bounds: Bounds {
                left,
                top,
                right,
                bottom,
            },
            component_bounds: vec![],
            estimated_text_height_px: Some((bottom - top) as f32),
            typography_confidence: 0.8,
        }
    }
}
