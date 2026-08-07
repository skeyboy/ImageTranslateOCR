#!/usr/bin/env python3
"""Render OCR regions and translated render slots from validation JSON as SVG."""

import argparse
import html
import json
import math
from pathlib import Path


ROLE_COLORS = {
    "TITLE": "#b45309",
    "BODY": "#2563eb",
    "METADATA": "#6b7280",
    "CONTROL": "#7c3aed",
    "IDENTIFIER": "#0f766e",
    "TIMESTAMP": "#64748b",
}


def visible_length(text):
    return sum(0.56 if ord(char) < 128 else 1.0 for char in text)


def text_width(text, font_size):
    return visible_length(text) * font_size


def boundary_end(text, requested):
    requested = max(1, min(requested, len(text)))
    if requested == len(text):
        return requested
    if not (text[requested - 1].isalnum() and text[requested].isalnum()):
        return requested
    minimum = max(1, requested - 24)
    for index in range(requested, minimum - 1, -1):
        if text[index - 1].isspace() or text[index - 1] in ",.;:，。；：!?！？":
            return index
    return requested


def wrap_prefix(text, width, maximum_lines, font_size):
    cursor = 0
    lines = []
    while cursor < len(text) and len(lines) < maximum_lines:
        remaining = text[cursor:].lstrip()
        cursor += len(text[cursor:]) - len(remaining)
        if not remaining:
            break
        low, high, best = 1, len(remaining), 1
        while low <= high:
            midpoint = (low + high) // 2
            if text_width(remaining[:midpoint], font_size) <= width:
                best = midpoint
                low = midpoint + 1
            else:
                high = midpoint - 1
        end = boundary_end(remaining, best)
        lines.append(remaining[:end].rstrip())
        cursor += end
    return lines, cursor


def flow_text(text, slots, source_line_height, minimum_scale):
    preferred = max(5.0, source_line_height * 0.78)
    minimum = max(4.0, preferred * max(0.68, minimum_scale))
    for spacing in (1.0, 0.92, 0.86):
        low, high = minimum, preferred
        best = None
        for _ in range(9):
            font_size = (low + high) / 2
            cursor = 0
            segments = []
            line_height = font_size * 1.18 * spacing
            for slot in slots:
                if cursor >= len(text):
                    break
                width = max(1, slot["right"] - slot["left"] - 4)
                maximum_lines = max(1, int((slot["bottom"] - slot["top"]) / line_height))
                lines, consumed = wrap_prefix(text[cursor:], width, maximum_lines, font_size)
                if not lines or consumed <= 0:
                    break
                segments.append((slot, lines))
                cursor += consumed
                while cursor < len(text) and text[cursor].isspace():
                    cursor += 1
            candidate = (cursor >= len(text), font_size, spacing, segments)
            if candidate[0]:
                best = candidate
                low = font_size
            else:
                high = font_size
        if best:
            return "FULL" if best[1] >= preferred * 0.98 and spacing == 1.0 else "COMPACT", best
    return "OVERFLOW", (False, minimum, 0.86, [])


def rect(bounds, offset_x, offset_y, stroke, fill="none", opacity=1.0, width=1.0):
    return (
        f'<rect x="{offset_x + bounds["left"]}" y="{offset_y + bounds["top"]}" '
        f'width="{bounds["right"] - bounds["left"]}" '
        f'height="{bounds["bottom"] - bounds["top"]}" rx="1" '
        f'fill="{fill}" fill-opacity="{opacity}" stroke="{stroke}" '
        f'stroke-width="{width}" vector-effect="non-scaling-stroke"/>'
    )


def svg_text(x, y, text, font_size, color="#111827", weight=400, anchor="start"):
    return (
        f'<text x="{x}" y="{y}" font-family="Arial Unicode MS, PingFang SC, sans-serif" '
        f'font-size="{font_size:.2f}" font-weight="{weight}" fill="{color}" '
        f'text-anchor="{anchor}">{html.escape(text)}</text>'
    )


def median(values):
    ordered = sorted(values)
    return ordered[len(ordered) // 2] if ordered else 12


def source_panel(report, offset_x, offset_y):
    output = []
    for group in report["semanticGrouping"]["groups"]:
        color = ROLE_COLORS.get(group["role"], "#475569")
        output.append(rect(group["bounds"], offset_x, offset_y, color, color, 0.035, 1.4))
        for region in group["regions"]:
            bounds = region["bounds"]
            output.append(rect(bounds, offset_x, offset_y, color, color, 0.075, 0.8))
            height = max(5, bounds["bottom"] - bounds["top"])
            width = max(5, bounds["right"] - bounds["left"])
            text = region["rawText"] or region["text"]
            font_size = min(height * 0.74, width / max(1.0, visible_length(text)) * 1.7)
            font_size = max(4.0, font_size)
            output.append(svg_text(
                offset_x + bounds["left"] + 1,
                offset_y + bounds["top"] + min(height - 1, font_size * 1.05),
                text,
                font_size,
                "#111827",
            ))
    return output


def translation_panel(report, offset_x, offset_y):
    output = []
    for group in report["semanticGrouping"]["groups"]:
        color = ROLE_COLORS.get(group["role"], "#475569")
        translation = group["translation"]
        hint = translation.get("layoutHint") or {}
        slots = hint.get("renderSlots") or group.get("renderSlots") or [group["bounds"]]
        text = translation["translatedText"] if translation["succeeded"] else group["sourceText"]
        heights = [
            region["bounds"]["bottom"] - region["bounds"]["top"]
            for region in group["regions"]
        ]
        minimum_scale = hint.get("minimumTextScale", 0.68)
        outcome, layout = flow_text(text.replace("\n", " "), slots, median(heights), minimum_scale)
        output.append(rect(group["bounds"], offset_x, offset_y, color, color, 0.025, 1.2))
        for slot in slots:
            output.append(rect(slot, offset_x, offset_y, color, color, 0.08, 0.9))
        _, font_size, spacing, segments = layout
        if outcome == "OVERFLOW":
            segments = []
            first = slots[0]
            lines, _ = wrap_prefix(text, first["right"] - first["left"] - 4, 2, max(4, font_size))
            if lines:
                lines[-1] = lines[-1].rstrip("。.") + "… 更多"
                segments = [(first, lines)]
        line_height = font_size * 1.18 * spacing
        for slot, lines in segments:
            start_y = offset_y + slot["top"] + font_size
            for index, line in enumerate(lines):
                output.append(svg_text(
                    offset_x + slot["left"] + 2,
                    start_y + index * line_height,
                    line,
                    font_size,
                    "#111827",
                ))
        badge_x = offset_x + group["bounds"]["right"] - 2
        badge_y = offset_y + group["bounds"]["top"] + 7
        output.append(svg_text(badge_x, badge_y, outcome, 5.5, color, 700, "end"))
    return output


def render(report_path, output_path, title):
    report = json.loads(report_path.read_text())
    width = report["source"]["width"]
    height = report["source"]["height"]
    header = max(34, int(height * 0.035))
    margin = max(18, int(width * 0.05))
    gap = max(24, int(width * 0.08))
    total_width = margin * 2 + width * 2 + gap
    total_height = header + height + margin
    left_x = margin
    right_x = margin + width + gap
    page_y = header
    body = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="100%" '
        f'viewBox="0 0 {total_width} {total_height}" role="img" aria-label="{html.escape(title)}">',
        '<rect width="100%" height="100%" fill="#f3f4f6"/>',
        svg_text(left_x, header * 0.62, f"OCR 行与语义组 · {width}×{height}", header * 0.28, "#111827", 700),
        svg_text(right_x, header * 0.62, "译文与 renderSlots 回流", header * 0.28, "#111827", 700),
        f'<rect x="{left_x}" y="{page_y}" width="{width}" height="{height}" fill="#ffffff" stroke="#111827" stroke-width="2"/>',
        f'<rect x="{right_x}" y="{page_y}" width="{width}" height="{height}" fill="#ffffff" stroke="#111827" stroke-width="2"/>',
    ]
    body.extend(source_panel(report, left_x, page_y))
    body.extend(translation_panel(report, right_x, page_y))
    body.append('</svg>')
    output_path.write_text("\n".join(body))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--title", required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    render(args.report, args.output, args.title)


if __name__ == "__main__":
    main()
