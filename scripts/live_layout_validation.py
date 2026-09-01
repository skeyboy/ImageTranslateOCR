#!/usr/bin/env python3
"""Validate semantic grouping and rendered patch layout from a device archive."""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, Iterable

try:
    import numpy as np
    from PIL import Image
except ImportError:  # Visual checks remain optional for structural-only environments.
    np = None
    Image = None


@dataclass(frozen=True)
class Bounds:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return max(0, self.right - self.left)

    @property
    def height(self) -> int:
        return max(0, self.bottom - self.top)

    @property
    def area(self) -> int:
        return self.width * self.height

    def intersection(self, other: "Bounds") -> int:
        width = max(0, min(self.right, other.right) - max(self.left, other.left))
        height = max(0, min(self.bottom, other.bottom) - max(self.top, other.top))
        return width * height

    def union(self, other: "Bounds") -> "Bounds":
        return Bounds(
            min(self.left, other.left),
            min(self.top, other.top),
            max(self.right, other.right),
            max(self.bottom, other.bottom),
        )

    def as_dict(self) -> dict[str, int]:
        return {
            "left": self.left,
            "top": self.top,
            "right": self.right,
            "bottom": self.bottom,
        }


def parse_bounds(value: dict[str, Any] | str) -> Bounds:
    if isinstance(value, dict):
        return Bounds(*(int(value[key]) for key in ("left", "top", "right", "bottom")))
    match = re.fullmatch(r"\[(\d+),(\d+)]\[(\d+),(\d+)]", value)
    if not match:
        raise ValueError(f"Unsupported bounds: {value}")
    return Bounds(*(int(part) for part in match.groups()))


def union_bounds(values: Iterable[Bounds]) -> Bounds | None:
    result = None
    for value in values:
        result = value if result is None else result.union(value)
    return result


def normalized(text: str) -> str:
    return "".join(character.lower() for character in text if character.isalnum())


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def add_finding(
    findings: list[dict[str, Any]],
    code: str,
    severity: str,
    message: str,
    **evidence: Any,
) -> None:
    findings.append(
        {"code": code, "severity": severity, "message": message, "evidence": evidence}
    )


def planned_groups(response: dict[str, Any]) -> list[dict[str, Any]]:
    return response.get("documentPlan", {}).get("groups", [])


def expected_blocks_from_config(
    expectation: dict[str, Any],
    groups: list[dict[str, Any]],
    findings: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    blocks = []
    for expected in expectation.get("expectedBlocks", []):
        anchors = [normalized(anchor) for anchor in expected.get("sourceContains", [])]
        matching = [
            group
            for group in groups
            if any(anchor and anchor in normalized(group.get("sourceText", "")) for anchor in anchors)
        ]
        complete = [
            group
            for group in matching
            if all(anchor in normalized(group.get("sourceText", "")) for anchor in anchors)
        ]
        expected_id = expected["id"]
        if len(complete) != 1:
            add_finding(
                findings,
                "EXPECTED_BLOCK_SPLIT" if matching else "EXPECTED_BLOCK_MISSING",
                "ERROR",
                f"Expected block {expected_id} resolved to {len(complete)} complete groups",
                expectedBlockId=expected_id,
                matchingGroupIds=[group.get("groupId") for group in matching],
                matchingSourceTexts=[group.get("sourceText") for group in matching],
            )
        group = complete[0] if len(complete) == 1 else None
        members = group.get("memberRegionIds", []) if group else []
        minimum_members = int(expected.get("minimumMemberCount", 1))
        if group and len(members) < minimum_members:
            add_finding(
                findings,
                "EXPECTED_MEMBER_COVERAGE_LOW",
                "ERROR",
                f"Expected block {expected_id} has too few OCR members",
                expectedBlockId=expected_id,
                memberCount=len(members),
                minimumMemberCount=minimum_members,
            )
        expected_shape = expected.get("layoutShape", "RECT")
        if group and group.get("layoutShape") != expected_shape:
            add_finding(
                findings,
                "EXPECTED_LAYOUT_SHAPE_MISMATCH",
                "ERROR",
                f"Expected block {expected_id} should render as {expected_shape}",
                expectedBlockId=expected_id,
                actualLayoutShape=group.get("layoutShape"),
                renderSlots=group.get("renderSlots", []),
            )
        bounds = union_bounds(parse_bounds(item["bounds"]) for item in matching)
        if group:
            bounds = parse_bounds(group["bounds"])
        if bounds:
            blocks.append(
                {
                    "id": expected_id,
                    "source": "EXPECTATION",
                    "bounds": bounds,
                    "expectedShape": expected_shape,
                    "groupIds": [item.get("groupId") for item in matching],
                }
            )
    return blocks


def accessibility_nodes(path: Path, minimum_characters: int) -> list[dict[str, Any]]:
    root = ET.parse(path).getroot()
    nodes: list[dict[str, Any]] = []

    def visit(node: ET.Element, in_webview: bool) -> None:
        class_name = node.attrib.get("class", "")
        inside = in_webview or class_name == "android.webkit.WebView"
        text = node.attrib.get("text", "").strip()
        if (
            inside
            and class_name == "android.widget.TextView"
            and len(normalized(text)) >= minimum_characters
            and not text.lower().startswith(("http://", "https://", "www."))
            and not text.endswith(("...", "…"))
            and node.attrib.get("bounds")
        ):
            bounds = parse_bounds(node.attrib["bounds"])
            if bounds.area > 0:
                nodes.append(
                    {
                        "id": f"dom-{len(nodes)}",
                        "text": text,
                        "normalized": normalized(text),
                        "bounds": bounds,
                    }
                )
        for child in node:
            visit(child, inside)

    visit(root, False)
    return nodes


def region_matches_node(region: dict[str, Any], node: dict[str, Any]) -> bool:
    region_bounds = parse_bounds(region["bounds"])
    if region_bounds.area == 0 or region_bounds.intersection(node["bounds"]) < region_bounds.area * 0.45:
        return False
    region_text = normalized(region.get("text", ""))
    node_text = node["normalized"]
    if not region_text or not node_text:
        return False
    if region_text in node_text or node_text in region_text:
        return True
    return SequenceMatcher(None, region_text, node_text).ratio() >= 0.56


def expected_blocks_from_accessibility(
    xml_path: Path,
    request: dict[str, Any],
    groups: list[dict[str, Any]],
    findings: list[dict[str, Any]],
    minimum_characters: int,
) -> list[dict[str, Any]]:
    nodes = accessibility_nodes(xml_path, minimum_characters)
    regions = request.get("regions", [])
    regions_by_id = {region.get("regionId"): region for region in regions}
    member_to_group = {
        region_id: group
        for group in groups
        for region_id in group.get("memberRegionIds", [])
    }
    mapped_nodes = []
    for node in nodes:
        matched = [region for region in regions if region_matches_node(region, node)]
        group_map = {
            group.get("groupId"): group
            for region in matched
            if (group := member_to_group.get(region.get("regionId"))) is not None
            and group.get("role") in {"BODY", "LIST_ITEM"}
        }
        if len(matched) < 2 or not group_map:
            continue
        matched_groups = list(group_map.values())
        if len(matched_groups) > 1:
            add_finding(
                findings,
                "ACCESSIBILITY_BLOCK_SPLIT",
                "ERROR",
                "One accessibility text node maps to multiple translated groups",
                domNodeId=node["id"],
                domText=node["text"],
                groupIds=list(group_map),
                memberRegionIds=[region.get("regionId") for region in matched],
            )
        elif matched_groups[0].get("layoutShape") != "RECT":
            add_finding(
                findings,
                "ACCESSIBILITY_RECT_BECAME_FLOW",
                "ERROR",
                "Rectangular accessibility paragraph did not produce RECT layout",
                domNodeId=node["id"],
                domText=node["text"],
                groupId=matched_groups[0].get("groupId"),
                layoutShape=matched_groups[0].get("layoutShape"),
            )
        matched_ids = {region.get("regionId") for region in matched}
        for group in matched_groups:
            foreign_members = []
            for region_id in group.get("memberRegionIds", []):
                if region_id in matched_ids:
                    continue
                region = regions_by_id.get(region_id)
                if region is None:
                    continue
                region_bounds = parse_bounds(region["bounds"])
                if region_bounds.intersection(node["bounds"]) < region_bounds.area * 0.20:
                    foreign_members.append(
                        {
                            "regionId": region_id,
                            "text": region.get("text"),
                            "bounds": region_bounds.as_dict(),
                        }
                    )
            if foreign_members:
                add_finding(
                    findings,
                    "ACCESSIBILITY_GROUP_HAS_FOREIGN_MEMBERS",
                    "ERROR",
                    "Translated group contains OCR members outside its accessibility text node",
                    domNodeId=node["id"],
                    domText=node["text"],
                    groupId=group.get("groupId"),
                    foreignMembers=foreign_members,
                )
        bounds = union_bounds(parse_bounds(region["bounds"]) for region in matched)
        if bounds:
            mapped_nodes.append(
                {
                    "id": node["id"],
                    "source": "ACCESSIBILITY",
                    "bounds": bounds,
                    "expectedShape": "RECT",
                    "groupIds": list(group_map),
                    "textPreview": node["text"][:120],
                }
            )

    group_to_nodes: dict[str, list[str]] = {}
    for node in mapped_nodes:
        for group_id in node["groupIds"]:
            group_to_nodes.setdefault(group_id, []).append(node["id"])
    for group_id, node_ids in group_to_nodes.items():
        if len(node_ids) > 1:
            add_finding(
                findings,
                "ACCESSIBILITY_BLOCKS_OVERMERGED",
                "ERROR",
                "One translated group maps to multiple accessibility text nodes",
                groupId=group_id,
                domNodeIds=node_ids,
            )
    return mapped_nodes


def expected_blocks_from_client_advisories(
    request: dict[str, Any],
    groups: list[dict[str, Any]],
    findings: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    blocks = []
    for advisory in request.get("groups", []):
        if (
            advisory.get("role") not in {"BODY", "LIST_ITEM"}
            or advisory.get("layoutShape") != "RECT"
            or len(advisory.get("memberRegionIds", [])) < 2
        ):
            continue
        advisory_id = advisory.get("groupId")
        matching = [
            group for group in groups if advisory_id in group.get("sourceGroupIds", [])
        ]
        if len(matching) > 1:
            add_finding(
                findings,
                "CLIENT_ADVISORY_SPLIT",
                "ERROR",
                "One client RECT advisory maps to multiple translated groups",
                clientGroupId=advisory_id,
                translatedGroupIds=[group.get("groupId") for group in matching],
                sourceText=advisory.get("sourceText"),
            )
        elif len(matching) == 1 and matching[0].get("layoutShape") != "RECT":
            add_finding(
                findings,
                "CLIENT_ADVISORY_RECT_BECAME_FLOW",
                "ERROR",
                "Client RECT advisory did not produce RECT layout",
                clientGroupId=advisory_id,
                translatedGroupId=matching[0].get("groupId"),
                layoutShape=matching[0].get("layoutShape"),
            )
        if matching:
            blocks.append(
                {
                    "id": f"client-{advisory_id}",
                    "source": "CLIENT_ADVISORY",
                    "bounds": parse_bounds(advisory["bounds"]),
                    "expectedShape": "RECT",
                    "groupIds": [group.get("groupId") for group in matching],
                    "textPreview": advisory.get("sourceText", "")[:120],
                }
            )
    return blocks


def contiguous_bands(active: Any) -> list[tuple[int, int]]:
    bands = []
    start = None
    for index, value in enumerate(active.tolist()):
        if value and start is None:
            start = index
        elif not value and start is not None:
            bands.append((start, index))
            start = None
    if start is not None:
        bands.append((start, len(active)))
    return bands


def visual_metrics(
    source_path: Path,
    translated_path: Path,
    block: dict[str, Any],
    viewport: dict[str, Any],
) -> dict[str, Any] | None:
    if Image is None or np is None:
        return None
    source = np.asarray(Image.open(source_path).convert("RGB"), dtype=np.int16)
    translated = np.asarray(Image.open(translated_path).convert("RGB"), dtype=np.int16)
    if source.shape != translated.shape:
        raise ValueError(f"Screenshot shape mismatch: {source.shape} != {translated.shape}")
    height, width = source.shape[:2]
    scale_x = width / max(1, int(viewport["width"]))
    scale_y = height / max(1, int(viewport["height"]))
    bounds: Bounds = block["bounds"]
    left = max(0, min(width, round(bounds.left * scale_x)))
    right = max(left + 1, min(width, round(bounds.right * scale_x)))
    top = max(0, min(height, round(bounds.top * scale_y)))
    bottom = max(top + 1, min(height, round(bounds.bottom * scale_y)))
    before = source[top:bottom, left:right]
    after = translated[top:bottom, left:right]
    difference = np.max(np.abs(after - before), axis=2)
    changed = difference >= 18
    row_coverage = changed.mean(axis=1)
    active_rows = row_coverage >= 0.20
    bands = contiguous_bands(active_rows)
    internal_gaps = [bands[index + 1][0] - bands[index][1] for index in range(len(bands) - 1)]
    column_coverage = changed.mean(axis=0)
    active_columns = column_coverage >= 0.20
    column_bands = contiguous_bands(active_columns)
    horizontal_gaps = [
        column_bands[index + 1][0] - column_bands[index][1]
        for index in range(len(column_bands) - 1)
    ]

    gray = after.mean(axis=2)
    background = float(np.median(gray))
    dark = gray <= background - 30
    dark_rows = dark.mean(axis=1) >= 0.006
    glyph_bands = [band for band in contiguous_bands(dark_rows) if band[1] - band[0] >= 3]
    glyph_heights = [end - start for start, end in glyph_bands]
    glyph_ratio = None
    if len(glyph_heights) >= 2:
        ordered = sorted(glyph_heights)
        glyph_ratio = ordered[0] / max(1, ordered[len(ordered) // 2])
    return {
        "bounds": bounds.as_dict(),
        "pixelBounds": {"left": left, "top": top, "right": right, "bottom": bottom},
        "changedPixelRatio": round(float(changed.mean()), 4),
        "visualBandCount": len(bands),
        "visualBands": [{"top": start, "bottom": end} for start, end in bands],
        "maximumInternalGapPx": max(internal_gaps, default=0),
        "horizontalBandCount": len(column_bands),
        "horizontalBands": [
            {"left": start, "right": end} for start, end in column_bands
        ],
        "maximumHorizontalGapPx": max(horizontal_gaps, default=0),
        "glyphRowBandCount": len(glyph_bands),
        "glyphHeightRatio": round(glyph_ratio, 4) if glyph_ratio is not None else None,
    }


def theoretical_merge_metrics(
    block: dict[str, Any],
    groups_by_id: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    groups = [
        groups_by_id[group_id]
        for group_id in block.get("groupIds", [])
        if group_id in groups_by_id
    ]
    slots = [slot for group in groups for slot in group.get("renderSlots", [])]
    shapes = sorted({group.get("layoutShape") for group in groups})
    return {
        "groupCount": len(groups),
        "groupIds": [group.get("groupId") for group in groups],
        "memberRegionCount": sum(len(group.get("memberRegionIds", [])) for group in groups),
        "layoutShapes": shapes,
        "renderSlotCount": len(slots),
        "merged": len(groups) == 1 and shapes == ["RECT"] and len(slots) == 1,
    }


def validate_visuals(
    blocks: list[dict[str, Any]],
    groups_by_id: dict[str, dict[str, Any]],
    source_path: Path,
    translated_path: Path,
    viewport: dict[str, Any],
    findings: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    results = []
    seen = set()
    for block in blocks:
        group_ids = tuple(sorted(block.get("groupIds", [])))
        key = ("groups", *group_ids) if group_ids else (
            "bounds",
            *block["bounds"].as_dict().values(),
        )
        if key in seen:
            continue
        seen.add(key)
        metrics = visual_metrics(source_path, translated_path, block, viewport)
        if metrics is None:
            return []
        theoretical = theoretical_merge_metrics(block, groups_by_id)
        actual_merged = (
            metrics["changedPixelRatio"] >= 0.30
            and metrics["visualBandCount"] == 1
            and metrics["horizontalBandCount"] == 1
        )
        merge_consistent = theoretical["merged"] == actual_merged
        metrics["id"] = block["id"]
        metrics["source"] = block["source"]
        metrics["theoretical"] = theoretical
        metrics["actualMerged"] = actual_merged
        metrics["mergeConsistent"] = merge_consistent
        results.append(metrics)
        if not merge_consistent:
            add_finding(
                findings,
                "THEORETICAL_ACTUAL_MERGE_MISMATCH",
                "ERROR",
                f"Theoretical and rendered merge results differ for block {block['id']}",
                **metrics,
            )
        if block["expectedShape"] == "RECT" and metrics["visualBandCount"] > 1:
            add_finding(
                findings,
                "RECT_VISUALLY_FRAGMENTED",
                "ERROR",
                f"Expected RECT block {block['id']} contains multiple changed-pixel bands",
                **metrics,
            )
        if metrics["changedPixelRatio"] < 0.30:
            add_finding(
                findings,
                "PATCH_VISUAL_COVERAGE_LOW",
                "ERROR",
                f"Rendered patch coverage is too low for block {block['id']}",
                **metrics,
            )
        if (
            metrics["glyphHeightRatio"] is not None
            and metrics["glyphHeightRatio"] < 0.72
            and metrics["visualBandCount"] > 1
        ):
            add_finding(
                findings,
                "INTRA_BLOCK_TYPOGRAPHY_DRIFT",
                "WARNING",
                f"Rendered glyph heights vary inside block {block['id']}",
                **metrics,
            )
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--ui-xml", type=Path)
    parser.add_argument("--expectation", type=Path)
    parser.add_argument("--source-image", type=Path)
    parser.add_argument("--translated-image", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--minimum-dom-characters", type=int, default=24)
    args = parser.parse_args()

    request = load_json(args.archive / "request.json")
    response = load_json(args.archive / "response.json")
    audit = load_json(args.archive / "render-audit.json")
    groups = planned_groups(response)
    groups_by_id = {group.get("groupId"): group for group in groups}
    findings: list[dict[str, Any]] = []
    blocks: list[dict[str, Any]] = []

    if args.expectation:
        blocks.extend(expected_blocks_from_config(load_json(args.expectation), groups, findings))
    accessibility_blocks = []
    if args.ui_xml:
        accessibility_blocks = expected_blocks_from_accessibility(
                args.ui_xml,
                request,
                groups,
                findings,
                args.minimum_dom_characters,
            )
        blocks.extend(accessibility_blocks)
    if not accessibility_blocks:
        blocks.extend(expected_blocks_from_client_advisories(request, groups, findings))

    diagnostics = audit.get("layoutDiagnostics", {})
    if diagnostics.get("translationFailedCount", 0) or diagnostics.get("renderFailedCount", 0):
        add_finding(
            findings,
            "PIPELINE_FAILURE",
            "BLOCKED",
            "Translation or rendering failures were reported",
            translationFailedCount=diagnostics.get("translationFailedCount", 0),
            renderFailedCount=diagnostics.get("renderFailedCount", 0),
        )
    if diagnostics.get("presentationOutcome") != "PRESENTED":
        add_finding(
            findings,
            "OVERLAY_NOT_PRESENTED",
            "ERROR",
            "Translated overlay was not presented",
            presentationOutcome=diagnostics.get("presentationOutcome"),
        )

    visual_results = []
    if args.source_image is None and (args.archive / "source-capture.jpg").is_file():
        args.source_image = args.archive / "source-capture.jpg"
    if args.translated_image is None and (args.archive / "rendered-capture.jpg").is_file():
        args.translated_image = args.archive / "rendered-capture.jpg"
    visual_requested = args.source_image is not None or args.translated_image is not None
    if args.source_image and args.translated_image:
        unrendered_group_ids = {
            result.get("groupId")
            for result in response.get("results", [])
            if result.get("status") != "TRANSLATED"
        }
        unrendered_group_ids.update(
            failure.get("groupId")
            for failure in diagnostics.get("renderFailures", [])
            if failure.get("groupId")
        )
        visual_blocks = [
            block
            for block in blocks
            if not unrendered_group_ids.intersection(block.get("groupIds", []))
        ]
        visual_results = validate_visuals(
            visual_blocks,
            groups_by_id,
            args.source_image,
            args.translated_image,
            request["viewport"],
            findings,
        )
    elif visual_requested:
        add_finding(
            findings,
            "VISUAL_INPUT_INCOMPLETE",
            "ERROR",
            "Both source and translated screenshots are required for visual validation",
        )

    error_count = sum(finding["severity"] == "ERROR" for finding in findings)
    blocked_count = sum(finding["severity"] == "BLOCKED" for finding in findings)
    status = "FAIL" if error_count else "INCONCLUSIVE" if blocked_count else "PASS"
    merge_consistency = {
        "theoreticalMergedBlockCount": sum(
            bool(result.get("theoretical", {}).get("merged")) for result in visual_results
        ),
        "actualMergedBlockCount": sum(
            bool(result.get("actualMerged")) for result in visual_results
        ),
        "consistentBlockCount": sum(
            bool(result.get("mergeConsistent")) for result in visual_results
        ),
        "inconsistentBlockCount": sum(
            result.get("mergeConsistent") is False for result in visual_results
        ),
    }
    report = {
        "schemaVersion": 2,
        "event": "live_layout_validation",
        "status": status,
        "requestId": request.get("requestId"),
        "plannedGroupCount": len(groups),
        "validatedBlockCount": len(blocks),
        "visualValidationAvailable": bool(visual_results),
        "mergeConsistency": merge_consistency,
        "layoutDiagnostics": {
            key: diagnostics.get(key)
            for key in (
                "translatedRegionCount",
                "renderedPatchCount",
                "translationFailedCount",
                "renderFailedCount",
                "translationVisible",
                "presentationOutcome",
                "endToEndMs",
            )
        },
        "visualBlocks": visual_results,
        "findings": findings,
        "errorCount": error_count,
        "blockedCount": blocked_count,
        "warningCount": sum(finding["severity"] == "WARNING" for finding in findings),
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 1 if error_count else 2 if blocked_count else 0


if __name__ == "__main__":
    sys.exit(main())
