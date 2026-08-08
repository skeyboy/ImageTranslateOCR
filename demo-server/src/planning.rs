use std::collections::HashMap;

use serde::Serialize;

use crate::contract::{Bounds, OcrRegion, SemanticTranslationRequest, TranslationGroup};

pub const DOCUMENT_PLAN_VERSION: &str = "server-semantic-plan-v3";
const AUTHORITATIVE_CONFIDENCE: f32 = 0.90;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DocumentPlan {
    pub mode: String,
    pub plan_version: &'static str,
    pub groups: Vec<PlannedGroup>,
    pub metrics: PlanMetrics,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanMetrics {
    pub client_group_count: usize,
    pub planned_group_count: usize,
    pub merged_group_count: usize,
    pub authoritative_eligible_count: usize,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedGroup {
    pub group_id: String,
    pub source_group_ids: Vec<String>,
    pub member_region_ids: Vec<String>,
    pub role: String,
    pub translation_unit: String,
    pub source_text: String,
    pub reading_order: i32,
    pub grouping_confidence: f32,
    pub grouping_evidence: Vec<String>,
    pub source_line_count: i32,
    pub bounds: Bounds,
    pub render_slots: Vec<Bounds>,
    pub layout_shape: String,
    pub authoritative_eligible: bool,
}

impl DocumentPlan {
    pub fn build(request: &SemanticTranslationRequest, authoritative: bool) -> Self {
        let regions = request
            .regions
            .iter()
            .map(|region| (region.region_id.as_str(), region))
            .collect::<HashMap<_, _>>();
        let mut ordered = request.groups.iter().collect::<Vec<_>>();
        ordered.sort_by_key(|group| group.reading_order);
        let mut groups = Vec::with_capacity(ordered.len());
        let mut index = 0;
        while index < ordered.len() {
            let mut planned = PlannedGroup::from_client(ordered[index]);
            index += 1;
            while let Some(next) = ordered.get(index).copied() {
                let accumulated = planned.to_translation_group();
                let Some(decision) = merge_confidence(&accumulated, next, &regions) else {
                    break;
                };
                if decision.confidence < AUTHORITATIVE_CONFIDENCE {
                    break;
                }
                planned = planned.merge_with(next, decision);
                index += 1;
            }
            groups.push(planned);
        }
        let merged_group_count = groups
            .iter()
            .filter(|group| group.source_group_ids.len() > 1)
            .count();
        let authoritative_eligible_count = groups
            .iter()
            .filter(|group| group.authoritative_eligible)
            .count();
        Self {
            mode: if authoritative {
                "AUTHORITATIVE"
            } else {
                "SHADOW"
            }
            .to_owned(),
            plan_version: DOCUMENT_PLAN_VERSION,
            metrics: PlanMetrics {
                client_group_count: request.groups.len(),
                planned_group_count: groups.len(),
                merged_group_count,
                authoritative_eligible_count,
            },
            groups,
        }
    }

    pub fn translation_groups(&self) -> Vec<TranslationGroup> {
        self.groups
            .iter()
            .map(PlannedGroup::to_translation_group)
            .collect()
    }
}

impl PlannedGroup {
    fn from_client(group: &TranslationGroup) -> Self {
        let confidence = group.grouping_confidence.clamp(0.0, 1.0);
        Self {
            group_id: group.group_id.clone(),
            source_group_ids: vec![group.group_id.clone()],
            member_region_ids: group.member_region_ids.clone(),
            role: group.role.clone(),
            translation_unit: group.translation_unit.clone(),
            source_text: group.source_text.clone(),
            reading_order: group.reading_order,
            grouping_confidence: confidence,
            grouping_evidence: group.grouping_evidence.clone(),
            source_line_count: source_line_count(group),
            bounds: group.bounds.clone(),
            render_slots: group
                .render_slots
                .clone()
                .into_iter()
                .chain(
                    group
                        .render_slots
                        .is_empty()
                        .then_some(group.bounds.clone()),
                )
                .collect(),
            layout_shape: group.layout_shape.clone(),
            authoritative_eligible: confidence >= AUTHORITATIVE_CONFIDENCE,
        }
    }

    fn merge_with(mut self, next: &TranslationGroup, decision: MergeDecision) -> Self {
        let role_drift = self.role != next.role;
        self.bounds = self.bounds.union(&next.bounds);
        let mut evidence = self.grouping_evidence;
        evidence.extend(next.grouping_evidence.clone());
        evidence.extend([
            "SERVER_SHADOW_REEVALUATION".to_owned(),
            "REGION_OCCUPANCY".to_owned(),
            "PUNCTUATION_CONTINUATION".to_owned(),
            decision.evidence.to_owned(),
        ]);
        if role_drift {
            evidence.push("ROLE_DRIFT_NORMALIZED".to_owned());
        }
        evidence.sort();
        evidence.dedup();
        self.source_group_ids.push(next.group_id.clone());
        self.group_id = format!("server-{}", self.source_group_ids.join("--"));
        self.member_region_ids
            .extend(next.member_region_ids.iter().cloned());
        self.translation_unit = "GROUP".to_owned();
        self.source_text = format!("{}\n{}", self.source_text.trim(), next.source_text.trim());
        self.reading_order = self.reading_order.min(next.reading_order);
        self.grouping_confidence = decision.confidence;
        self.grouping_evidence = evidence;
        self.source_line_count += source_line_count(next);
        if role_drift && (self.role == "BODY" || next.role == "BODY") {
            self.role = "BODY".to_owned();
        }
        self.render_slots.extend(slots(next));
        self.layout_shape = if self.render_slots.len() > 1 {
            "FLOW_SLOTS"
        } else {
            "RECT"
        }
        .to_owned();
        self.authoritative_eligible = true;
        self
    }

    fn to_translation_group(&self) -> TranslationGroup {
        TranslationGroup {
            group_id: self.group_id.clone(),
            role: self.role.clone(),
            translation_unit: self.translation_unit.clone(),
            source_text: self.source_text.clone(),
            member_region_ids: self.member_region_ids.clone(),
            reading_order: self.reading_order,
            grouping_confidence: self.grouping_confidence,
            grouping_evidence: self.grouping_evidence.clone(),
            source_line_count: Some(self.source_line_count),
            bounds: self.bounds.clone(),
            render_slots: self.render_slots.clone(),
            layout_shape: self.layout_shape.clone(),
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct MergeDecision {
    confidence: f32,
    evidence: &'static str,
}

fn merge_confidence(
    first: &TranslationGroup,
    second: &TranslationGroup,
    regions: &HashMap<&str, &OcrRegion>,
) -> Option<MergeDecision> {
    let same_ocr_block = is_same_ocr_block_continuation(first, second, regions);
    let same_visible_text = first
        .source_text
        .split_whitespace()
        .collect::<String>()
        .eq_ignore_ascii_case(&second.source_text.split_whitespace().collect::<String>());
    if first.translation_unit != "GROUP"
        || second.translation_unit != "GROUP"
        || same_visible_text
        || !matches!(first.role.as_str(), "BODY" | "LIST_ITEM" | "TITLE")
        || !matches!(second.role.as_str(), "BODY" | "LIST_ITEM" | "TITLE")
        || looks_like_section_label(&first.source_text)
    {
        return None;
    }
    let height = first
        .member_region_ids
        .iter()
        .chain(&second.member_region_ids)
        .filter_map(|id| regions.get(id.as_str()))
        .map(|region| region.bounds.height())
        .max()
        .unwrap_or_else(|| {
            let first_line_height = first.bounds.height() / source_line_count(first).max(1);
            let second_line_height = second.bounds.height() / source_line_count(second).max(1);
            first_line_height.max(second_line_height)
        })
        .max(1);
    let gap = second.bounds.top - first.bounds.bottom;
    if gap < -(height / 3) || gap > (height as f32 * 1.15) as i32 {
        return None;
    }
    let overlap = first.bounds.horizontal_overlap(&second.bounds) as f32
        / first.bounds.width().min(second.bounds.width()).max(1) as f32;
    let left_delta = (first.bounds.left - second.bounds.left).abs();
    let right_delta = (first.bounds.right - second.bounds.right).abs();
    let returns_below_wrapped_media = first.layout_shape == "FLOW_SLOTS"
        && first.render_slots.len() >= 2
        && second.bounds.left + height * 2 < first.bounds.left
        && overlap >= 0.25;
    let expands_around_media = second.bounds.left < first.bounds.left
        && overlap >= 0.72
        && left_delta <= height * 4
        && right_delta <= height * 2;
    let same_column = overlap >= 0.72 && left_delta <= height;
    if !same_column && !expands_around_media && !returns_below_wrapped_media {
        return None;
    }
    if first.role != second.role && !same_ocr_block && !same_column && !returns_below_wrapped_media
    {
        return None;
    }
    let first_text = first.source_text.trim_end();
    let second_text = second.source_text.trim_start();
    let continuation = !first_text.ends_with(['.', '!', '?', '。', '！', '？'])
        || second_text.chars().next().is_some_and(char::is_lowercase);
    if !continuation {
        return None;
    }
    let source_confidence = first.grouping_confidence.min(second.grouping_confidence);
    (source_confidence >= AUTHORITATIVE_CONFIDENCE).then_some(MergeDecision {
        confidence: source_confidence.min(0.96),
        evidence: if same_ocr_block {
            "OCR_BLOCK_CONTINUATION"
        } else if expands_around_media || returns_below_wrapped_media {
            "WRAPPED_MEDIA_FLOW"
        } else {
            "VISUAL_LINE_CONTINUATION"
        },
    })
}

fn is_same_ocr_block_continuation(
    first: &TranslationGroup,
    second: &TranslationGroup,
    regions: &HashMap<&str, &OcrRegion>,
) -> bool {
    let first_region = first
        .member_region_ids
        .iter()
        .filter_map(|id| regions.get(id.as_str()))
        .max_by_key(|region| region.line_index.unwrap_or(region.reading_order));
    let second_region = second
        .member_region_ids
        .iter()
        .filter_map(|id| regions.get(id.as_str()))
        .min_by_key(|region| region.line_index.unwrap_or(region.reading_order));
    let (Some(first_region), Some(second_region)) = (first_region, second_region) else {
        return false;
    };
    first_region.block_id.as_deref().is_some_and(|block_id| {
        second_region.block_id.as_deref() == Some(block_id)
            && first_region
                .line_index
                .zip(second_region.line_index)
                .is_some_and(|(first_line, second_line)| second_line == first_line + 1)
    })
}

fn looks_like_section_label(text: &str) -> bool {
    let words = text.split_whitespace().collect::<Vec<_>>();
    !words.is_empty()
        && words.len() <= 3
        && text
            .chars()
            .filter(|character| character.is_alphanumeric())
            .count()
            <= 24
        && text.chars().any(|character| character.is_alphabetic())
        && text
            .chars()
            .filter(|character| character.is_alphabetic())
            .all(|character| character.is_uppercase())
}

fn source_line_count(group: &TranslationGroup) -> i32 {
    group
        .source_line_count
        .unwrap_or_default()
        .max(group.member_region_ids.len().max(1) as i32)
        .max(
            group
                .source_text
                .lines()
                .filter(|line| !line.trim().is_empty())
                .count() as i32,
        )
}

fn slots(group: &TranslationGroup) -> Vec<Bounds> {
    if group.render_slots.is_empty() {
        vec![group.bounds.clone()]
    } else {
        group.render_slots.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contract::SemanticTranslationRequest;

    #[test]
    fn merges_only_high_confidence_continuations_and_keeps_lineage() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/xi-news-request.json")).unwrap();
        request.groups[0].role = "BODY".to_owned();
        let mut second = request.groups[0].clone();
        second.group_id = "group-title-continuation".to_owned();
        second.source_text = "in Beijing".to_owned();
        second.member_region_ids = vec!["region-title-continuation".to_owned()];
        second.bounds.top = request.groups[0].bounds.bottom + 5;
        second.bounds.bottom = second.bounds.top + 60;
        second.render_slots = vec![second.bounds.clone()];
        second.reading_order += 1;
        let mut region = request.regions[0].clone();
        region.region_id = second.member_region_ids[0].clone();
        region.group_id = second.group_id.clone();
        region.text = second.source_text.clone();
        region.bounds = second.bounds.clone();
        request.groups.insert(1, second);
        request.regions.push(region);
        let plan = DocumentPlan::build(&request, false);
        assert_eq!(plan.groups[0].source_group_ids.len(), 2);
        assert!(plan.groups[0].authoritative_eligible);
        assert_eq!(plan.mode, "SHADOW");
    }

    #[test]
    fn merges_a_chain_of_three_continuation_groups_into_one_flow() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/xi-news-request.json")).unwrap();
        let mut first = request.groups[0].clone();
        first.role = "BODY".to_owned();
        first.source_text = "Meanwhile, the Department of".to_owned();
        first.bounds.bottom = first.bounds.top + 60;
        first.render_slots = vec![first.bounds.clone()];

        let mut second = first.clone();
        second.group_id = "body-continuation-2".to_owned();
        second.source_text = "Meteorology under the Ministry of Agriculture and".to_owned();
        second.reading_order += 1;
        second.bounds.top = first.bounds.bottom + 20;
        second.bounds.bottom = second.bounds.top + 120;
        second.render_slots = vec![second.bounds.clone()];

        let mut third = second.clone();
        third.group_id = "body-continuation-3".to_owned();
        third.source_text = "issued a warning on Wednesday that".to_owned();
        third.reading_order += 1;
        third.bounds.top = second.bounds.bottom + 20;
        third.bounds.bottom = third.bounds.top + 60;
        third.render_slots = vec![third.bounds.clone()];

        request.groups = vec![first, second, third];
        request.regions.clear();

        let plan = DocumentPlan::build(&request, true);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].source_group_ids.len(), 3);
        assert_eq!(plan.groups[0].render_slots.len(), 3);
        assert_eq!(plan.groups[0].source_line_count, 3);
        assert_eq!(plan.groups[0].layout_shape, "FLOW_SLOTS");
        assert_eq!(plan.metrics.merged_group_count, 1);
    }

    #[test]
    fn keeps_short_uppercase_section_label_separate() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/xi-news-request.json")).unwrap();
        let mut label = request.groups[0].clone();
        label.role = "BODY".to_owned();
        label.source_text = "AFRICA".to_owned();
        label.bounds.right = label.bounds.left + 140;
        label.render_slots = vec![label.bounds.clone()];

        let mut title = label.clone();
        title.group_id = "article-title".to_owned();
        title.source_text = "It is time for new, equitable criteria".to_owned();
        title.reading_order += 1;
        title.bounds.top = label.bounds.bottom + 20;
        title.bounds.bottom = title.bounds.top + 60;
        title.bounds.right = title.bounds.left + 1_200;
        title.render_slots = vec![title.bounds.clone()];

        request.groups = vec![label, title];
        request.regions.clear();

        let plan = DocumentPlan::build(&request, true);

        assert_eq!(plan.groups.len(), 2);
    }

    #[test]
    fn keeps_repeated_labels_as_separate_page_elements() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/xi-news-request.json")).unwrap();
        let mut brand_label = request.groups[0].clone();
        brand_label.role = "BODY".to_owned();
        brand_label.source_text = "Africa Edition".to_owned();
        brand_label.bounds = Bounds {
            left: 557,
            top: 458,
            right: 777,
            bottom: 484,
        };
        brand_label.render_slots = vec![brand_label.bounds.clone()];

        let mut navigation_label = brand_label.clone();
        navigation_label.group_id = "navigation-label".to_owned();
        navigation_label.reading_order += 1;
        navigation_label.bounds = Bounds {
            left: 536,
            top: 548,
            right: 901,
            bottom: 606,
        };
        navigation_label.render_slots = vec![navigation_label.bounds.clone()];

        request.groups = vec![brand_label, navigation_label];
        request.regions.clear();

        let plan = DocumentPlan::build(&request, true);

        assert_eq!(plan.groups.len(), 2);
        assert!(
            plan.groups
                .iter()
                .all(|group| group.source_group_ids.len() == 1)
        );
    }

    #[test]
    fn merges_consecutive_lines_from_same_ocr_block_despite_role_drift() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/xi-news-request.json")).unwrap();
        let mut first = request.groups[0].clone();
        first.role = "BODY".to_owned();
        first.source_text = "silent struggle has been".to_owned();
        first.member_region_ids = vec!["line-0".to_owned()];
        first.source_line_count = Some(1);
        first.bounds.right = first.bounds.left + 584;
        first.render_slots = vec![first.bounds.clone()];

        let mut second = first.clone();
        second.group_id = "continuation".to_owned();
        second.role = "TITLE".to_owned();
        second.source_text = "raging within our universities and it is not".to_owned();
        second.member_region_ids = vec!["line-1".to_owned()];
        second.reading_order += 1;
        second.bounds.top = first.bounds.bottom + 17;
        second.bounds.bottom = second.bounds.top + first.bounds.height() * 2;
        second.render_slots = vec![second.bounds.clone()];

        let mut first_region = request.regions[0].clone();
        first_region.region_id = "line-0".to_owned();
        first_region.group_id = first.group_id.clone();
        first_region.block_id = Some("mlkit-block".to_owned());
        first_region.line_index = Some(0);
        first_region.bounds = first.bounds.clone();
        let mut second_region = first_region.clone();
        second_region.region_id = "line-1".to_owned();
        second_region.group_id = second.group_id.clone();
        second_region.line_index = Some(1);
        second_region.bounds = second.bounds.clone();

        request.groups = vec![first, second];
        request.regions = vec![first_region, second_region];

        let plan = DocumentPlan::build(&request, true);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].source_group_ids.len(), 2);
        assert_eq!(plan.groups[0].source_line_count, 2);
    }

    #[test]
    fn merges_right_wrapped_text_when_the_next_line_returns_below_media() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/xi-news-request.json")).unwrap();
        let mut right_top = request.groups[0].clone();
        right_top.role = "BODY".to_owned();
        right_top.source_text = "silent struggle has been".to_owned();
        right_top.bounds = Bounds {
            left: 727,
            top: 858,
            right: 1311,
            bottom: 908,
        };
        right_top.render_slots = vec![right_top.bounds.clone()];

        let mut right_body = right_top.clone();
        right_body.group_id = "right-body".to_owned();
        right_body.source_text =
            "raging within our universities and specifically about what".to_owned();
        right_body.reading_order = 1;
        right_body.bounds = Bounds {
            left: 680,
            top: 923,
            right: 1410,
            bottom: 1261,
        };
        right_body.render_slots = vec![right_body.bounds.clone()];

        let mut below = right_top.clone();
        below.group_id = "below-media".to_owned();
        below.source_text = "is perceived as worthy academic work.".to_owned();
        below.reading_order = 2;
        below.bounds = Bounds {
            left: 29,
            top: 1270,
            right: 996,
            bottom: 1331,
        };
        below.render_slots = vec![below.bounds.clone()];

        request.groups = vec![right_top, right_body, below];
        request.regions.clear();

        let plan = DocumentPlan::build(&request, true);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].source_group_ids.len(), 3);
        assert_eq!(plan.groups[0].render_slots.len(), 3);
        assert_eq!(plan.groups[0].layout_shape, "FLOW_SLOTS");
    }

    #[test]
    fn merges_article_text_that_steps_left_around_media_and_crosses_role_drift() {
        let mut request: SemanticTranslationRequest =
            serde_json::from_str(include_str!("../examples/xi-news-request.json")).unwrap();
        let mut first = request.groups[0].clone();
        first.role = "TITLE".to_owned();
        first.source_text =
            "The Canadian government\nhas awarded C$20 million\n(US$19.4 million) over the"
                .to_owned();
        first.bounds = Bounds {
            left: 661,
            top: 854,
            right: 1323,
            bottom: 1041,
        };
        first.render_slots = vec![first.bounds.clone()];
        first.source_line_count = Some(3);

        let mut second = first.clone();
        second.group_id = "article-flow-2".to_owned();
        second.source_text =
            "next four years to five centres of the\nAfrican Institute for Mathematical".to_owned();
        second.reading_order = 1;
        second.bounds = Bounds {
            left: 484,
            top: 1064,
            right: 1387,
            bottom: 1176,
        };
        second.render_slots = vec![second.bounds.clone()];
        second.source_line_count = Some(2);

        let mut third = first.clone();
        third.group_id = "article-flow-3".to_owned();
        third.role = "BODY".to_owned();
        third.source_text = "Sciences. The centres are spread".to_owned();
        third.reading_order = 2;
        third.bounds = Bounds {
            left: 487,
            top: 1200,
            right: 1291,
            bottom: 1254,
        };
        third.render_slots = vec![third.bounds.clone()];
        third.source_line_count = Some(1);

        let mut fourth = third.clone();
        fourth.group_id = "article-flow-4".to_owned();
        fourth.source_text =
            "across the continent and run through the AIMS-Next Initiative.".to_owned();
        fourth.reading_order = 3;
        fourth.bounds = Bounds {
            left: 29,
            top: 1274,
            right: 1334,
            bottom: 1532,
        };
        fourth.render_slots = vec![fourth.bounds.clone()];
        fourth.source_line_count = Some(4);

        request.groups = vec![first, second, third, fourth];
        request.regions.clear();

        let plan = DocumentPlan::build(&request, true);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].source_group_ids.len(), 4);
        assert_eq!(plan.groups[0].render_slots.len(), 4);
        assert_eq!(plan.groups[0].source_line_count, 10);
        assert_eq!(plan.groups[0].role, "BODY");
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"WRAPPED_MEDIA_FLOW".to_owned())
        );
    }
}
