use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};

use crate::contract::{Bounds, OcrRegion, SemanticTranslationRequest};
use crate::planning::{DocumentPlan, PlanMetrics, PlannedGroup};

pub const DOCUMENT_PLAN_VERSION_V4: &str = "server-regions-first-plan-v4";
const AUTHORITATIVE_CONFIDENCE: f32 = 0.90;

pub fn build_regions_first_plan(request: &SemanticTranslationRequest) -> DocumentPlan {
    let advisory_by_region = request
        .groups
        .iter()
        .flat_map(|group| {
            group
                .member_region_ids
                .iter()
                .map(move |region_id| (region_id.as_str(), group))
        })
        .collect::<HashMap<_, _>>();
    let mut ordered = request.regions.iter().collect::<Vec<_>>();
    ordered.sort_by_key(|region| (region.reading_order, region.bounds.top, region.bounds.left));

    let mut groups = Vec::<RegionGroup>::new();
    for region in ordered {
        let advisory = advisory_by_region.get(region.region_id.as_str()).copied();
        let next = RegionGroup::from_region(region, advisory);
        if let Some(previous) = groups.last_mut()
            && let Some(decision) = merge_decision(previous, &next, request.viewport.width)
        {
            previous.merge(next, decision);
            continue;
        }
        groups.push(next);
    }

    let planned = groups
        .into_iter()
        .enumerate()
        .map(|(index, group)| group.into_planned(index))
        .collect::<Vec<_>>();
    let merged_group_count = planned
        .iter()
        .filter(|group| group.member_region_ids.len() > 1)
        .count();
    let authoritative_eligible_count = planned
        .iter()
        .filter(|group| group.authoritative_eligible)
        .count();
    DocumentPlan {
        mode: "AUTHORITATIVE".to_owned(),
        plan_version: DOCUMENT_PLAN_VERSION_V4,
        metrics: PlanMetrics {
            client_group_count: request.groups.len(),
            planned_group_count: planned.len(),
            merged_group_count,
            authoritative_eligible_count,
        },
        groups: planned,
    }
}

#[derive(Clone, Debug)]
struct RegionGroup {
    source_group_ids: Vec<String>,
    member_region_ids: Vec<String>,
    role: String,
    translation_unit: String,
    source_text: String,
    reading_order: i32,
    confidence: f32,
    evidence: Vec<String>,
    bounds: Bounds,
    render_slots: Vec<Bounds>,
    first_region: OcrRegion,
    last_region: OcrRegion,
}

impl RegionGroup {
    fn from_region(
        region: &OcrRegion,
        advisory: Option<&crate::contract::TranslationGroup>,
    ) -> Self {
        let role = inferred_role(region, advisory);
        let translation_unit = if is_protected_role(&role) {
            "PRESERVED".to_owned()
        } else {
            advisory
                .filter(|group| group.member_region_ids.len() == 1)
                .map(|group| group.translation_unit.clone())
                .unwrap_or_else(|| "GROUP".to_owned())
        };
        let mut evidence = vec![
            "SERVER_REGIONS_FIRST".to_owned(),
            "ATOMIC_REGION".to_owned(),
        ];
        if advisory.is_some() {
            evidence.push("CLIENT_GROUP_ADVISORY".to_owned());
        }
        Self {
            source_group_ids: region
                .group_id
                .trim()
                .is_empty()
                .then(Vec::new)
                .unwrap_or_else(|| vec![region.group_id.clone()]),
            member_region_ids: vec![region.region_id.clone()],
            role,
            translation_unit,
            source_text: region.text.trim().to_owned(),
            reading_order: region.reading_order,
            confidence: region.confidence.clamp(0.0, 1.0),
            evidence,
            bounds: region.bounds.clone(),
            render_slots: vec![region.bounds.clone()],
            first_region: region.clone(),
            last_region: region.clone(),
        }
    }

    fn merge(&mut self, next: Self, decision: MergeDecision) {
        self.source_group_ids.extend(next.source_group_ids);
        deduplicate(&mut self.source_group_ids);
        self.member_region_ids.extend(next.member_region_ids);
        self.source_text = format!("{}\n{}", self.source_text.trim(), next.source_text.trim());
        self.bounds = self.bounds.union(&next.bounds);
        self.render_slots.extend(next.render_slots);
        self.confidence = self
            .confidence
            .min(next.confidence)
            .min(decision.confidence);
        self.evidence.extend(next.evidence);
        self.evidence.push(decision.evidence.to_owned());
        self.evidence.push("SERVER_V4_MERGE".to_owned());
        deduplicate(&mut self.evidence);
        if self.role != next.role && (self.role == "BODY" || next.role == "BODY") {
            self.role = "BODY".to_owned();
            self.evidence.push("ROLE_DRIFT_NORMALIZED".to_owned());
        }
        self.translation_unit =
            if self.translation_unit == "PRESERVED" && next.translation_unit == "PRESERVED" {
                "PRESERVED"
            } else {
                "GROUP"
            }
            .to_owned();
        self.last_region = next.last_region;
    }

    fn into_planned(self, index: usize) -> PlannedGroup {
        let group_id = stable_group_id(index, &self.member_region_ids);
        PlannedGroup {
            group_id,
            source_group_ids: self.source_group_ids,
            member_region_ids: self.member_region_ids,
            role: self.role,
            translation_unit: self.translation_unit,
            source_text: self.source_text,
            reading_order: self.reading_order,
            grouping_confidence: self.confidence,
            grouping_evidence: self.evidence,
            source_line_count: self.render_slots.len().max(1) as i32,
            bounds: self.bounds,
            layout_shape: if self.render_slots.len() > 1 {
                "FLOW_SLOTS"
            } else {
                "RECT"
            }
            .to_owned(),
            render_slots: self.render_slots,
            authoritative_eligible: self.confidence >= AUTHORITATIVE_CONFIDENCE,
        }
    }
}

#[derive(Clone, Copy)]
struct MergeDecision {
    confidence: f32,
    evidence: &'static str,
}

fn merge_decision(
    previous: &RegionGroup,
    next: &RegionGroup,
    viewport_width: i32,
) -> Option<MergeDecision> {
    if previous.translation_unit == "PRESERVED"
        || next.translation_unit == "PRESERVED"
        || is_protected_role(&previous.role)
        || is_protected_role(&next.role)
        || is_strong_text_boundary(&previous.source_text, &next.source_text)
    {
        return None;
    }
    let first = &previous.last_region;
    let second = &next.first_region;
    let height = first.bounds.height().max(second.bounds.height()).max(1);
    let gap = second.bounds.top - first.bounds.bottom;
    if gap < -(height / 3) || gap > (height as f32 * 1.25) as i32 {
        return None;
    }
    let height_ratio = first.bounds.height().max(second.bounds.height()) as f32
        / first.bounds.height().min(second.bounds.height()).max(1) as f32;
    if height_ratio > 1.85 {
        return None;
    }
    let same_block = same_block_continuation(first, second);
    let overlap = first.bounds.horizontal_overlap(&second.bounds).max(0) as f32
        / first.bounds.width().min(second.bounds.width()).max(1) as f32;
    let left_delta = (first.bounds.left - second.bounds.left).abs();
    let right_delta = (first.bounds.right - second.bounds.right).abs();
    let same_column = overlap >= 0.68 && left_delta <= height * 2;
    let wrapped_step = overlap >= 0.25
        && right_delta <= height * 4
        && left_delta <= (viewport_width as f32 * 0.42) as i32;
    if !same_block && !same_column && !wrapped_step {
        return None;
    }
    if previous.role != next.role && !same_block {
        return None;
    }
    let continuation = !ends_sentence(&previous.source_text)
        || next
            .source_text
            .trim_start()
            .chars()
            .next()
            .is_some_and(char::is_lowercase)
        || same_block;
    if !continuation {
        return None;
    }
    let source_confidence = previous.confidence.min(next.confidence);
    let confidence = if same_block {
        source_confidence.min(0.97)
    } else if same_column {
        source_confidence.min(0.94)
    } else {
        source_confidence.min(0.92)
    };
    (confidence >= AUTHORITATIVE_CONFIDENCE).then_some(MergeDecision {
        confidence,
        evidence: if same_block {
            "OCR_BLOCK_CONTINUATION"
        } else if wrapped_step {
            "WRAPPED_MEDIA_FLOW"
        } else {
            "VISUAL_LINE_CONTINUATION"
        },
    })
}

fn inferred_role(
    region: &OcrRegion,
    advisory: Option<&crate::contract::TranslationGroup>,
) -> String {
    let text = region.text.trim();
    if is_standalone_timestamp(text) {
        return "TIMESTAMP".to_owned();
    }
    if looks_like_code(text) {
        return "CODE".to_owned();
    }
    if looks_like_identifier(text) {
        return "IDENTIFIER".to_owned();
    }
    advisory
        .map(|group| group.role.clone())
        .filter(|role| !matches!(role.as_str(), "TIMESTAMP" | "IDENTIFIER" | "CONTROL"))
        .unwrap_or_else(|| "BODY".to_owned())
}

fn is_protected_role(role: &str) -> bool {
    matches!(role, "TIMESTAMP" | "IDENTIFIER" | "CONTROL" | "CODE")
}

fn same_block_continuation(first: &OcrRegion, second: &OcrRegion) -> bool {
    first.block_id.as_deref().is_some_and(|block_id| {
        second.block_id.as_deref() == Some(block_id)
            && first
                .line_index
                .zip(second.line_index)
                .is_none_or(|(first_line, second_line)| second_line == first_line + 1)
    })
}

fn is_strong_text_boundary(previous: &str, next: &str) -> bool {
    looks_like_short_label(previous)
        || (looks_like_short_label(next) && ends_sentence(previous))
        || is_standalone_timestamp(previous)
        || is_standalone_timestamp(next)
}

fn looks_like_short_label(text: &str) -> bool {
    let compact = text
        .chars()
        .filter(|character| character.is_alphanumeric())
        .count();
    compact > 0
        && compact <= 24
        && text.split_whitespace().count() <= 4
        && text
            .chars()
            .filter(|character| character.is_alphabetic())
            .count()
            > 0
        && text
            .chars()
            .filter(|character| character.is_alphabetic())
            .all(|character| character.is_uppercase())
}

fn is_standalone_timestamp(text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.len() > 32 || !trimmed.chars().any(|character| character.is_ascii_digit()) {
        return false;
    }
    trimmed.chars().all(|character| {
        character.is_ascii_digit()
            || character.is_whitespace()
            || matches!(
                character,
                ':' | '-' | '/' | '.' | 'A' | 'P' | 'M' | 'a' | 'p' | 'm'
            )
    })
}

fn looks_like_identifier(text: &str) -> bool {
    let trimmed = text.trim();
    trimmed.contains("://")
        || trimmed.starts_with("www.")
        || (trimmed.contains('@') && !trimmed.contains(' '))
}

fn looks_like_code(text: &str) -> bool {
    let mut tokens = text.split_whitespace();
    let first = tokens.next().unwrap_or_default();
    let second = tokens.next().unwrap_or_default();
    matches!(
        first,
        "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD" | "OPTIONS"
    ) && second.starts_with('/')
        || text.contains("/api/")
        || text.contains("//")
        || text.contains("::")
}

fn ends_sentence(text: &str) -> bool {
    text.trim_end().ends_with(['.', '!', '?', '。', '！', '？'])
}

fn stable_group_id(index: usize, region_ids: &[String]) -> String {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    region_ids.hash(&mut hasher);
    format!("server-v4-{index}-{:016x}", hasher.finish())
}

fn deduplicate(values: &mut Vec<String>) {
    let mut seen = HashSet::new();
    values.retain(|value| seen.insert(value.clone()));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contract::SemanticTranslationRequest;

    fn request() -> SemanticTranslationRequest {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/xi-news-request.json")).unwrap();
        request.schema_version = 4;
        request
    }

    #[test]
    fn builds_from_regions_without_client_groups() {
        let mut request = request();
        request.groups.clear();
        request.regions[0].group_id.clear();

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.plan_version, DOCUMENT_PLAN_VERSION_V4);
        assert!(!plan.groups.is_empty());
        assert!(
            plan.groups
                .iter()
                .all(|group| !group.member_region_ids.is_empty())
        );
    }

    #[test]
    fn splits_an_overmerged_advisory_group_at_a_large_visual_gap() {
        let mut request = request();
        let first = request.regions[0].clone();
        let mut second = first.clone();
        second.region_id = "far-line".to_owned();
        second.reading_order += 1;
        second.line_index = second.line_index.map(|line| line + 1);
        second.bounds.top = first.bounds.bottom + first.bounds.height() * 4;
        second.bounds.bottom = second.bounds.top + first.bounds.height();
        request.regions = vec![first.clone(), second.clone()];
        request.groups[0].member_region_ids = vec![first.region_id, second.region_id];
        request.groups[0].source_text = format!("{}\n{}", first.text, second.text);
        request.groups[0].bounds = first.bounds.union(&second.bounds);
        request.groups.truncate(1);

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
        assert!(
            plan.groups
                .iter()
                .all(|group| group.source_group_ids.len() == 1)
        );
    }

    #[test]
    fn keeps_chat_timestamp_outside_body_flow() {
        let mut request = request();
        let mut body = request.regions[0].clone();
        body.text = "The Web3 industry tried and we did quite well".to_owned();
        let mut timestamp = body.clone();
        timestamp.region_id = "timestamp".to_owned();
        timestamp.text = "22:43".to_owned();
        timestamp.reading_order += 1;
        timestamp.bounds.top = body.bounds.bottom + 4;
        timestamp.bounds.bottom = timestamp.bounds.top + body.bounds.height();
        request.groups.clear();
        request.regions = vec![body, timestamp];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
        assert_eq!(plan.groups[1].role, "TIMESTAMP");
    }

    #[test]
    fn restores_article_lines_that_flow_around_media() {
        let mut request = request();
        let mut first = request.regions[0].clone();
        first.text = "The Canadian government has awarded funding over the".to_owned();
        first.group_id.clear();
        first.block_id = Some("article-flow".to_owned());
        first.line_index = Some(0);
        first.bounds = Bounds {
            left: 420,
            top: 500,
            right: 1000,
            bottom: 550,
        };
        let mut second = first.clone();
        second.region_id = "article-line-2".to_owned();
        second.text = "next four years to five research centres".to_owned();
        second.reading_order += 1;
        second.line_index = Some(1);
        second.bounds = Bounds {
            left: 360,
            top: 560,
            right: 1020,
            bottom: 610,
        };
        let mut third = second.clone();
        third.region_id = "article-line-3".to_owned();
        third.text = "across the continent through the AIMS-Next Initiative.".to_owned();
        third.reading_order += 1;
        third.line_index = Some(2);
        third.bounds = Bounds {
            left: 20,
            top: 620,
            right: 1040,
            bottom: 670,
        };
        request.groups.clear();
        request.regions = vec![first, second, third];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].member_region_ids.len(), 3);
        assert_eq!(plan.groups[0].layout_shape, "FLOW_SLOTS");
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"OCR_BLOCK_CONTINUATION".to_owned())
        );
    }

    #[test]
    fn keeps_technical_section_heading_separate_from_explanation() {
        let mut request = request();
        let mut heading = request.regions[0].clone();
        heading.text = "API REFERENCE".to_owned();
        heading.group_id.clear();
        heading.block_id = Some("technical".to_owned());
        heading.line_index = Some(0);
        let mut body = heading.clone();
        body.region_id = "technical-body".to_owned();
        body.text = "The request body contains an ordered list of OCR regions.".to_owned();
        body.reading_order += 1;
        body.line_index = Some(1);
        body.bounds.top = heading.bounds.bottom + 8;
        body.bounds.bottom = body.bounds.top + heading.bounds.height();
        request.groups.clear();
        request.regions = vec![heading, body];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
        assert_eq!(plan.groups[0].source_text, "API REFERENCE");
        assert_eq!(plan.groups[1].role, "BODY");
    }

    #[test]
    fn preserves_http_method_and_api_path_as_code() {
        let mut request = request();
        let mut code = request.regions[0].clone();
        code.text = "POST /api/v4/translate/layout-plan".to_owned();
        code.group_id.clear();
        request.groups.clear();
        request.regions = vec![code];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups[0].role, "CODE");
        assert_eq!(plan.groups[0].translation_unit, "PRESERVED");
    }
}
