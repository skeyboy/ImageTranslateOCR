use std::collections::HashMap;

use serde::Serialize;

use crate::contract::{Bounds, OcrRegion, SemanticTranslationRequest, TranslationGroup};

pub const DOCUMENT_PLAN_VERSION: &str = "server-semantic-plan-v2";
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
                let Some(confidence) = merge_confidence(&accumulated, next, &regions) else {
                    break;
                };
                if confidence < AUTHORITATIVE_CONFIDENCE {
                    break;
                }
                planned = planned.merge_with(next, confidence);
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

    fn merge_with(mut self, next: &TranslationGroup, confidence: f32) -> Self {
        self.bounds = self.bounds.union(&next.bounds);
        let mut evidence = self.grouping_evidence;
        evidence.extend(next.grouping_evidence.clone());
        evidence.extend([
            "SERVER_SHADOW_REEVALUATION".to_owned(),
            "REGION_OCCUPANCY".to_owned(),
            "PUNCTUATION_CONTINUATION".to_owned(),
        ]);
        evidence.sort();
        evidence.dedup();
        self.source_group_ids.push(next.group_id.clone());
        self.group_id = format!("server-{}", self.source_group_ids.join("--"));
        self.member_region_ids
            .extend(next.member_region_ids.iter().cloned());
        self.translation_unit = "GROUP".to_owned();
        self.source_text = format!("{}\n{}", self.source_text.trim(), next.source_text.trim());
        self.reading_order = self.reading_order.min(next.reading_order);
        self.grouping_confidence = confidence;
        self.grouping_evidence = evidence;
        self.source_line_count += source_line_count(next);
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

fn merge_confidence(
    first: &TranslationGroup,
    second: &TranslationGroup,
    regions: &HashMap<&str, &OcrRegion>,
) -> Option<f32> {
    if first.translation_unit != "GROUP"
        || second.translation_unit != "GROUP"
        || first.role != second.role
        || !matches!(first.role.as_str(), "BODY" | "LIST_ITEM" | "TITLE")
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
        .unwrap_or_else(|| first.bounds.height().min(second.bounds.height()))
        .max(1);
    let gap = second.bounds.top - first.bounds.bottom;
    if gap < -(height / 3) || gap > (height as f32 * 1.15) as i32 {
        return None;
    }
    let overlap = first.bounds.horizontal_overlap(&second.bounds) as f32
        / first.bounds.width().min(second.bounds.width()).max(1) as f32;
    let left_delta = (first.bounds.left - second.bounds.left).abs();
    if overlap < 0.72 || left_delta > height {
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
    (source_confidence >= AUTHORITATIVE_CONFIDENCE).then_some(source_confidence.min(0.96))
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
}
