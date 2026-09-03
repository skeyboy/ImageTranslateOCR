use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};

use crate::contract::{Bounds, OcrRegion, SemanticTranslationRequest};
use crate::planning::{DocumentPlan, PlanMetrics, PlannedGroup};

pub const DOCUMENT_PLAN_VERSION_V4: &str = "server-regions-first-plan-v4";
const AUTHORITATIVE_CONFIDENCE: f32 = 0.90;
const CROSS_GROUP_FONT_RATIO: f32 = 0.88;
const CROSS_GROUP_MINIMUM_GAP_EM: f32 = 0.65;
const CROSS_GROUP_GAP_DISCONTINUITY_RATIO: f32 = 1.8;
const TYPOGRAPHY_TIER_RATIO: f32 = 0.78;
const RELAXED_TYPOGRAPHY_RATIO: f32 = 0.65;
const RELIABLE_TYPOGRAPHY_CONFIDENCE: f32 = 0.70;
const COMPACT_TAIL_MINIMUM_TYPOGRAPHY_RATIO: f32 = 0.58;

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
    let client_group_profiles = build_client_group_profiles(request);
    let mut ordered = request.regions.iter().collect::<Vec<_>>();
    ordered.sort_by_key(|region| (region.reading_order, region.bounds.top, region.bounds.left));

    let mut groups = Vec::<RegionGroup>::new();
    for region in ordered {
        let advisory = advisory_by_region.get(region.region_id.as_str()).copied();
        let next = RegionGroup::from_region(region, advisory);
        if let Some(previous) = groups.last_mut()
            && let Some(decision) = merge_decision(
                previous,
                &next,
                request.viewport.width,
                &client_group_profiles,
            )
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
        plan_version: DOCUMENT_PLAN_VERSION_V4.to_owned(),
        metrics: PlanMetrics {
            client_group_count: request.groups.len(),
            planned_group_count: planned.len(),
            merged_group_count,
            authoritative_eligible_count,
        },
        groups: planned,
    }
}

#[derive(Clone, Copy, Debug, Default)]
struct ClientGroupProfile {
    median_internal_gap: Option<i32>,
    member_count: usize,
    sustained_typography_transition_reading_order: Option<i32>,
}

fn build_client_group_profiles(
    request: &SemanticTranslationRequest,
) -> HashMap<String, ClientGroupProfile> {
    let regions_by_id = request
        .regions
        .iter()
        .map(|region| (region.region_id.as_str(), region))
        .collect::<HashMap<_, _>>();
    request
        .groups
        .iter()
        .map(|group| {
            let mut regions = group
                .member_region_ids
                .iter()
                .filter_map(|region_id| regions_by_id.get(region_id.as_str()).copied())
                .cloned()
                .collect::<Vec<_>>();
            regions.sort_by_key(|region| {
                (region.reading_order, region.bounds.top, region.bounds.left)
            });
            (
                group.group_id.clone(),
                ClientGroupProfile {
                    median_internal_gap: median_internal_gap(&regions),
                    member_count: regions.len(),
                    sustained_typography_transition_reading_order:
                        sustained_typography_transition_reading_order(&regions),
                },
            )
        })
        .collect()
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
    all_advisory_layouts_rect: bool,
    first_region: OcrRegion,
    last_region: OcrRegion,
    regions: Vec<OcrRegion>,
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
        if region.confidence < AUTHORITATIVE_CONFIDENCE {
            evidence.push("LOW_OCR_TEXT_CONFIDENCE".to_owned());
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
            // A single OCR region has no grouping ambiguity. OCR recognition
            // confidence describes text quality, not layout membership.
            confidence: 1.0,
            evidence,
            bounds: region.bounds.clone(),
            render_slots: vec![region.bounds.clone()],
            all_advisory_layouts_rect: advisory
                .is_some_and(|group| group.layout_shape == "RECT" && group.render_slots.len() <= 1),
            first_region: region.clone(),
            last_region: region.clone(),
            regions: vec![region.clone()],
        }
    }

    fn merge(&mut self, next: Self, decision: MergeDecision) {
        self.source_group_ids.extend(next.source_group_ids);
        deduplicate(&mut self.source_group_ids);
        self.member_region_ids.extend(next.member_region_ids);
        self.source_text = format!("{}\n{}", self.source_text.trim(), next.source_text.trim());
        self.bounds = self.bounds.union(&next.bounds);
        self.render_slots.extend(next.render_slots);
        self.all_advisory_layouts_rect &= next.all_advisory_layouts_rect;
        self.confidence = self
            .confidence
            .min(next.confidence)
            .min(decision.confidence);
        self.evidence.extend(next.evidence);
        self.evidence.push(decision.evidence.to_owned());
        self.evidence.push("SERVER_V4_MERGE".to_owned());
        deduplicate(&mut self.evidence);
        self.translation_unit =
            if self.translation_unit == "PRESERVED" && next.translation_unit == "PRESERVED" {
                "PRESERVED"
            } else {
                "GROUP"
            }
            .to_owned();
        self.regions.extend(next.regions);
        self.last_region = next.last_region;
    }

    fn into_planned(self, index: usize) -> PlannedGroup {
        let group_id = stable_group_id(index, &self.member_region_ids);
        let source_line_count = self.render_slots.len().max(1) as i32;
        let same_ocr_block_flow = is_single_ocr_block_flow(&self.regions);
        let recovered_paragraph = self.evidence.iter().any(|evidence| {
            matches!(
                evidence.as_str(),
                "ESTABLISHED_PARAGRAPH_CONTINUATION"
                    | "COMPACT_PARAGRAPH_TAIL_RECOVERY"
                    | "COMPACT_CROSS_BLOCK_TAIL"
                    | "RELAXED_SAME_BLOCK_WIDE_CONTINUATION"
                    | "COMPACT_SAME_ADVISORY_TAIL"
                    | "RELAXED_SAME_ADVISORY_PARAGRAPH"
                    | "TIGHT_CROSS_BLOCK_CONTINUATION"
            )
        });
        let recovered_main_column = recovered_paragraph
            && is_strict_main_column_paragraph(&self.render_slots, &self.bounds);
        let recovered_advisory_rect = recovered_paragraph
            && self.source_group_ids.len() == 1
            && self.all_advisory_layouts_rect
            && is_natural_wrapped_rect_flow(&self.render_slots, &self.bounds);
        let tight_two_line_continuation =
            self.evidence.iter().any(|evidence| {
                matches!(
                    evidence.as_str(),
                    "VISUAL_LINE_CONTINUATION"
                        | "TIGHT_CROSS_BLOCK_CONTINUATION"
                        | "RELAXED_SAME_BLOCK_WIDE_CONTINUATION"
                )
            }) && is_tight_two_line_wrap(&self.render_slots, &self.bounds);
        let compact_tail_rect = self
            .evidence
            .iter()
            .any(|evidence| {
                matches!(
                    evidence.as_str(),
                    "COMPACT_PARAGRAPH_TAIL_RECOVERY" | "COMPACT_CROSS_BLOCK_TAIL"
                )
            })
            && (is_natural_wrapped_rect_flow(&self.render_slots, &self.bounds)
                || is_compact_two_line_rect(&self.render_slots, &self.bounds));
        let collapsible_cross_group_rect = recovered_main_column
            || recovered_advisory_rect
            || tight_two_line_continuation
            || compact_tail_rect;
        let render_slots = layout_slots(
            &self.render_slots,
            &self.bounds,
            &self.role,
            self.all_advisory_layouts_rect,
            self.source_group_ids.len() > 1
                && !same_ocr_block_flow
                && !collapsible_cross_group_rect,
            has_multiple_typography_tiers(&self.regions) && !collapsible_cross_group_rect,
        );
        let mut grouping_evidence = self.evidence;
        if source_line_count > 1 && render_slots.len() == 1 {
            grouping_evidence.push("DENSE_RECT_LAYOUT_COLLAPSED".to_owned());
            deduplicate(&mut grouping_evidence);
        }
        PlannedGroup {
            group_id,
            source_group_ids: self.source_group_ids,
            member_region_ids: self.member_region_ids,
            role: self.role,
            translation_unit: self.translation_unit,
            source_text: self.source_text,
            reading_order: self.reading_order,
            grouping_confidence: self.confidence,
            grouping_evidence,
            source_line_count,
            bounds: self.bounds,
            layout_shape: if render_slots.len() > 1 {
                "FLOW_SLOTS"
            } else {
                "RECT"
            }
            .to_owned(),
            render_slots,
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
    client_group_profiles: &HashMap<String, ClientGroupProfile>,
) -> Option<MergeDecision> {
    if previous.translation_unit == "PRESERVED"
        || next.translation_unit == "PRESERVED"
        || is_protected_role(&previous.role)
        || is_protected_role(&next.role)
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
    let relaxed_same_block_tail = is_relaxed_same_block_paragraph_tail(previous, next);
    let compact_same_block_tail = is_compact_same_block_tail(previous, next, gap);
    let relaxed_same_block_wide_continuation =
        is_relaxed_same_block_wide_continuation(previous, next, viewport_width);
    let different_client_group = previous
        .source_group_ids
        .iter()
        .all(|group_id| !next.source_group_ids.contains(group_id));
    let different_ocr_block = first.block_id.as_deref().is_some_and(|block_id| {
        second
            .block_id
            .as_deref()
            .is_some_and(|other| other != block_id)
    });
    let same_advisory_group = previous
        .source_group_ids
        .iter()
        .any(|group_id| next.source_group_ids.contains(group_id));
    let tight_cross_block_continuation = different_client_group
        && different_ocr_block
        && is_tight_cross_block_continuation(first, second, gap);
    let compact_cross_block_tail = different_client_group
        && different_ocr_block
        && is_compact_cross_block_tail(previous, next, gap, viewport_width);
    if is_strong_text_boundary(&previous.source_text, &next.source_text)
        && !(same_block && same_advisory_group)
        && !compact_cross_block_tail
    {
        return None;
    }
    let established_paragraph_continuation = is_established_paragraph_continuation(
        previous,
        next,
        gap,
        client_group_profiles,
        same_block,
        same_advisory_group,
    );
    let relaxed_same_advisory_paragraph = is_relaxed_same_advisory_paragraph(
        previous,
        next,
        gap,
        client_group_profiles,
        same_block,
        same_advisory_group,
        viewport_width,
    );
    let compact_same_advisory_tail = same_block
        && same_advisory_group
        && next.regions.len() == 1
        && !ends_sentence(&previous.source_text)
        && next
            .source_text
            .chars()
            .find(|character| character.is_alphanumeric())
            .is_some_and(char::is_lowercase)
        && next
            .source_text
            .chars()
            .filter(|character| character.is_alphanumeric())
            .count()
            <= 20
        && previous.source_group_ids.iter().any(|group_id| {
            next.source_group_ids.contains(group_id)
                && client_group_profiles
                    .get(group_id)
                    .is_some_and(|profile| profile.member_count >= 3)
        });
    // Check the complete accumulated group on every streaming merge. Once the
    // first line of a client group has merged, `different_client_group` becomes
    // false and must not allow later lines to widen the typography envelope.
    if crosses_typography_tier(previous, next)
        && !relaxed_same_block_tail
        && !relaxed_same_block_wide_continuation
        && !established_paragraph_continuation
        && !compact_same_block_tail
        && !compact_cross_block_tail
        && !compact_same_advisory_tail
        && !relaxed_same_advisory_paragraph
    {
        return None;
    }
    if different_client_group
        && different_ocr_block
        && is_cross_group_visual_boundary(previous, next, gap, client_group_profiles)
        && !established_paragraph_continuation
        && !tight_cross_block_continuation
        && !compact_cross_block_tail
    {
        return None;
    }
    let font_compatibility = font_compatibility(first, second);
    let font_ratio = region_text_height(first).min(region_text_height(second))
        / region_text_height(first)
            .max(region_text_height(second))
            .max(1.0);
    let compact_tail_font_recovery = compact_same_block_tail
        || compact_cross_block_tail
        || (established_paragraph_continuation
            && font_compatibility == FontCompatibility::Incompatible
            && next.regions.len() == 1
            && next
                .source_text
                .chars()
                .filter(|character| character.is_alphanumeric())
                .count()
                <= 20
            && font_ratio >= COMPACT_TAIL_MINIMUM_TYPOGRAPHY_RATIO);
    if font_compatibility != FontCompatibility::Strong
        && !(font_compatibility == FontCompatibility::Relaxed
            && (relaxed_same_block_tail
                || relaxed_same_block_wide_continuation
                || established_paragraph_continuation
                || relaxed_same_advisory_paragraph))
        && !compact_tail_font_recovery
    {
        return None;
    }
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
    if previous.role != next.role {
        return None;
    }
    let next_starts_lowercase = next
        .source_text
        .trim_start()
        .chars()
        .find(|character| character.is_alphanumeric())
        .is_some_and(char::is_lowercase);
    let next_continues_text = is_textual_or_acronym_number_continuation(
        &previous.source_text,
        &next.source_text,
    );
    let cross_block_requires_lowercase = different_client_group
        && different_ocr_block
        && !same_advisory_group
        && !established_paragraph_continuation
        && !tight_cross_block_continuation;
    let continuation = if cross_block_requires_lowercase {
        next_continues_text
    } else {
        !ends_sentence(&previous.source_text) || next_starts_lowercase || same_block
    };
    if !continuation {
        return None;
    }
    let confidence = if same_block {
        0.98
    } else if same_advisory_group {
        0.96
    } else if same_column {
        0.94
    } else {
        0.92
    };
    (confidence >= AUTHORITATIVE_CONFIDENCE).then_some(MergeDecision {
        confidence,
        evidence: if compact_tail_font_recovery {
            if compact_cross_block_tail {
                "COMPACT_CROSS_BLOCK_TAIL"
            } else {
                "COMPACT_PARAGRAPH_TAIL_RECOVERY"
            }
        } else if relaxed_same_block_tail {
            "RELAXED_SAME_BLOCK_PARAGRAPH_TAIL"
        } else if compact_same_advisory_tail {
            "COMPACT_SAME_ADVISORY_TAIL"
        } else if relaxed_same_advisory_paragraph {
            "RELAXED_SAME_ADVISORY_PARAGRAPH"
        } else if relaxed_same_block_wide_continuation {
            "RELAXED_SAME_BLOCK_WIDE_CONTINUATION"
        } else if tight_cross_block_continuation {
            "TIGHT_CROSS_BLOCK_CONTINUATION"
        } else if established_paragraph_continuation {
            "ESTABLISHED_PARAGRAPH_CONTINUATION"
        } else if same_block {
            "OCR_BLOCK_CONTINUATION"
        } else if same_advisory_group {
            "CLIENT_GROUP_GEOMETRY_CONFIRMED"
        } else if wrapped_step {
            "WRAPPED_MEDIA_FLOW"
        } else {
            "VISUAL_LINE_CONTINUATION"
        },
    })
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum FontCompatibility {
    Strong,
    Relaxed,
    Incompatible,
}

fn font_compatibility(first: &OcrRegion, second: &OcrRegion) -> FontCompatibility {
    let first_height = region_text_height(first);
    let second_height = region_text_height(second);
    let ratio = first_height.min(second_height) / first_height.max(second_height).max(1.0);
    if ratio >= TYPOGRAPHY_TIER_RATIO {
        FontCompatibility::Strong
    } else if ratio >= RELAXED_TYPOGRAPHY_RATIO {
        FontCompatibility::Relaxed
    } else {
        FontCompatibility::Incompatible
    }
}

fn region_text_height(region: &OcrRegion) -> f32 {
    region
        .estimated_text_height_px
        .filter(|height| height.is_finite() && *height > 0.0)
        .unwrap_or_else(|| region.bounds.height().max(1) as f32)
}

fn is_tight_cross_block_continuation(
    first: &OcrRegion,
    second: &OcrRegion,
    boundary_gap: i32,
) -> bool {
    if font_compatibility(first, second) != FontCompatibility::Strong {
        return false;
    }
    let height = first.bounds.height().max(second.bounds.height()).max(1);
    let overlap = first.bounds.horizontal_overlap(&second.bounds).max(0) as f32
        / first.bounds.width().min(second.bounds.width()).max(1) as f32;
    boundary_gap >= 0
        && boundary_gap <= height * 2 / 3
        && (first.bounds.left - second.bounds.left).abs() <= height / 2
        && overlap >= 0.68
}

fn is_compact_same_block_tail(
    previous: &RegionGroup,
    next: &RegionGroup,
    boundary_gap: i32,
) -> bool {
    if !same_block_continuation(&previous.last_region, &next.first_region)
        || ends_sentence(&previous.source_text)
        || next.regions.len() != 1
        || next
            .source_text
            .chars()
            .filter(|character| character.is_alphanumeric())
            .count()
            > 20
        || !next
            .source_text
            .chars()
            .find(|character| character.is_alphanumeric())
            .is_some_and(char::is_lowercase)
    {
        return false;
    }
    let first_height = region_text_height(&previous.last_region);
    let second_height = region_text_height(&next.first_region);
    let ratio = first_height.min(second_height) / first_height.max(second_height).max(1.0);
    let bounds_height = previous
        .last_region
        .bounds
        .height()
        .max(next.first_region.bounds.height())
        .max(1);
    font_compatibility(&previous.last_region, &next.first_region) == FontCompatibility::Relaxed
        && ratio >= COMPACT_TAIL_MINIMUM_TYPOGRAPHY_RATIO
        && boundary_gap >= 0
        && boundary_gap <= bounds_height / 2
}

fn is_relaxed_same_block_wide_continuation(
    previous: &RegionGroup,
    next: &RegionGroup,
    viewport_width: i32,
) -> bool {
    let first = &previous.last_region;
    let second = &next.first_region;
    let minimum_width = first.bounds.width().min(second.bounds.width()).max(1);
    let maximum_width = first.bounds.width().max(second.bounds.width()).max(1);
    previous.role == "BODY"
        && next.role == "BODY"
        && same_block_continuation(first, second)
        && !ends_sentence(&previous.source_text)
        && second
            .text
            .chars()
            .find(|character| character.is_alphanumeric())
            .is_some_and(char::is_lowercase)
        && font_compatibility(first, second) == FontCompatibility::Relaxed
        && minimum_width >= viewport_width * 2 / 5
        && minimum_width as f32 / maximum_width as f32 >= 0.70
}

fn is_compact_cross_block_tail(
    previous: &RegionGroup,
    next: &RegionGroup,
    boundary_gap: i32,
    viewport_width: i32,
) -> bool {
    let first = &previous.last_region;
    let second = &next.first_region;
    let first_height = region_text_height(first);
    let second_height = region_text_height(second);
    let font_ratio = first_height.min(second_height) / first_height.max(second_height).max(1.0);
    let bounds_height = first.bounds.height().max(second.bounds.height()).max(1);
    !previous.regions.is_empty()
        && next.regions.len() == 1
        && previous.role == "BODY"
        && next.role == "BODY"
        && !ends_sentence(&previous.source_text)
        && is_textual_or_acronym_number_continuation(&previous.source_text, &second.text)
        && second
            .text
            .chars()
            .filter(|character| character.is_alphanumeric())
            .count()
            <= 20
        && font_compatibility(first, second) == FontCompatibility::Relaxed
        && font_ratio >= COMPACT_TAIL_MINIMUM_TYPOGRAPHY_RATIO
        && first.bounds.width() >= viewport_width * 2 / 5
        && first.bounds.width() >= second.bounds.width() * 3
        && (first.bounds.left - second.bounds.left).abs() <= second.bounds.height().max(1)
        && boundary_gap >= 0
        && boundary_gap <= bounds_height / 2
}

fn is_textual_or_acronym_number_continuation(previous: &str, next: &str) -> bool {
    if next
        .chars()
        .find(|character| character.is_alphanumeric())
        .is_some_and(char::is_lowercase)
    {
        return true;
    }
    let compact_next = next
        .chars()
        .filter(|character| character.is_alphanumeric())
        .collect::<String>();
    let previous_token = previous
        .trim_end()
        .chars()
        .rev()
        .take_while(|character| character.is_alphanumeric())
        .collect::<String>();
    !compact_next.is_empty()
        && compact_next.len() <= 6
        && compact_next.chars().all(|character| character.is_ascii_digit())
        && (2..=8).contains(&previous_token.len())
        && previous_token.chars().any(|character| character.is_alphabetic())
        && previous_token
            .chars()
            .all(|character| !character.is_alphabetic() || character.is_uppercase())
}

fn is_established_paragraph_continuation(
    previous: &RegionGroup,
    next: &RegionGroup,
    boundary_gap: i32,
    client_group_profiles: &HashMap<String, ClientGroupProfile>,
    same_block: bool,
    same_advisory_group: bool,
) -> bool {
    if previous.regions.len() < 2
        || previous.role != "BODY"
        || next.role != "BODY"
        || ends_sentence(&previous.source_text)
    {
        return false;
    }
    let different_ocr_block = previous
        .last_region
        .block_id
        .as_deref()
        .zip(next.first_region.block_id.as_deref())
        .is_some_and(|(first, second)| first != second);
    let missing_block_provenance =
        previous.last_region.block_id.is_none() || next.first_region.block_id.is_none();
    let next_starts_lowercase = next
        .source_text
        .trim_start()
        .chars()
        .find(|character| character.is_alphanumeric())
        .is_some_and(char::is_lowercase);
    let next_continues_text = is_textual_or_acronym_number_continuation(
        &previous.source_text,
        &next.source_text,
    );
    let mature_same_advisory_continuation =
        previous.regions.len() >= 3 && same_block && same_advisory_group && next_starts_lowercase;
    let continues_recovered_paragraph = previous.evidence.iter().any(|evidence| {
        matches!(
            evidence.as_str(),
            "ESTABLISHED_PARAGRAPH_CONTINUATION"
                | "RELAXED_SAME_BLOCK_PARAGRAPH_TAIL"
                | "RELAXED_SAME_BLOCK_WIDE_CONTINUATION"
                | "RELAXED_SAME_ADVISORY_PARAGRAPH"
        )
    });
    if !continues_recovered_paragraph
        && !different_ocr_block
        && !(missing_block_provenance && same_advisory_group)
        && !mature_same_advisory_continuation
        && font_compatibility(&previous.last_region, &next.first_region)
            != FontCompatibility::Relaxed
    {
        return false;
    }
    let textual_continuation = same_block || same_advisory_group || next_continues_text;
    if !textual_continuation {
        return false;
    }
    let height = previous
        .last_region
        .bounds
        .height()
        .max(next.first_region.bounds.height())
        .max(1);
    if (previous.last_region.bounds.left - next.first_region.bounds.left).abs() > height * 2 {
        return false;
    }
    let typical_gap = median_internal_gap(&previous.regions).or_else(|| {
        client_group_profiles
            .get(previous.last_region.group_id.as_str())
            .and_then(|profile| profile.median_internal_gap)
    });
    typical_gap.is_none_or(|gap| (boundary_gap - gap).abs() <= (height / 2).max(6))
}

fn is_relaxed_same_advisory_paragraph(
    previous: &RegionGroup,
    next: &RegionGroup,
    boundary_gap: i32,
    client_group_profiles: &HashMap<String, ClientGroupProfile>,
    same_block: bool,
    same_advisory_group: bool,
    viewport_width: i32,
) -> bool {
    if !same_advisory_group
        || previous.role != "BODY"
        || next.role != "BODY"
    {
        return false;
    }
    let Some(profile) = previous.source_group_ids.iter().find_map(|group_id| {
        next.source_group_ids
            .contains(group_id)
            .then(|| client_group_profiles.get(group_id))
            .flatten()
    }) else {
        return false;
    };
    if profile.member_count < 3 {
        return false;
    }
    let boundary_compatibility =
        font_compatibility(&previous.last_region, &next.first_region);
    if boundary_compatibility == FontCompatibility::Relaxed {
        if same_block {
            return true;
        }
        let first = &previous.last_region;
        let second = &next.first_region;
        let height = first.bounds.height().max(second.bounds.height()).max(1);
        return !ends_sentence(&previous.source_text)
            && second
                .text
                .chars()
                .find(|character| character.is_alphanumeric())
                .is_some_and(char::is_lowercase)
            && first.bounds.width() >= viewport_width * 2 / 5
            && second.bounds.width() >= viewport_width * 2 / 5
            && (first.bounds.left - second.bounds.left).abs() <= height
            && boundary_gap >= 0
            && boundary_gap <= height;
    }
    if boundary_compatibility != FontCompatibility::Strong
        || previous.regions.len() < 2
        || ends_sentence(&previous.source_text)
        || profile.sustained_typography_transition_reading_order
            != Some(next.first_region.reading_order)
        || !next
            .source_text
            .chars()
            .find(|character| character.is_alphanumeric())
            .is_some_and(char::is_lowercase)
    {
        return false;
    }
    let mut heights = previous
        .regions
        .iter()
        .chain(&next.regions)
        .map(region_text_height)
        .collect::<Vec<_>>();
    heights.sort_by(f32::total_cmp);
    if heights.first().copied().unwrap_or_default()
        / heights.last().copied().unwrap_or(1.0).max(1.0)
        < RELAXED_TYPOGRAPHY_RATIO
    {
        return false;
    }
    let height = previous
        .last_region
        .bounds
        .height()
        .max(next.first_region.bounds.height())
        .max(1);
    if (previous.last_region.bounds.left - next.first_region.bounds.left).abs() > height * 2 {
        return false;
    }
    profile
        .median_internal_gap
        .is_none_or(|gap| (boundary_gap - gap).abs() <= (height / 2).max(6))
}

fn sustained_typography_transition_reading_order(regions: &[OcrRegion]) -> Option<i32> {
    if regions.len() < 4 || !is_single_ocr_block_flow(regions) {
        return None;
    }
    (2..regions.len() - 1).find_map(|split| {
        let prefix = &regions[..split];
        let suffix = &regions[split..];
        let range_ratio = |values: &[OcrRegion]| {
            let minimum = values
                .iter()
                .map(region_text_height)
                .fold(f32::MAX, f32::min);
            let maximum = values
                .iter()
                .map(region_text_height)
                .fold(0.0_f32, f32::max);
            minimum / maximum.max(1.0)
        };
        let complete_ratio = range_ratio(regions);
        (range_ratio(prefix) >= TYPOGRAPHY_TIER_RATIO
            && range_ratio(suffix) >= TYPOGRAPHY_TIER_RATIO
            && complete_ratio >= RELAXED_TYPOGRAPHY_RATIO
            && complete_ratio < TYPOGRAPHY_TIER_RATIO
            && font_compatibility(&regions[split - 1], &regions[split])
                == FontCompatibility::Strong)
            .then_some(regions[split].reading_order)
    })
}

fn is_cross_group_visual_boundary(
    previous: &RegionGroup,
    next: &RegionGroup,
    boundary_gap: i32,
    client_group_profiles: &HashMap<String, ClientGroupProfile>,
) -> bool {
    let first_height = region_text_height(&previous.last_region);
    let second_height = region_text_height(&next.first_region);
    let boundary_font_ratio =
        first_height.min(second_height) / first_height.max(second_height).max(1.0);
    let previous_group_height = median_region_text_height(&previous.regions);
    let next_group_height = median_region_text_height(&next.regions);
    let group_font_ratio = previous_group_height.min(next_group_height)
        / previous_group_height.max(next_group_height).max(1.0);
    let gap_em = boundary_gap.max(0) as f32 / first_height.min(second_height).max(1.0);
    let typography_and_gap_break = boundary_font_ratio.min(group_font_ratio)
        < CROSS_GROUP_FONT_RATIO
        && gap_em > CROSS_GROUP_MINIMUM_GAP_EM;

    let previous_internal_gap = median_internal_gap(&previous.regions).or_else(|| {
        client_group_profiles
            .get(previous.last_region.group_id.as_str())
            .and_then(|profile| profile.median_internal_gap)
    });
    let next_internal_gap = median_internal_gap(&next.regions).or_else(|| {
        client_group_profiles
            .get(next.first_region.group_id.as_str())
            .and_then(|profile| profile.median_internal_gap)
    });
    let typical_internal_gap = previous_internal_gap
        .into_iter()
        .chain(next_internal_gap)
        .max();
    let gap_discontinuity = typical_internal_gap.is_some_and(|internal_gap| {
        boundary_gap.max(0) as f32
            > internal_gap.max(1) as f32 * CROSS_GROUP_GAP_DISCONTINUITY_RATIO
    });
    typography_and_gap_break || gap_discontinuity
}

fn median_region_text_height(regions: &[OcrRegion]) -> f32 {
    let mut values = regions.iter().map(region_text_height).collect::<Vec<_>>();
    values.sort_by(f32::total_cmp);
    values.get(values.len() / 2).copied().unwrap_or(1.0)
}

fn median_internal_gap(regions: &[OcrRegion]) -> Option<i32> {
    let mut gaps = regions
        .windows(2)
        .map(|pair| pair[1].bounds.top - pair[0].bounds.bottom)
        .filter(|gap| *gap >= 0)
        .collect::<Vec<_>>();
    gaps.sort_unstable();
    gaps.get(gaps.len() / 2).copied()
}

fn has_multiple_typography_tiers(regions: &[OcrRegion]) -> bool {
    if regions
        .iter()
        .all(|region| region.typography_confidence < RELIABLE_TYPOGRAPHY_CONFIDENCE)
    {
        return false;
    }
    let mut heights = regions.iter().map(region_text_height).collect::<Vec<_>>();
    if heights.len() < 2 {
        return false;
    }
    heights.sort_by(f32::total_cmp);
    heights[0] / heights[heights.len() - 1].max(1.0) < TYPOGRAPHY_TIER_RATIO
        && !has_relaxed_terminal_typography_outlier(regions)
}

fn is_relaxed_same_block_paragraph_tail(previous: &RegionGroup, next: &RegionGroup) -> bool {
    if previous.role != "BODY"
        || next.role != "BODY"
        || previous.regions.len() < 3
        || next.regions.len() != 1
        || ends_sentence(&previous.source_text)
        || !same_block_continuation(&previous.last_region, &next.first_region)
    {
        return false;
    }
    let mut previous_heights = previous
        .regions
        .iter()
        .map(region_text_height)
        .collect::<Vec<_>>();
    previous_heights.sort_by(f32::total_cmp);
    let previous_ratio =
        previous_heights[0] / previous_heights[previous_heights.len() - 1].max(1.0);
    let typical_height = previous_heights[previous_heights.len() / 2];
    let tail_height = region_text_height(&next.first_region);
    let tail_ratio = typical_height.min(tail_height) / typical_height.max(tail_height).max(1.0);
    let relaxed_boundary =
        font_compatibility(&previous.last_region, &next.first_region) == FontCompatibility::Relaxed;
    let paragraph_outlier = tail_ratio < TYPOGRAPHY_TIER_RATIO;
    previous_ratio >= TYPOGRAPHY_TIER_RATIO
        && (relaxed_boundary || paragraph_outlier)
        && tail_ratio >= RELAXED_TYPOGRAPHY_RATIO
}

fn has_relaxed_terminal_typography_outlier(regions: &[OcrRegion]) -> bool {
    if regions.len() < 4 || !is_single_ocr_block_flow(regions) {
        return false;
    }
    let (tail, previous) = regions.split_last().expect("checked non-empty regions");
    let mut previous_heights = previous.iter().map(region_text_height).collect::<Vec<_>>();
    previous_heights.sort_by(f32::total_cmp);
    let previous_ratio =
        previous_heights[0] / previous_heights[previous_heights.len() - 1].max(1.0);
    let typical_height = previous_heights[previous_heights.len() / 2];
    let tail_height = region_text_height(tail);
    let tail_ratio = typical_height.min(tail_height) / typical_height.max(tail_height).max(1.0);
    previous_ratio >= TYPOGRAPHY_TIER_RATIO && tail_ratio >= RELAXED_TYPOGRAPHY_RATIO
}

fn is_single_ocr_block_flow(regions: &[OcrRegion]) -> bool {
    regions.len() > 1
        && regions
            .windows(2)
            .all(|pair| same_block_continuation(&pair[0], &pair[1]))
}

fn crosses_typography_tier(previous: &RegionGroup, next: &RegionGroup) -> bool {
    let mut minimum = f32::MAX;
    let mut maximum = 0.0_f32;
    for height in previous
        .regions
        .iter()
        .chain(&next.regions)
        .map(region_text_height)
    {
        minimum = minimum.min(height);
        maximum = maximum.max(height);
    }
    maximum > 0.0 && minimum / maximum < TYPOGRAPHY_TIER_RATIO
}

fn layout_slots(
    source_slots: &[Bounds],
    group_bounds: &Bounds,
    role: &str,
    all_advisory_layouts_rect: bool,
    crosses_client_groups: bool,
    has_multiple_typography_tiers: bool,
) -> Vec<Bounds> {
    if crosses_client_groups || has_multiple_typography_tiers {
        return source_slots.to_vec();
    }
    let is_rect = role == "BODY"
        && (is_dense_rectangular_text_flow(source_slots, group_bounds)
            || (all_advisory_layouts_rect
                && is_natural_wrapped_rect_flow(source_slots, group_bounds)));
    if source_slots.len() <= 1 || !is_rect {
        return source_slots.to_vec();
    }
    vec![group_bounds.clone()]
}

fn is_natural_wrapped_rect_flow(source_slots: &[Bounds], group_bounds: &Bounds) -> bool {
    if source_slots.len() < 3 || group_bounds.width() <= 0 {
        return false;
    }
    let mut heights = source_slots
        .iter()
        .map(|slot| slot.height().max(1))
        .collect::<Vec<_>>();
    heights.sort_unstable();
    let typical_height = heights[heights.len() / 2].max(1);
    let maximum_gap = source_slots
        .windows(2)
        .map(|pair| pair[1].top - pair[0].bottom)
        .max()
        .unwrap_or_default();
    if maximum_gap > typical_height {
        return false;
    }

    let left_tolerance = (typical_height * 2).max(group_bounds.width() * 12 / 100);
    if range(source_slots.iter().map(|slot| slot.left)) > left_tolerance {
        return false;
    }

    let non_final = &source_slots[..source_slots.len() - 1];
    let main_column_width = group_bounds.width() * 72 / 100;
    let wide_line_count = non_final
        .iter()
        .filter(|slot| slot.width() >= main_column_width)
        .count();
    wide_line_count * 2 >= non_final.len()
}

fn is_dense_rectangular_text_flow(source_slots: &[Bounds], group_bounds: &Bounds) -> bool {
    if source_slots.len() < 2 || group_bounds.width() <= 0 {
        return false;
    }
    let mut heights = source_slots
        .iter()
        .map(|slot| slot.height().max(1))
        .collect::<Vec<_>>();
    heights.sort_unstable();
    let typical_height = heights[heights.len() / 2].max(1);
    let maximum_gap = source_slots
        .windows(2)
        .map(|pair| pair[1].top - pair[0].bottom)
        .max()
        .unwrap_or_default();
    if maximum_gap > typical_height {
        return false;
    }

    // Ignore the final line's right edge because a normal paragraph often ends
    // with a short line. Earlier edge steps are retained as FLOW_SLOTS so text
    // cannot flow across an image or another non-text island.
    let non_final = &source_slots[..source_slots.len().saturating_sub(1).max(1)];
    let left_range = range(source_slots.iter().map(|slot| slot.left));
    let right_range = range(non_final.iter().map(|slot| slot.right));
    let left_tolerance = (typical_height * 2).max(group_bounds.width() * 12 / 100);
    let right_tolerance = (typical_height * 3).max(group_bounds.width() * 25 / 100);
    left_range <= left_tolerance && right_range <= right_tolerance
}

fn is_strict_main_column_paragraph(source_slots: &[Bounds], group_bounds: &Bounds) -> bool {
    if source_slots.len() < 3 || group_bounds.width() <= 0 {
        return false;
    }
    let mut heights = source_slots
        .iter()
        .map(|slot| slot.height().max(1))
        .collect::<Vec<_>>();
    heights.sort_unstable();
    let typical_height = heights[heights.len() / 2].max(1);
    if range(source_slots.iter().map(|slot| slot.left)) > typical_height {
        return false;
    }
    let maximum_gap = source_slots
        .windows(2)
        .map(|pair| pair[1].top - pair[0].bottom)
        .max()
        .unwrap_or_default();
    if maximum_gap > typical_height {
        return false;
    }
    if source_slots
        .last()
        .is_none_or(|slot| slot.width() < group_bounds.width() / 2)
    {
        return false;
    }
    let non_final = &source_slots[..source_slots.len() - 1];
    let main_column_width = group_bounds.width() * 70 / 100;
    non_final
        .iter()
        .filter(|slot| slot.width() >= main_column_width)
        .count()
        * 2
        >= non_final.len()
}

fn is_tight_two_line_wrap(source_slots: &[Bounds], group_bounds: &Bounds) -> bool {
    if source_slots.len() != 2 || group_bounds.width() <= 0 {
        return false;
    }
    let first = &source_slots[0];
    let second = &source_slots[1];
    let typical_height = first.height().max(second.height()).max(1);
    let gap = second.top - first.bottom;
    (first.left - second.left).abs() <= (typical_height / 2).max(6)
        && gap >= 0
        && gap <= typical_height
        && first.width() >= group_bounds.width() * 70 / 100
}

fn is_compact_two_line_rect(source_slots: &[Bounds], group_bounds: &Bounds) -> bool {
    if source_slots.len() != 2 || group_bounds.width() <= 0 {
        return false;
    }
    let first = &source_slots[0];
    let second = &source_slots[1];
    let typical_height = first.height().max(second.height()).max(1);
    let gap = second.top - first.bottom;
    (first.left - second.left).abs() <= typical_height * 3 / 2
        && gap >= 0
        && gap <= typical_height / 2
        && first.width() >= group_bounds.width() * 70 / 100
}

fn range(values: impl Iterator<Item = i32>) -> i32 {
    let mut minimum = i32::MAX;
    let mut maximum = i32::MIN;
    for value in values {
        minimum = minimum.min(value);
        maximum = maximum.max(value);
    }
    maximum.saturating_sub(minimum)
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
    if let Some(protected_role) = advisory.and_then(|group| {
        (group.translation_unit == "PRESERVED"
            && matches!(group.role.as_str(), "CONTROL" | "IDENTIFIER"))
        .then(|| group.role.clone())
    }) {
        return protected_role;
    }
    if looks_like_discussion_metadata(
        advisory
            .map(|group| group.source_text.as_str())
            .unwrap_or(text),
    ) {
        return "METADATA".to_owned();
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
    next.trim_start().starts_with('>')
        || starts_list_item(next)
        || looks_like_short_label(previous)
        || looks_like_title_label(previous)
        || (looks_like_short_label(next) && ends_sentence(previous))
        || (looks_like_title_label(next) && ends_sentence(previous))
        || is_standalone_timestamp(previous)
        || is_standalone_timestamp(next)
}

fn starts_list_item(text: &str) -> bool {
    let trimmed = text.trim_start();
    if trimmed.starts_with(['•', '·', '‣', '◦', '*', '-']) {
        return true;
    }
    let prefix = trimmed.split_whitespace().next().unwrap_or_default();
    let marker = prefix.trim_end_matches(['.', ')']);
    prefix.len() > marker.len()
        && !marker.is_empty()
        && (marker.chars().all(|character| character.is_ascii_digit())
            || marker.len() == 1
                && marker.chars().all(|character| character.is_ascii_alphabetic()))
}

fn looks_like_title_label(text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.is_empty()
        || trimmed.ends_with(['.', '!', '?', '。', '！', '？', ',', ';'])
        || trimmed.contains(',')
        || trimmed.contains(';')
    {
        return false;
    }
    let words = trimmed.split_whitespace().collect::<Vec<_>>();
    if words.is_empty() || words.len() > 6 || trimmed.chars().count() > 48 {
        return false;
    }
    let connectors = ["a", "an", "and", "for", "in", "of", "the", "to"];
    let mut meaningful_words = 0;
    words.iter().all(|word| {
        let normalized = word.trim_matches(|character: char| !character.is_alphabetic());
        if normalized.is_empty() {
            return false;
        }
        if connectors.contains(&normalized.to_ascii_lowercase().as_str()) {
            return true;
        }
        meaningful_words += 1;
        normalized.chars().next().is_some_and(char::is_uppercase)
    }) && meaningful_words > 0
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
    if trimmed.ends_with('.')
        && trimmed[..trimmed.len() - 1]
            .chars()
            .all(|character| character.is_ascii_digit())
    {
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
    let trimmed = text.trim_end_matches(char::is_whitespace);
    if let Some(opening) = trimmed.strip_suffix(')').and_then(|value| value.rfind('(')) {
        if ends_with_sentence_terminal(&trimmed[..opening]) {
            return true;
        }
    }
    ends_with_sentence_terminal(trimmed)
}

fn ends_with_sentence_terminal(text: &str) -> bool {
    text.trim_end_matches(|character: char| {
        character.is_whitespace() || matches!(character, ')' | ']' | '}' | '"' | '\'')
    })
    .ends_with(['.', '!', '?', '。', '！', '？'])
}

fn looks_like_discussion_metadata(text: &str) -> bool {
    let normalized = text.to_ascii_lowercase();
    let has_age = [
        "second ago",
        "seconds ago",
        "minute ago",
        "minutes ago",
        "hour ago",
        "hours ago",
        "day ago",
        "days ago",
        "week ago",
        "weeks ago",
    ]
    .iter()
    .any(|age| normalized.contains(age));
    let has_navigation = normalized.contains("parent") && normalized.contains("context");
    let has_subject = normalized.contains("on:") || normalized.contains("on：");
    has_age && has_navigation && has_subject
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
    fn dense_title_keeps_member_flow_slots() {
        let slots = vec![
            Bounds {
                left: 20,
                top: 100,
                right: 620,
                bottom: 150,
            },
            Bounds {
                left: 20,
                top: 158,
                right: 620,
                bottom: 208,
            },
            Bounds {
                left: 20,
                top: 216,
                right: 260,
                bottom: 266,
            },
        ];
        let group_bounds = slots[0].union(&slots[1]).union(&slots[2]);

        assert_eq!(
            layout_slots(&slots, &group_bounds, "TITLE", true, false, false),
            slots
        );
    }

    #[test]
    fn ignores_low_confidence_typography_tiers_for_rectangular_comment_tail() {
        let request = request();
        let mut regions = [41.0, 43.0, 32.0]
            .into_iter()
            .enumerate()
            .map(|(index, height)| {
                let mut region = request.regions[0].clone();
                region.region_id = format!("comment-{index}");
                region.estimated_text_height_px = Some(height);
                region.typography_confidence = 0.45;
                region
            })
            .collect::<Vec<_>>();

        assert!(!has_multiple_typography_tiers(&regions));

        for region in &mut regions {
            region.typography_confidence = 0.9;
        }
        assert!(has_multiple_typography_tiers(&regions));
    }

    #[test]
    fn keeps_same_advisory_comment_merged_across_gradual_font_transition() {
        let mut request = request();
        let specifications = [
            (
                "comment-0",
                0,
                1_439,
                1_486,
                42.0,
                "But then again these systems do not use 1400watts all",
            ),
            (
                "comment-1",
                1,
                1_504,
                1_548,
                39.0,
                "the time.We would probably have much more then 11.2",
            ),
            (
                "comment-2",
                2,
                1_567,
                1_602,
                32.0,
                "terawatts demand if all humans turn on all their electrical",
            ),
            (
                "comment-3",
                3,
                1_629,
                1_664,
                32.0,
                "consumers at the same time.",
            ),
        ];
        let mut regions = specifications
            .into_iter()
            .enumerate()
            .map(|(reading_order, (id, line_index, top, bottom, height, text))| {
                let mut region = request.regions[0].clone();
                region.region_id = id.to_owned();
                region.group_id = "client-comment".to_owned();
                region.block_id = Some("mlkit-comment".to_owned());
                region.line_index = Some(line_index);
                region.reading_order = reading_order as i32;
                region.text = text.to_owned();
                region.raw_text = Some(text.to_owned());
                region.estimated_text_height_px = Some(height);
                region.typography_confidence = 0.82;
                region.bounds = Bounds {
                    left: 64,
                    top,
                    right: if line_index == 3 { 676 } else { 1_247 },
                    bottom,
                };
                region.component_bounds.clear();
                region
            })
            .collect::<Vec<_>>();
        regions[2].bounds.left = 63;
        let mut advisory = request.groups[0].clone();
        advisory.group_id = "client-comment".to_owned();
        advisory.role = "BODY".to_owned();
        advisory.member_region_ids = regions
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        advisory.source_text = regions
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        advisory.bounds = regions
            .iter()
            .skip(1)
            .fold(regions[0].bounds.clone(), |bounds, region| {
                bounds.union(&region.bounds)
            });
        advisory.layout_shape = "RECT".to_owned();
        advisory.render_slots = vec![advisory.bounds.clone()];
        request.regions = regions;
        request.groups = vec![advisory];

        let plan = build_regions_first_plan(&request);

        assert_eq!(1, plan.groups.len());
        assert_eq!(4, plan.groups[0].member_region_ids.len());
        assert_eq!(4, plan.groups[0].source_line_count);
        assert_eq!("RECT", plan.groups[0].layout_shape);
        assert_eq!(1, plan.groups[0].render_slots.len());
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"RELAXED_SAME_ADVISORY_PARAGRAPH".to_owned())
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
    fn keeps_explicitly_different_font_scales_in_separate_groups() {
        let mut request = request();
        let mut first = request.regions[0].clone();
        first.text = "The first paragraph line continues".to_owned();
        first.group_id.clear();
        first.block_id = Some("body".to_owned());
        first.line_index = Some(0);
        first.estimated_text_height_px = Some(30.0);
        let mut second = first.clone();
        second.region_id = "small-line".to_owned();
        second.text = "with visibly smaller text".to_owned();
        second.reading_order += 1;
        second.line_index = Some(1);
        second.estimated_text_height_px = Some(14.0);
        second.bounds.top = first.bounds.bottom + 4;
        second.bounds.bottom = second.bounds.top + first.bounds.height();
        request.groups.clear();
        request.regions = vec![first, second];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
    }

    #[test]
    fn splits_cross_group_blocks_when_font_change_and_gap_mark_a_new_paragraph() {
        let mut request = request();
        let mut regions = Vec::new();
        let specs = [
            (
                "metadata-0",
                "meta",
                0,
                1497,
                1538,
                37.0,
                "A compact header line that remains",
            ),
            (
                "metadata-1",
                "meta",
                1,
                1543,
                1588,
                37.0,
                "visually separate",
            ),
            (
                "body-0",
                "body",
                0,
                1626,
                1677,
                46.0,
                "My partner describes me as a professional problem",
            ),
            (
                "body-1",
                "body",
                1,
                1690,
                1734,
                40.0,
                "solver since I was a child and fixed things",
            ),
        ];
        for (index, (region_id, block_id, line_index, top, bottom, height, text)) in
            specs.into_iter().enumerate()
        {
            let mut region = request.regions[0].clone();
            region.region_id = region_id.to_owned();
            region.group_id = if block_id == "meta" {
                "client-meta"
            } else {
                "client-body"
            }
            .to_owned();
            region.block_id = Some(block_id.to_owned());
            region.line_index = Some(line_index);
            region.reading_order = index as i32;
            region.text = text.to_owned();
            region.estimated_text_height_px = Some(height);
            region.typography_confidence = 0.82;
            region.component_bounds.clear();
            region.bounds = Bounds {
                left: 64,
                top,
                right: 1_240,
                bottom,
            };
            regions.push(region);
        }
        let mut metadata = request.groups[0].clone();
        metadata.group_id = "client-meta".to_owned();
        metadata.role = "BODY".to_owned();
        metadata.member_region_ids = regions[..2]
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        metadata.source_text = regions[..2]
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        metadata.bounds = regions[0].bounds.union(&regions[1].bounds);
        metadata.render_slots = vec![metadata.bounds.clone()];
        let mut body = metadata.clone();
        body.group_id = "client-body".to_owned();
        body.member_region_ids = regions[2..]
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        body.source_text = regions[2..]
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        body.bounds = regions[2].bounds.union(&regions[3].bounds);
        body.render_slots = vec![body.bounds.clone()];
        request.regions = regions;
        request.groups = vec![metadata, body];
        request.viewport.width = 1_440;
        request.viewport.height = 3_200;
        request.document_context.reading_order_region_ids = request
            .regions
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        request.document_context.text = request
            .regions
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
        assert_eq!(plan.groups[0].source_group_ids, vec!["client-meta"]);
        assert_eq!(plan.groups[1].source_group_ids, vec!["client-body"]);

        let prepared = crate::engine::prepare_translation(
            &serde_json::to_string(&request).unwrap(),
            "openlux",
            "regression-model",
        )
        .unwrap();
        let translations = prepared
            .actionable_groups
            .iter()
            .map(|group| crate::model::ModelTranslation {
                group_id: group.group_id.clone(),
                translated_text: format!("translated {}", group.group_id),
                detected_source_language: "en".to_owned(),
                target_language: "zh".to_owned(),
                failure: None,
            })
            .collect::<Vec<_>>();
        let response = crate::engine::assemble_translation(&prepared, &translations, 10).unwrap();

        assert_eq!(response.results.len(), 2);
        for (result, planned) in response.results.iter().zip(&prepared.document_plan.groups) {
            assert_eq!(result.anchor_bounds, planned.bounds);
            assert_eq!(
                result.layout_hint.source_line_count,
                planned.source_line_count
            );
            assert!(!result.layout_hint.render_slots.is_empty());
        }
        assert_ne!(
            response.results[0].anchor_bounds,
            response.results[1].anchor_bounds
        );
    }

    #[test]
    fn splits_same_ocr_block_when_cross_group_merge_would_cross_typography_tiers() {
        let mut request = request();
        let specs = [
            (
                "comment-0",
                "client-comment-main",
                0,
                643,
                692,
                44.0,
                "Replacing governments,hard sanctions perhaps yes.",
            ),
            (
                "comment-1",
                "client-comment-main",
                1,
                708,
                751,
                39.0,
                "Throwing out an entire people?Why Why not throw out",
            ),
            (
                "comment-2",
                "client-comment-tail",
                2,
                770,
                806,
                32.0,
                "the Palestinians then?",
            ),
        ];
        let regions = specs
            .into_iter()
            .enumerate()
            .map(
                |(index, (region_id, group_id, line_index, top, bottom, height, text))| {
                    let mut region = request.regions[0].clone();
                    region.region_id = region_id.to_owned();
                    region.group_id = group_id.to_owned();
                    region.block_id = Some("mlkit-comment-block".to_owned());
                    region.line_index = Some(line_index);
                    region.reading_order = index as i32;
                    region.text = text.to_owned();
                    region.estimated_text_height_px = Some(height);
                    region.typography_confidence = 0.82;
                    region.component_bounds.clear();
                    region.bounds = Bounds {
                        left: 63,
                        top,
                        right: if index == 2 { 528 } else { 1_284 },
                        bottom,
                    };
                    region
                },
            )
            .collect::<Vec<_>>();
        let mut main = request.groups[0].clone();
        main.group_id = "client-comment-main".to_owned();
        main.role = "BODY".to_owned();
        main.member_region_ids = regions[..2]
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        main.source_text = regions[..2]
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        main.bounds = regions[0].bounds.union(&regions[1].bounds);
        main.render_slots = vec![main.bounds.clone()];
        let mut tail = main.clone();
        tail.group_id = "client-comment-tail".to_owned();
        tail.member_region_ids = vec![regions[2].region_id.clone()];
        tail.source_text = regions[2].text.clone();
        tail.bounds = regions[2].bounds.clone();
        tail.render_slots = vec![tail.bounds.clone()];
        request.regions = regions;
        request.groups = vec![main, tail];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
        assert_eq!(plan.groups[0].source_group_ids, vec!["client-comment-main"]);
        assert_eq!(plan.groups[1].source_group_ids, vec!["client-comment-tail"]);
        assert_eq!(plan.groups[0].layout_shape, "RECT");
        assert_eq!(plan.groups[1].layout_shape, "RECT");
    }

    #[test]
    fn merges_relaxed_singleton_tail_into_same_block_article_paragraph() {
        let mut request = request();
        let specs = [
            (
                "article-0",
                0,
                224,
                294,
                34,
                1386,
                59.0,
                "By leveraging big data and algorithms,the",
            ),
            (
                "article-1",
                1,
                337,
                402,
                30,
                1391,
                59.0,
                "system analyzes basic blood test results to",
            ),
            (
                "article-2",
                2,
                446,
                514,
                28,
                1123,
                62.0,
                "flag potential health risks,offering",
            ),
            (
                "article-3",
                3,
                559,
                631,
                30,
                1236,
                63.0,
                "diagnostic support and enabling early",
            ),
            (
                "article-4",
                4,
                673,
                727,
                33,
                696,
                49.0,
                "medical intervention.",
            ),
        ];
        let regions = specs
            .into_iter()
            .enumerate()
            .map(
                |(index, (region_id, line_index, top, bottom, left, right, height, text))| {
                    let mut region = request.regions[0].clone();
                    region.region_id = region_id.to_owned();
                    region.group_id = if index < 4 {
                        "client-article-main"
                    } else {
                        "client-article-tail"
                    }
                    .to_owned();
                    region.block_id = Some("mlkit-article-body".to_owned());
                    region.line_index = Some(line_index);
                    region.reading_order = index as i32;
                    region.text = text.to_owned();
                    region.estimated_text_height_px = Some(height);
                    region.typography_confidence = 0.82;
                    region.component_bounds.clear();
                    region.bounds = Bounds {
                        left,
                        top,
                        right,
                        bottom,
                    };
                    region
                },
            )
            .collect::<Vec<_>>();
        let mut main = request.groups[0].clone();
        main.group_id = "client-article-main".to_owned();
        main.role = "BODY".to_owned();
        main.member_region_ids = regions[..4]
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        main.source_text = regions[..4]
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        main.bounds = regions[..4]
            .iter()
            .skip(1)
            .fold(regions[0].bounds.clone(), |bounds, region| {
                bounds.union(&region.bounds)
            });
        main.render_slots = vec![main.bounds.clone()];
        let mut tail = main.clone();
        tail.group_id = "client-article-tail".to_owned();
        tail.member_region_ids = vec![regions[4].region_id.clone()];
        tail.source_text = regions[4].text.clone();
        tail.bounds = regions[4].bounds.clone();
        tail.render_slots = vec![tail.bounds.clone()];
        request.regions = regions;
        request.groups = vec![main, tail];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].member_region_ids.len(), 5);
        assert_eq!(plan.groups[0].source_line_count, 5);
        assert_eq!(plan.groups[0].layout_shape, "RECT");
        assert_eq!(plan.groups[0].render_slots.len(), 1);
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"RELAXED_SAME_BLOCK_PARAGRAPH_TAIL".to_owned())
        );

        for (region, height) in request
            .regions
            .iter_mut()
            .zip([71.0, 62.0, 65.0, 61.0, 49.0])
        {
            region.estimated_text_height_px = Some(height);
        }
        let paragraph_outlier_plan = build_regions_first_plan(&request);

        assert_eq!(paragraph_outlier_plan.groups.len(), 1);
        assert_eq!(paragraph_outlier_plan.groups[0].member_region_ids.len(), 5);
        assert_eq!(paragraph_outlier_plan.groups[0].layout_shape, "RECT");
        assert!(
            paragraph_outlier_plan.groups[0]
                .grouping_evidence
                .contains(&"RELAXED_SAME_BLOCK_PARAGRAPH_TAIL".to_owned())
        );
    }

    #[test]
    fn keeps_title_case_first_line_inside_same_advisory_article_paragraph() {
        let mut request = request();
        let specs = [
            (
                "The Haidong Road Community Health",
                0,
                30,
                1302,
                1236,
                1364,
                56.0,
            ),
            (
                "Center in Hohhot,capital of Inner",
                1,
                30,
                1414,
                1091,
                1477,
                56.0,
            ),
            (
                "Mongolia Autonomous Region,is one of",
                2,
                34,
                1527,
                1301,
                1589,
                56.0,
            ),
            (
                "the pioneering clinics showing how",
                3,
                29,
                1639,
                1157,
                1704,
                59.0,
            ),
            (
                "technology is reshaping everyday life,",
                4,
                30,
                1751,
                1224,
                1820,
                62.0,
            ),
            (
                "thanks to the region's burgeoning green",
                5,
                30,
                1853,
                1301,
                1937,
                65.0,
            ),
            ("computing infrastructure.", 6, 31, 1968, 834, 2048, 68.0),
        ];
        let regions = specs
            .into_iter()
            .enumerate()
            .map(
                |(index, (text, line_index, left, top, right, bottom, height))| {
                    let mut region = request.regions[0].clone();
                    region.region_id = format!("haidong-{index}");
                    region.group_id = "client-haidong".to_owned();
                    region.block_id = Some("mlkit-haidong".to_owned());
                    region.line_index = Some(line_index);
                    region.reading_order = index as i32;
                    region.text = text.to_owned();
                    region.estimated_text_height_px = Some(height);
                    region.typography_confidence = 0.82;
                    region.component_bounds.clear();
                    region.bounds = Bounds {
                        left,
                        top,
                        right,
                        bottom,
                    };
                    region
                },
            )
            .collect::<Vec<_>>();
        let mut advisory = request.groups[0].clone();
        advisory.group_id = "client-haidong".to_owned();
        advisory.role = "BODY".to_owned();
        advisory.member_region_ids = regions
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        advisory.source_text = regions
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        advisory.bounds = regions
            .iter()
            .skip(1)
            .fold(regions[0].bounds.clone(), |bounds, region| {
                bounds.union(&region.bounds)
            });
        advisory.render_slots = vec![advisory.bounds.clone()];
        request.regions = regions;
        request.groups = vec![advisory];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].member_region_ids.len(), 7);
        assert_eq!(plan.groups[0].source_line_count, 7);
        assert_eq!(plan.groups[0].layout_shape, "RECT");

        for (region, height) in request
            .regions
            .iter_mut()
            .zip([56.0, 59.0, 61.0, 64.0, 73.0, 65.0, 67.0])
        {
            region.estimated_text_height_px = Some(height);
        }
        let mature_paragraph_plan = build_regions_first_plan(&request);

        assert_eq!(mature_paragraph_plan.groups.len(), 1);
        assert_eq!(mature_paragraph_plan.groups[0].member_region_ids.len(), 7);
        assert_eq!(mature_paragraph_plan.groups[0].layout_shape, "RECT");
    }

    #[test]
    fn recovers_established_article_paragraph_across_ocr_block_fragments() {
        let mut request = request();
        let specs = [
            (
                "This Al-driven transformation is taking",
                "block-a",
                0,
                31,
                2156,
                1248,
                2218,
                56.0,
            ),
            (
                "place across multiple sectors.In",
                "block-a",
                1,
                33,
                2268,
                1048,
                2330,
                56.0,
            ),
            (
                "classrooms at school affiliated with Inner",
                "block-b",
                0,
                32,
                2381,
                1394,
                2434,
                48.0,
            ),
            (
                "Mongolia Normal University,smart",
                "block-b",
                1,
                66,
                2483,
                1132,
                2562,
                71.0,
            ),
            (
                "blackboards respond to voice commands,",
                "block-c",
                0,
                33,
                2604,
                1350,
                2671,
                60.0,
            ),
            (
                "while Al systems analyze student",
                "block-c",
                1,
                29,
                2717,
                1086,
                2786,
                62.0,
            ),
            (
                "performance in real time and create",
                "block-c",
                2,
                33,
                2824,
                1179,
                2887,
                56.0,
            ),
            (
                "personalized learning plans.",
                "block-d",
                0,
                33,
                2941,
                917,
                3010,
                62.0,
            ),
        ];
        let client_groups = [
            "client-a", "client-a", "client-b", "client-c", "client-d", "client-d", "client-d",
            "client-e",
        ];
        let regions = specs
            .into_iter()
            .enumerate()
            .map(
                |(index, (text, block_id, line_index, left, top, right, bottom, height))| {
                    let mut region = request.regions[0].clone();
                    region.region_id = format!("article-flow-{index}");
                    region.group_id = client_groups[index].to_owned();
                    region.block_id = Some(block_id.to_owned());
                    region.line_index = Some(line_index);
                    region.reading_order = index as i32;
                    region.text = text.to_owned();
                    region.estimated_text_height_px = Some(height);
                    region.typography_confidence = 0.82;
                    region.component_bounds.clear();
                    region.bounds = Bounds {
                        left,
                        top,
                        right,
                        bottom,
                    };
                    region
                },
            )
            .collect::<Vec<_>>();
        let mut advisories = Vec::new();
        for group_id in client_groups.iter().copied().collect::<HashSet<_>>() {
            let members = regions
                .iter()
                .filter(|region| region.group_id == group_id)
                .collect::<Vec<_>>();
            let mut advisory = request.groups[0].clone();
            advisory.group_id = group_id.to_owned();
            advisory.role = "BODY".to_owned();
            advisory.member_region_ids = members
                .iter()
                .map(|region| region.region_id.clone())
                .collect();
            advisory.source_text = members
                .iter()
                .map(|region| region.text.as_str())
                .collect::<Vec<_>>()
                .join("\n");
            advisory.bounds = members
                .iter()
                .skip(1)
                .fold(members[0].bounds.clone(), |bounds, region| {
                    bounds.union(&region.bounds)
                });
            advisory.render_slots = vec![advisory.bounds.clone()];
            advisories.push(advisory);
        }
        advisories.sort_by_key(|group| group.bounds.top);
        request.regions = regions;
        request.groups = advisories;

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].member_region_ids.len(), 8);
        assert_eq!(plan.groups[0].source_line_count, 8);
        assert_eq!(plan.groups[0].layout_shape, "RECT");
        assert_eq!(plan.groups[0].render_slots.len(), 1);
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"ESTABLISHED_PARAGRAPH_CONTINUATION".to_owned())
        );

        for (region, height) in request
            .regions
            .iter_mut()
            .zip([60.0, 61.0, 48.0, 66.0, 62.0, 56.0, 61.0, 66.0])
        {
            region.group_id = "client-single-block".to_owned();
            region.block_id = Some("mlkit-single-block".to_owned());
            region.estimated_text_height_px = Some(height);
        }
        let mut single_advisory = request.groups[0].clone();
        single_advisory.group_id = "client-single-block".to_owned();
        single_advisory.member_region_ids = request
            .regions
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        single_advisory.source_text = request
            .regions
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        single_advisory.bounds = request
            .regions
            .iter()
            .skip(1)
            .fold(request.regions[0].bounds.clone(), |bounds, region| {
                bounds.union(&region.bounds)
            });
        single_advisory.render_slots = vec![single_advisory.bounds.clone()];
        request.groups = vec![single_advisory];

        let single_block_plan = build_regions_first_plan(&request);

        assert_eq!(single_block_plan.groups.len(), 1);
        assert_eq!(single_block_plan.groups[0].member_region_ids.len(), 8);
        assert_eq!(single_block_plan.groups[0].layout_shape, "RECT");

        for (region, height) in request
            .regions
            .iter_mut()
            .zip([53.0, 59.0, 61.0, 71.0, 59.0, 56.0, 59.0, 62.0])
        {
            region.block_id = None;
            region.estimated_text_height_px = Some(height);
        }
        let missing_block_plan = build_regions_first_plan(&request);

        assert_eq!(missing_block_plan.groups.len(), 1);
        assert_eq!(missing_block_plan.groups[0].member_region_ids.len(), 8);
        assert_eq!(missing_block_plan.groups[0].layout_shape, "RECT");

        for (index, (region, height)) in request
            .regions
            .iter_mut()
            .zip([32.0, 46.0, 36.0, 45.0, 41.0, 41.0, 40.0, 41.0])
            .enumerate()
        {
            region.block_id = Some("long-comment-block".to_owned());
            region.line_index = Some(index as i32);
            region.estimated_text_height_px = Some(height);
        }
        let relaxed_first_line_plan = build_regions_first_plan(&request);

        assert_eq!(relaxed_first_line_plan.groups.len(), 1);
        assert_eq!(relaxed_first_line_plan.groups[0].member_region_ids.len(), 8);
        assert_eq!(relaxed_first_line_plan.groups[0].layout_shape, "RECT");
        assert!(
            relaxed_first_line_plan.groups[0]
                .grouping_evidence
                .contains(&"RELAXED_SAME_ADVISORY_PARAGRAPH".to_owned())
        );

        request.regions.last_mut().unwrap().bounds.right = 260;
        let short_final_line_plan = build_regions_first_plan(&request);

        assert_eq!(short_final_line_plan.groups.len(), 1);
        assert_eq!(short_final_line_plan.groups[0].layout_shape, "RECT");
        assert_eq!(short_final_line_plan.groups[0].render_slots.len(), 1);
    }

    #[test]
    fn uses_the_complete_next_client_group_gap_profile_before_merging() {
        let mut request = request();
        let specs = [
            (
                "quote",
                "client-quote",
                "quote-block",
                0,
                1792,
                1838,
                41.0,
                107,
                586,
                "dangerous or polluting",
            ),
            (
                "body-0",
                "client-body",
                "body-block",
                0,
                1879,
                1921,
                38.0,
                68,
                1270,
                "IIRC more than half of cars sold in China are EV or PHEV.",
            ),
            (
                "body-1",
                "client-body",
                "body-block",
                1,
                1940,
                1986,
                41.0,
                82,
                1262,
                "I am receptive to learn that there are PHEVs that are too",
            ),
            (
                "body-2",
                "client-body",
                "body-block",
                2,
                2002,
                2048,
                41.0,
                67,
                1230,
                "polluting to be sold in the west, but I would love to see",
            ),
            (
                "body-3",
                "client-body",
                "body-block",
                3,
                2068,
                2103,
                32.0,
                63,
                246,
                "the data.",
            ),
        ];
        let regions = specs
            .into_iter()
            .enumerate()
            .map(
                |(
                    index,
                    (
                        region_id,
                        group_id,
                        block_id,
                        line_index,
                        top,
                        bottom,
                        height,
                        left,
                        right,
                        text,
                    ),
                )| {
                    let mut region = request.regions[0].clone();
                    region.region_id = region_id.to_owned();
                    region.group_id = group_id.to_owned();
                    region.block_id = Some(block_id.to_owned());
                    region.line_index = Some(line_index);
                    region.reading_order = index as i32;
                    region.text = text.to_owned();
                    region.estimated_text_height_px = Some(height);
                    region.typography_confidence = 0.82;
                    region.bounds = Bounds {
                        left,
                        top,
                        right,
                        bottom,
                    };
                    region
                },
            )
            .collect::<Vec<_>>();
        let mut quote = request.groups[0].clone();
        quote.group_id = "client-quote".to_owned();
        quote.role = "BODY".to_owned();
        quote.member_region_ids = vec!["quote".to_owned()];
        quote.source_text = regions[0].text.clone();
        quote.bounds = regions[0].bounds.clone();
        quote.render_slots = vec![quote.bounds.clone()];
        let mut body = quote.clone();
        body.group_id = "client-body".to_owned();
        body.member_region_ids = regions[1..]
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        body.source_text = regions[1..]
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        body.bounds = regions[1..]
            .iter()
            .skip(1)
            .fold(regions[1].bounds.clone(), |bounds, region| {
                bounds.union(&region.bounds)
            });
        body.render_slots = vec![body.bounds.clone()];
        request.regions = regions;
        request.groups = vec![quote, body];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
        assert_eq!(plan.groups[0].source_group_ids, vec!["client-quote"]);
        assert_eq!(plan.groups[0].member_region_ids, vec!["quote"]);
        assert_eq!(plan.groups[1].source_group_ids, vec!["client-body"]);
        assert_eq!(plan.groups[1].member_region_ids.len(), 4);
        assert_eq!(plan.groups[1].layout_shape, "RECT");
    }

    #[test]
    fn rejects_gradual_font_drift_across_the_accumulated_group() {
        let mut request = request();
        let heights = [42.0, 40.0, 32.0];
        let texts = [
            "This paragraph starts with the larger type and continues",
            "through a locally compatible middle line before",
            "a visibly smaller typography tier begins.",
        ];
        let mut regions = Vec::new();
        for index in 0..3 {
            let mut region = request.regions[0].clone();
            region.region_id = format!("drift-{index}");
            region.group_id = "client-drift".to_owned();
            region.block_id = Some("same-ocr-block".to_owned());
            region.line_index = Some(index as i32);
            region.reading_order = index as i32;
            region.text = texts[index].to_owned();
            region.estimated_text_height_px = Some(heights[index]);
            region.typography_confidence = 0.9;
            region.bounds = Bounds {
                left: 64,
                top: 500 + index as i32 * 52,
                right: 1_260,
                bottom: 545 + index as i32 * 52,
            };
            regions.push(region);
        }
        let mut advisory = request.groups[0].clone();
        advisory.group_id = "client-drift".to_owned();
        advisory.role = "BODY".to_owned();
        advisory.member_region_ids = regions
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        advisory.source_text = regions
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        advisory.bounds = regions[0]
            .bounds
            .union(&regions[1].bounds)
            .union(&regions[2].bounds);
        advisory.render_slots = vec![advisory.bounds.clone()];
        request.regions = regions;
        request.groups = vec![advisory];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
        assert_eq!(plan.groups[0].member_region_ids.len(), 2);
        assert_eq!(plan.groups[1].member_region_ids, vec!["drift-2"]);
    }

    #[test]
    fn collapses_tight_two_line_cross_block_continuation_to_rect() {
        let mut request = request();
        let mut first = request.regions[0].clone();
        first.region_id = "paragraph-a".to_owned();
        first.group_id = "client-a".to_owned();
        first.block_id = Some("block-a".to_owned());
        first.line_index = Some(0);
        first.reading_order = 0;
        first.text = "A paragraph can continue across an OCR".to_owned();
        first.estimated_text_height_px = Some(42.0);
        first.bounds = Bounds {
            left: 64,
            top: 500,
            right: 1_200,
            bottom: 550,
        };
        let mut second = first.clone();
        second.region_id = "paragraph-b".to_owned();
        second.group_id = "client-b".to_owned();
        second.block_id = Some("block-b".to_owned());
        second.reading_order = 1;
        second.text = "block when typography and spacing remain continuous.".to_owned();
        second.bounds = Bounds {
            left: 64,
            top: 594,
            right: 414,
            bottom: 639,
        };
        let mut first_group = request.groups[0].clone();
        first_group.group_id = first.group_id.clone();
        first_group.role = "BODY".to_owned();
        first_group.member_region_ids = vec![first.region_id.clone()];
        first_group.source_text = first.text.clone();
        first_group.bounds = first.bounds.clone();
        first_group.render_slots = vec![first.bounds.clone()];
        let mut second_group = first_group.clone();
        second_group.group_id = second.group_id.clone();
        second_group.member_region_ids = vec![second.region_id.clone()];
        second_group.source_text = second.text.clone();
        second_group.bounds = second.bounds.clone();
        second_group.render_slots = vec![second.bounds.clone()];
        request.regions = vec![first, second];
        request.groups = vec![first_group, second_group];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].source_group_ids.len(), 2);
        assert_eq!(plan.groups[0].layout_shape, "RECT");
        assert_eq!(plan.groups[0].render_slots.len(), 1);

        request.regions[0].text = "Nearly a year of full time work at 40hrs/week went".to_owned();
        request.regions[0].block_id = Some("quote-block".to_owned());
        request.regions[0].line_index = Some(0);
        request.regions[0].estimated_text_height_px = Some(48.0);
        request.regions[0].bounds = Bounds {
            left: 109,
            top: 2671,
            right: 1220,
            bottom: 2724,
        };
        request.regions[1].text = "into this".to_owned();
        request.regions[1].block_id = Some("quote-block".to_owned());
        request.regions[1].line_index = Some(1);
        request.regions[1].estimated_text_height_px = Some(32.0);
        request.regions[1].bounds = Bounds {
            left: 67,
            top: 2742,
            right: 233,
            bottom: 2778,
        };
        for (group, region) in request.groups.iter_mut().zip(&request.regions) {
            group.source_text = region.text.clone();
            group.bounds = region.bounds.clone();
            group.render_slots = vec![region.bounds.clone()];
        }

        let compact_quote_plan = build_regions_first_plan(&request);

        assert_eq!(compact_quote_plan.groups.len(), 1);
        assert_eq!(compact_quote_plan.groups[0].layout_shape, "RECT");
        assert!(
            compact_quote_plan.groups[0]
                .grouping_evidence
                .contains(&"COMPACT_PARAGRAPH_TAIL_RECOVERY".to_owned())
        );

        request.regions[0].text = "one HTML file".to_owned();
        request.regions[0].block_id = Some("story-title".to_owned());
        request.regions[0].line_index = Some(1);
        request.regions[0].estimated_text_height_px = Some(32.0);
        request.regions[0].bounds = Bounds {
            left: 64,
            top: 563,
            right: 299,
            bottom: 602,
        };
        request.regions[1].text = "Reminds me of old MUDs but elevated with proper".to_owned();
        request.regions[1].block_id = Some("comment-body".to_owned());
        request.regions[1].line_index = Some(0);
        request.regions[1].estimated_text_height_px = Some(32.0);
        request.regions[1].bounds = Bounds {
            left: 67,
            top: 647,
            right: 1197,
            bottom: 679,
        };
        for (group, region) in request.groups.iter_mut().zip(&request.regions) {
            group.source_text = region.text.clone();
            group.bounds = region.bounds.clone();
            group.render_slots = vec![region.bounds.clone()];
        }

        let uppercase_cross_block_plan = build_regions_first_plan(&request);

        assert_eq!(uppercase_cross_block_plan.groups.len(), 2);
    }

    #[test]
    fn recovers_compact_cross_block_comment_tail_into_rect() {
        let mut request = request();
        let specs = [
            (
                "comment-0",
                "client-main",
                "block-main",
                0,
                "the primary difference between the free and the paid",
                40.0,
                63,
                1764,
                1197,
                1809,
            ),
            (
                "comment-1",
                "client-main",
                "block-main",
                1,
                "versions should be the amount of inference quota you",
                47.0,
                77,
                1821,
                1213,
                1873,
            ),
            (
                "comment-2",
                "client-tail",
                "block-tail",
                0,
                "have access to",
                30.0,
                67,
                1891,
                374,
                1924,
            ),
        ];
        let regions = specs
            .into_iter()
            .enumerate()
            .map(
                |(
                    index,
                    (
                        region_id,
                        group_id,
                        block_id,
                        line_index,
                        text,
                        height,
                        left,
                        top,
                        right,
                        bottom,
                    ),
                )| {
                    let mut region = request.regions[0].clone();
                    region.region_id = region_id.to_owned();
                    region.group_id = group_id.to_owned();
                    region.block_id = Some(block_id.to_owned());
                    region.line_index = Some(line_index);
                    region.reading_order = index as i32;
                    region.text = text.to_owned();
                    region.estimated_text_height_px = Some(height);
                    region.typography_confidence = 0.82;
                    region.bounds = Bounds {
                        left,
                        top,
                        right,
                        bottom,
                    };
                    region
                },
            )
            .collect::<Vec<_>>();
        let mut main = request.groups[0].clone();
        main.group_id = "client-main".to_owned();
        main.role = "BODY".to_owned();
        main.member_region_ids = regions[..2]
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        main.source_text = regions[..2]
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        main.bounds = regions[0].bounds.union(&regions[1].bounds);
        main.render_slots = vec![main.bounds.clone()];
        let mut tail = main.clone();
        tail.group_id = "client-tail".to_owned();
        tail.member_region_ids = vec![regions[2].region_id.clone()];
        tail.source_text = regions[2].text.clone();
        tail.bounds = regions[2].bounds.clone();
        tail.render_slots = vec![tail.bounds.clone()];
        request.regions = regions;
        request.groups = vec![main, tail];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].member_region_ids.len(), 3);
        assert_eq!(plan.groups[0].layout_shape, "RECT");
        assert_eq!(plan.groups[0].render_slots.len(), 1);
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"COMPACT_PARAGRAPH_TAIL_RECOVERY".to_owned())
        );

        let tight_specs = [
            (
                "Most of us here on HN also have access to those",
                "client-head",
                "block-head",
                0,
                33.0,
                Bounds {
                    left: 67,
                    top: 2062,
                    right: 1101,
                    bottom: 2099,
                },
            ),
            (
                "$200/mo models and we can prompt them ourselves if",
                "client-body",
                "block-body",
                0,
                41.0,
                Bounds {
                    left: 64,
                    top: 2123,
                    right: 1238,
                    bottom: 2169,
                },
            ),
            (
                "we are interested in an LLM's take on the question.",
                "client-body",
                "block-body",
                1,
                41.0,
                Bounds {
                    left: 63,
                    top: 2183,
                    right: 1147,
                    bottom: 2229,
                },
            ),
        ];
        for (region, (text, group_id, block_id, line_index, height, bounds)) in
            request.regions.iter_mut().zip(tight_specs)
        {
            region.text = text.to_owned();
            region.group_id = group_id.to_owned();
            region.block_id = Some(block_id.to_owned());
            region.line_index = Some(line_index);
            region.estimated_text_height_px = Some(height);
            region.bounds = bounds;
        }
        request.groups[0].group_id = "client-head".to_owned();
        request.groups[0].member_region_ids = vec![request.regions[0].region_id.clone()];
        request.groups[0].source_text = request.regions[0].text.clone();
        request.groups[0].bounds = request.regions[0].bounds.clone();
        request.groups[0].render_slots = vec![request.regions[0].bounds.clone()];
        request.groups[1].group_id = "client-body".to_owned();
        request.groups[1].member_region_ids = request.regions[1..]
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        request.groups[1].source_text = request.regions[1..]
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        request.groups[1].bounds = request.regions[1].bounds.union(&request.regions[2].bounds);
        request.groups[1].render_slots = vec![request.groups[1].bounds.clone()];

        let tight_cross_block_plan = build_regions_first_plan(&request);

        assert_eq!(tight_cross_block_plan.groups.len(), 1);
        assert_eq!(tight_cross_block_plan.groups[0].member_region_ids.len(), 3);
        assert_eq!(tight_cross_block_plan.groups[0].layout_shape, "RECT");
        assert!(
            tight_cross_block_plan.groups[0]
                .grouping_evidence
                .contains(&"TIGHT_CROSS_BLOCK_CONTINUATION".to_owned())
        );

        let latin_specs = [
            (
                "The standard dictionary of Latin doesn't mention this",
                43.0,
            ),
            ("particular sense.negative is harder to cite,but you", 39.0),
            ("can check it here:", 32.0),
        ];
        for (index, (region, (text, height))) in
            request.regions.iter_mut().zip(latin_specs).enumerate()
        {
            region.text = text.to_owned();
            region.group_id = "client-latin".to_owned();
            region.block_id = Some("latin-block".to_owned());
            region.line_index = Some(index as i32);
            region.estimated_text_height_px = Some(height);
        }
        let mut latin_group = request.groups[0].clone();
        latin_group.group_id = "client-latin".to_owned();
        latin_group.member_region_ids = request
            .regions
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        latin_group.source_text = request
            .regions
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        latin_group.bounds = request
            .regions
            .iter()
            .skip(1)
            .fold(request.regions[0].bounds.clone(), |bounds, region| {
                bounds.union(&region.bounds)
            });
        latin_group.render_slots = vec![latin_group.bounds.clone()];
        request.groups = vec![latin_group];

        let compact_same_advisory_plan = build_regions_first_plan(&request);

        assert_eq!(compact_same_advisory_plan.groups.len(), 1);
        assert_eq!(compact_same_advisory_plan.groups[0].layout_shape, "RECT");
        assert!(
            compact_same_advisory_plan.groups[0]
                .grouping_evidence
                .contains(&"COMPACT_SAME_ADVISORY_TAIL".to_owned())
        );
    }

    #[test]
    fn recovers_relaxed_same_block_and_acronym_number_tail_into_rect() {
        let mut request = request();
        let mut first = request.regions[0].clone();
        first.region_id = "comment-main".to_owned();
        first.group_id = "client-main".to_owned();
        first.block_id = Some("block-main".to_owned());
        first.line_index = Some(1);
        first.reading_order = 0;
        first.text = "I assume you linked to this because a new result was".to_owned();
        first.estimated_text_height_px = Some(36.0);
        first.typography_confidence = 0.82;
        first.bounds = Bounds {
            left: 67,
            top: 1_496,
            right: 1_192,
            bottom: 1_536,
        };
        let mut second = first.clone();
        second.region_id = "comment-middle".to_owned();
        second.group_id = "client-middle".to_owned();
        second.line_index = Some(2);
        second.reading_order = 1;
        second.text = "apparently just found(today?!),a factorization of RSA".to_owned();
        second.estimated_text_height_px = Some(49.0);
        second.bounds = Bounds {
            left: 64,
            top: 1_551,
            right: 1_219,
            bottom: 1_606,
        };
        let mut tail = second.clone();
        tail.region_id = "comment-tail".to_owned();
        tail.group_id = "client-tail".to_owned();
        tail.block_id = Some("block-tail".to_owned());
        tail.line_index = Some(0);
        tail.reading_order = 2;
        tail.text = "260.".to_owned();
        tail.estimated_text_height_px = Some(32.0);
        tail.typography_confidence = 0.45;
        tail.bounds = Bounds {
            left: 66,
            top: 1_620,
            right: 149,
            bottom: 1_656,
        };
        let mut first_group = request.groups[0].clone();
        first_group.group_id = first.group_id.clone();
        first_group.role = "BODY".to_owned();
        first_group.member_region_ids = vec![first.region_id.clone()];
        first_group.source_text = first.text.clone();
        first_group.bounds = first.bounds.clone();
        first_group.render_slots = vec![first.bounds.clone()];
        let mut second_group = first_group.clone();
        second_group.group_id = second.group_id.clone();
        second_group.member_region_ids = vec![second.region_id.clone()];
        second_group.source_text = second.text.clone();
        second_group.bounds = second.bounds.clone();
        second_group.render_slots = vec![second.bounds.clone()];
        let mut tail_group = second_group.clone();
        tail_group.group_id = tail.group_id.clone();
        tail_group.member_region_ids = vec![tail.region_id.clone()];
        tail_group.source_text = tail.text.clone();
        tail_group.bounds = tail.bounds.clone();
        tail_group.render_slots = vec![tail.bounds.clone()];
        request.regions = vec![first, second, tail];
        request.groups = vec![first_group, second_group, tail_group];

        let plan = build_regions_first_plan(&request);

        assert_eq!(1, plan.groups.len());
        assert_eq!(3, plan.groups[0].member_region_ids.len());
        assert_eq!("RECT", plan.groups[0].layout_shape);
        assert_eq!(1, plan.groups[0].render_slots.len());
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"COMPACT_CROSS_BLOCK_TAIL".to_owned())
        );
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"RELAXED_SAME_BLOCK_WIDE_CONTINUATION".to_owned())
        );
    }

    #[test]
    fn recognizes_hacker_news_discussion_metadata_as_a_separate_role() {
        let mut request = request();
        let mut metadata = request.regions[0].clone();
        metadata.region_id = "hn-meta-0".to_owned();
        metadata.group_id = "hn-meta".to_owned();
        metadata.block_id = Some("hn-meta-block".to_owned());
        metadata.line_index = Some(0);
        metadata.reading_order = 0;
        metadata.text =
            "CrzyLngPwd 2 hours ago parent context on:Why aren't smart people".to_owned();
        metadata.bounds = Bounds {
            left: 64,
            top: 1497,
            right: 1_342,
            bottom: 1538,
        };
        metadata.estimated_text_height_px = Some(37.0);
        let mut metadata_tail = metadata.clone();
        metadata_tail.region_id = "hn-meta-1".to_owned();
        metadata_tail.line_index = Some(1);
        metadata_tail.reading_order = 1;
        metadata_tail.text = "happier?(2022)".to_owned();
        metadata_tail.bounds = Bounds {
            left: 64,
            top: 1543,
            right: 324,
            bottom: 1588,
        };
        let mut body = metadata.clone();
        body.region_id = "hn-body-0".to_owned();
        body.group_id = "hn-body".to_owned();
        // Even a bad OCR block assignment must not bridge METADATA and BODY.
        body.block_id = Some("hn-meta-block".to_owned());
        body.line_index = Some(2);
        body.reading_order = 2;
        body.text = "My partner describes me as a professional problem".to_owned();
        body.bounds = Bounds {
            left: 67,
            top: 1626,
            right: 1_154,
            bottom: 1677,
        };
        body.estimated_text_height_px = Some(46.0);
        let mut metadata_group = request.groups[0].clone();
        metadata_group.group_id = "hn-meta".to_owned();
        metadata_group.role = "BODY".to_owned();
        metadata_group.member_region_ids =
            vec![metadata.region_id.clone(), metadata_tail.region_id.clone()];
        metadata_group.source_text = format!("{}\n{}", metadata.text, metadata_tail.text);
        metadata_group.bounds = metadata.bounds.union(&metadata_tail.bounds);
        metadata_group.render_slots = vec![metadata_group.bounds.clone()];
        let mut body_group = metadata_group.clone();
        body_group.group_id = "hn-body".to_owned();
        body_group.member_region_ids = vec![body.region_id.clone()];
        body_group.source_text = body.text.clone();
        body_group.bounds = body.bounds.clone();
        body_group.render_slots = vec![body.bounds.clone()];
        request.regions = vec![metadata, metadata_tail, body];
        request.groups = vec![metadata_group, body_group];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
        assert_eq!(plan.groups[0].role, "METADATA");
        assert_eq!(plan.groups[1].role, "BODY");
    }

    #[test]
    fn preserves_explicit_truncated_control_instead_of_translating_it_as_body() {
        let mut request = request();
        let mut region = request.regions[0].clone();
        region.region_id = "truncated-control".to_owned();
        region.group_id = "client-control".to_owned();
        region.text = "diversity in...".to_owned();
        let mut advisory = request.groups[0].clone();
        advisory.group_id = region.group_id.clone();
        advisory.role = "CONTROL".to_owned();
        advisory.translation_unit = "PRESERVED".to_owned();
        advisory.source_text = region.text.clone();
        advisory.member_region_ids = vec![region.region_id.clone()];
        advisory.bounds = region.bounds.clone();
        advisory.render_slots = vec![region.bounds.clone()];
        request.regions = vec![region];
        request.groups = vec![advisory];
        request.document_context.reading_order_region_ids =
            vec!["truncated-control".to_owned()];

        let prepared = crate::engine::prepare_translation(
            &serde_json::to_string(&request).unwrap(),
            "openlux",
            "gemini-test",
        )
        .unwrap();

        assert_eq!(1, prepared.execution_groups.len());
        assert_eq!("CONTROL", prepared.execution_groups[0].role);
        assert!(prepared.actionable_groups.is_empty());

        request.regions[0].text = "23 / en.wikipedia.org/wiki/Op".to_owned();
        request.groups[0].role = "IDENTIFIER".to_owned();
        request.groups[0].source_text = request.regions[0].text.clone();
        let identifier_prepared = crate::engine::prepare_translation(
            &serde_json::to_string(&request).unwrap(),
            "openlux",
            "gemini-test",
        )
        .unwrap();

        assert_eq!("IDENTIFIER", identifier_prepared.execution_groups[0].role);
        assert!(identifier_prepared.actionable_groups.is_empty());
    }

    #[test]
    fn recognizes_sentence_ending_before_a_parenthetical_suffix() {
        assert!(ends_sentence("happier?(2022)"));
        assert!(is_strong_text_boundary(
            "The standard dictionary fully backs this up:",
            "> logos"
        ));
        assert!(is_strong_text_boundary(
            "programming and the Internet.",
            "•JavaScript Guide(this guide)provides an"
        ));
    }

    #[test]
    fn restores_article_lines_that_flow_around_media() {
        let mut request = request();
        let mut first = request.regions[0].clone();
        first.text = "The Canadian government has awarded funding over the".to_owned();
        first.group_id.clear();
        first.block_id = Some("article-flow".to_owned());
        first.line_index = Some(0);
        first.confidence = 0.72;
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
        assert_eq!(plan.groups[0].grouping_confidence, 0.98);
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"OCR_BLOCK_CONTINUATION".to_owned())
        );
    }

    #[test]
    fn collapses_dense_low_confidence_paragraph_lines_into_one_rect() {
        let mut request = request();
        let mut regions = Vec::new();
        let texts = [
            "Welcome to The Rust Programming Language, an",
            "introductory book about Rust. The language helps",
            "you write faster and more reliable software.",
            "control.",
        ];
        for (index, text) in texts.into_iter().enumerate() {
            let mut region = request.regions[0].clone();
            region.region_id = format!("paragraph-{index}");
            region.group_id.clear();
            region.block_id = Some("paragraph-block".to_owned());
            region.line_index = Some(index as i32);
            region.reading_order = index as i32;
            region.confidence = 0.71;
            region.text = text.to_owned();
            region.bounds = Bounds {
                left: 70 + index as i32,
                top: 500 + index as i32 * 70,
                right: if index + 1 == texts.len() { 280 } else { 1_280 },
                bottom: 555 + index as i32 * 70,
            };
            regions.push(region);
        }
        request.groups.clear();
        request.regions = regions;

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].member_region_ids.len(), 4);
        assert_eq!(plan.groups[0].source_line_count, 4);
        assert_eq!(plan.groups[0].layout_shape, "RECT");
        assert_eq!(
            plan.groups[0].render_slots,
            vec![plan.groups[0].bounds.clone()]
        );
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"LOW_OCR_TEXT_CONFIDENCE".to_owned())
        );
    }

    #[test]
    fn overrides_client_flow_slots_for_dense_rectangular_paragraphs() {
        let mut request = request();
        let mut advisory = request.groups[0].clone();
        advisory.group_id = "client-paragraph".to_owned();
        advisory.role = "BODY".to_owned();
        advisory.layout_shape = "FLOW_SLOTS".to_owned();
        let texts = [
            "Finally, some appendixes contain useful information",
            "about the language in a more reference-like format.",
            "Appendix A covers Rust keywords and Appendix B",
            "covers operators and symbols.",
        ];
        let regions = texts
            .into_iter()
            .enumerate()
            .map(|(index, text)| {
                let mut region = request.regions[0].clone();
                region.region_id = format!("appendix-line-{index}");
                region.group_id = advisory.group_id.clone();
                region.block_id = Some("appendix-paragraph".to_owned());
                region.line_index = Some(index as i32);
                region.reading_order = index as i32;
                region.text = text.to_owned();
                region.bounds = Bounds {
                    left: 70 + index as i32,
                    top: 700 + index as i32 * 80,
                    right: if index + 1 == texts.len() { 620 } else { 1_300 },
                    bottom: 758 + index as i32 * 80,
                };
                region
            })
            .collect::<Vec<_>>();
        advisory.member_region_ids = regions
            .iter()
            .map(|region| region.region_id.clone())
            .collect();
        advisory.source_text = regions
            .iter()
            .map(|region| region.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        advisory.bounds = regions
            .iter()
            .skip(1)
            .fold(regions[0].bounds.clone(), |bounds, region| {
                bounds.union(&region.bounds)
            });
        advisory.render_slots = regions.iter().map(|region| region.bounds.clone()).collect();
        request.regions = regions;
        request.groups = vec![advisory];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].member_region_ids.len(), 4);
        assert_eq!(plan.groups[0].layout_shape, "RECT");
        assert_eq!(
            plan.groups[0].render_slots,
            vec![plan.groups[0].bounds.clone()]
        );
        assert!(
            plan.groups[0]
                .grouping_evidence
                .contains(&"DENSE_RECT_LAYOUT_COLLAPSED".to_owned())
        );
    }

    #[test]
    fn keeps_cross_advisory_natural_lines_as_flow_slots() {
        let mut request = request();
        let slot_bounds = [
            (57, 1914, 1371, 1971),
            (59, 2009, 921, 2066),
            (97, 2097, 1094, 2161),
            (59, 2198, 1303, 2256),
            (58, 2291, 1292, 2351),
            (58, 2387, 190, 2430),
        ];
        let texts = [
            "The public key is the one you generated earlier",
            "and should look something like",
            "did:key:string.Once this command",
            "completes you're done.You've now attached",
            "your own rotation key to your account.Take",
            "look!",
        ];
        let mut regions = Vec::new();
        let mut groups = Vec::new();
        for group_index in 0..3 {
            let line_range = match group_index {
                0 => 0..2,
                1 => 2..3,
                _ => 3..6,
            };
            let group_id = format!("client-rect-{group_index}");
            let mut advisory = request.groups[0].clone();
            advisory.group_id = group_id.clone();
            advisory.role = "BODY".to_owned();
            advisory.layout_shape = "RECT".to_owned();
            advisory.render_slots.clear();
            advisory.member_region_ids.clear();
            let mut group_bounds: Option<Bounds> = None;
            let mut group_text = Vec::new();
            for index in line_range {
                let (left, top, right, bottom) = slot_bounds[index];
                let mut region = request.regions[0].clone();
                region.region_id = format!("natural-line-{index}");
                region.group_id = group_id.clone();
                region.block_id = Some(format!("natural-block-{group_index}"));
                region.line_index = Some(index as i32);
                region.reading_order = index as i32;
                region.text = texts[index].to_owned();
                region.estimated_text_height_px = Some(52.0);
                region.typography_confidence = 0.9;
                region.bounds = Bounds {
                    left,
                    top,
                    right,
                    bottom,
                };
                group_bounds = Some(group_bounds.map_or_else(
                    || region.bounds.clone(),
                    |bounds| bounds.union(&region.bounds),
                ));
                advisory.member_region_ids.push(region.region_id.clone());
                group_text.push(region.text.clone());
                regions.push(region);
            }
            advisory.bounds = group_bounds.unwrap();
            advisory.render_slots = vec![advisory.bounds.clone()];
            advisory.source_text = group_text.join("\n");
            groups.push(advisory);
        }
        request.regions = regions;
        request.groups = groups;

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].member_region_ids.len(), 6);
        assert_eq!(plan.groups[0].layout_shape, "FLOW_SLOTS");
        assert_eq!(plan.groups[0].render_slots.len(), 6);
        assert!(
            !plan.groups[0]
                .grouping_evidence
                .contains(&"DENSE_RECT_LAYOUT_COLLAPSED".to_owned())
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
    fn keeps_title_case_section_heading_separate_from_body() {
        let mut request = request();
        let mut heading = request.regions[0].clone();
        heading.text = "Teams of Developers".to_owned();
        heading.group_id.clear();
        heading.block_id = Some("heading".to_owned());
        heading.bounds = Bounds {
            left: 70,
            top: 500,
            right: 700,
            bottom: 570,
        };
        let mut body = heading.clone();
        body.region_id = "title-case-body".to_owned();
        body.text = "Rust is proving to be a productive tool for teams.".to_owned();
        body.reading_order += 1;
        body.block_id = Some("body".to_owned());
        body.bounds = Bounds {
            left: 70,
            top: 630,
            right: 1_250,
            bottom: 700,
        };
        request.groups.clear();
        request.regions = vec![heading, body];

        let plan = build_regions_first_plan(&request);

        assert_eq!(plan.groups.len(), 2);
        assert_eq!(plan.groups[0].source_text, "Teams of Developers");
    }

    #[test]
    fn sentence_with_title_case_terms_is_not_a_section_heading() {
        assert!(!looks_like_title_label(
            "Welcome to The Rust Programming Language,an"
        ));
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
