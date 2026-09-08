#!/usr/bin/env python3
"""A/B benchmark baseline and compressed OCR translation prompts from saved V4 audits."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import random
import re
import statistics
import subprocess
import tempfile
import time
import uuid
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import openlux_v4_cold_case_benchmark as shared


VARIANTS = ("baseline", "no_outline", "compact", "compact_ordered", "compact_keyed", "compact_bound", "compact_safe")
COMPACT_SYSTEM = """You are a screen OCR translation engine. Input is JSON with mode m and ordered groups g. Each group has integer index i, source text t, source language s, target language d, required literal strings p, role r, and optional bounds b in 0..1000 coordinates. Translate every group independently and completely; use order, role, and bounds only for disambiguation. Do not merge, split, summarize, explain, or copy text between groups. Preserve every p string verbatim. For AUTO_BIDIRECTIONAL, translate Chinese natural language to English and other natural language to Chinese. Return only strict JSON matching the schema, with every i exactly once and every translated t non-empty."""
COMPACT_ORDERED_SYSTEM = """You are a screen OCR translation engine. Input is JSON with mode m and ordered groups g. Each group has source text t, source language s, target language d, required literal strings p, role r, and optional bounds b in 0..1000 coordinates. Translate every group independently and completely; use order, role, and bounds only for disambiguation. Do not merge, split, summarize, explain, or copy text between groups. Preserve every p string verbatim. For AUTO_BIDIRECTIONAL, translate Chinese natural language to English and other natural language to Chinese. Return only strict JSON {\"t\":[\"translation 0\",\"translation 1\"]}, with exactly one non-empty translated string per input group in the same order."""
COMPACT_KEYED_SYSTEM = """You are a screen OCR translation engine. Input is JSON with mode m and ordered groups g. Each group has integer key i, source text t, source language s, target language d, required literal strings p, role r, and optional bounds b in 0..1000 coordinates. Translate every group independently and completely; use order, role, and bounds only for disambiguation. Do not merge, split, summarize, explain, or copy text between groups. Preserve every p string verbatim. For AUTO_BIDIRECTIONAL, translate Chinese natural language to English and other natural language to Chinese. Return only strict JSON {\"t\":{\"0\":\"translation for group 0\"}}, with every input i key exactly once and every value non-empty."""
COMPACT_BOUND_SYSTEM = """You are a screen OCR translation engine. Input is JSON with mode m and a groups object g. Every g key is an immutable binding: translate only that key's source text t and write the complete translation under the identical key in output object t. Never shift, reorder, merge, split, summarize, explain, or copy text between keys. Use object order, role r, and bounds b only for disambiguation. Preserve every required literal string p verbatim. For AUTO_BIDIRECTIONAL, translate Chinese natural language to English and other natural language to Chinese. Return only strict JSON {\"t\":{\"g00\":\"translation of g.g00.t\"}} matching the schema, with every input key exactly once and every value non-empty."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default="demo-server.sqlite3")
    parser.add_argument("--env-file", default="demo-server/.env")
    parser.add_argument("--model", default="gemini-3.1-flash-lite")
    parser.add_argument("--thinking-level", default="medium")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--proxy", default="http://127.0.0.1:7897")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--request-delay", type=float, default=1.0)
    parser.add_argument("--seed", type=int, default=20260825)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--select-only", action="store_true")
    parser.add_argument("--case-limit", type=int, default=6)
    parser.add_argument("--variants", default=",".join(VARIANTS))
    parser.add_argument("--buckets", default="")
    return parser.parse_args()


def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def response_schema(variant: str, group_count: int) -> dict[str, Any]:
    if variant == "compact_safe":
        root_name = "translations"
        properties = {
            "groupId": {"type": "string", "minLength": 1},
            "translatedText": {"type": "string", "minLength": 1},
        }
        root_schema = {
            "type": "array",
            "items": {
                "type": "object",
                "properties": properties,
                "required": ["groupId", "translatedText"],
                "additionalProperties": False,
            },
        }
    elif variant in {"compact_keyed", "compact_bound"}:
        root_name = "t"
        keys = [f"g{index:02d}" for index in range(group_count)] if variant == "compact_bound" else [
            str(index) for index in range(group_count)
        ]
        root_schema = {
            "type": "object",
            "properties": {key: {"type": "string", "minLength": 1} for key in keys},
            "required": keys,
            "additionalProperties": False,
        }
    elif variant == "compact_ordered":
        root_name = "t"
        root_schema: dict[str, Any] = {
            "type": "array",
            "minItems": group_count,
            "maxItems": group_count,
            "items": {"type": "string", "minLength": 1},
        }
    elif variant == "compact":
        properties = {
            "i": {"type": "integer"},
            "t": {"type": "string", "minLength": 1},
        }
        required = ["i", "t"]
        root_name = "t"
        root_schema = {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": required,
                "properties": properties,
            },
        }
    else:
        properties = {
            field: {"type": "string", "minLength": 1}
            for field in sorted(shared.REQUIRED_FIELDS)
        }
        required = sorted(shared.REQUIRED_FIELDS)
        root_name = "translations"
        root_schema = {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": required,
                "properties": properties,
            },
        }
    return {
        "type": "json_schema",
        "json_schema": {
            "name": "translation_ab",
            "strict": True,
            "schema": {
                "type": "object",
                "additionalProperties": False,
                "required": [root_name],
                "properties": {root_name: root_schema},
            },
        },
    }


def source_text(case: shared.Case) -> str:
    return "\n".join(str(group.get("sourceText") or "") for group in case.groups)


def literal_count(case: shared.Case) -> int:
    return sum(len(group.get("requiredLiteralIdentifiers") or []) for group in case.groups)


def fragmentation_score(case: shared.Case) -> int:
    source = source_text(case)
    return source.count("\n") + len(re.findall(r"\w[,.;:]\w|\bl[A-Z]|\bI\s*\|", source)) * 2


def mixed_language_score(case: shared.Case) -> int:
    source = source_text(case)
    latin = len(re.findall(r"[A-Za-z]", source))
    chinese = len(re.findall(r"[\u4e00-\u9fff]", source))
    return min(latin, chinese)


def distinct_pick(
    candidates: list[shared.Case],
    selected: list[shared.Case],
    key: Any,
    reverse: bool = True,
) -> shared.Case | None:
    for candidate in sorted(candidates, key=key, reverse=reverse):
        if candidate.audit_id in {case.audit_id for case in selected}:
            continue
        if all(
            shared.SequenceMatcher(
                None,
                shared.normalized(source_text(candidate)),
                shared.normalized(source_text(existing)),
            ).ratio() < 0.72
            for existing in selected
        ):
            return candidate
    return None


def select_cases(candidates: list[dict[str, Any]], limit: int) -> list[tuple[str, shared.Case]]:
    cases = [
        shared.Case(
            case_id="candidate",
            bucket="candidate",
            **{key: row[key] for key in (
                "audit_id", "input_chars", "group_count", "source_model", "source_created_at",
                "system", "user_payload", "groups", "references", "max_tokens",
            )},
        )
        for row in candidates
    ]
    selected: list[shared.Case] = []
    labeled: list[tuple[str, shared.Case]] = []
    median_chars = statistics.median(case.input_chars for case in cases)
    selectors = (
        ("short", lambda case: case.input_chars, False),
        ("long", lambda case: case.input_chars, True),
        ("dense", lambda case: case.group_count, True),
        ("literal", literal_count, True),
        ("fragmented", fragmentation_score, True),
        ("balanced", lambda case: -abs(case.input_chars - median_chars), True),
    )
    for label, key, reverse in selectors[:limit]:
        picked = distinct_pick(cases, selected, key, reverse)
        if picked is None:
            continue
        picked.case_id = f"case-{len(selected) + 1}-{label}"
        picked.bucket = label
        selected.append(picked)
        labeled.append((label, picked))
    if len(labeled) < limit:
        raise RuntimeError(f"could only select {len(labeled)} distinct cases, need {limit}")
    return labeled


def bounds(group: dict[str, Any]) -> list[int] | None:
    value = group.get("normalizedBounds")
    if not isinstance(value, list) or len(value) != 4:
        return None
    try:
        return [round(float(item) * 1000) for item in value]
    except (TypeError, ValueError):
        return None


def compact_user(case: shared.Case, nonce: str) -> dict[str, Any]:
    groups = []
    for index, group in enumerate(case.groups):
        item: dict[str, Any] = {
            "i": index,
            "t": group.get("sourceText", ""),
            "s": group.get("sourceLanguage", "auto"),
            "d": group.get("targetLanguage", "auto"),
            "p": group.get("requiredLiteralIdentifiers") or [],
            "r": group.get("role", "BODY"),
        }
        group_bounds = bounds(group)
        if group_bounds is not None:
            item["b"] = group_bounds
        groups.append(item)
    return {"m": case.user_payload.get("translationMode", "AUTO_BIDIRECTIONAL"), "g": groups, "n": nonce}


def compact_bound_user(case: shared.Case, nonce: str) -> dict[str, Any]:
    array_payload = compact_user(case, nonce)
    groups = {}
    for item in array_payload["g"]:
        key = f"g{item.pop('i'):02d}"
        groups[key] = item
    return {"m": array_payload["m"], "g": groups, "n": nonce}


def compact_safe_user(case: shared.Case, nonce: str) -> dict[str, Any]:
    groups = [{
        "groupId": group["groupId"],
        "sourceText": group.get("sourceText", ""),
        "sourceLanguage": group.get("sourceLanguage", "auto"),
        "targetLanguage": group.get("targetLanguage", "auto"),
        "requiredLiteralIdentifiers": group.get("requiredLiteralIdentifiers") or [],
        "role": group.get("role", "BODY"),
    } for group in case.groups]
    return {
        "translationMode": case.user_payload.get("translationMode", "AUTO_BIDIRECTIONAL"),
        "translateGroups": groups,
        "benchmarkNonce": nonce,
    }


def build_payload(case: shared.Case, variant: str, model: str, level: str) -> tuple[dict[str, Any], str]:
    nonce = f"compression-ab-{uuid.uuid4()}"
    if variant == "compact_safe":
        system = (
            case.system
            + "\nThe compact input may omit documentOutline and geometry. Return only groupId and "
              "translatedText for every translateGroups item; copy each full groupId exactly once."
        )
        user = compact_safe_user(case, nonce)
        schema = response_schema(variant, len(case.groups))
    elif variant in {"compact", "compact_ordered", "compact_keyed", "compact_bound"}:
        system = {
            "compact": COMPACT_SYSTEM,
            "compact_ordered": COMPACT_ORDERED_SYSTEM,
            "compact_keyed": COMPACT_KEYED_SYSTEM,
            "compact_bound": COMPACT_BOUND_SYSTEM,
        }[variant]
        user = compact_bound_user(case, nonce) if variant == "compact_bound" else compact_user(case, nonce)
        schema = response_schema(variant, len(case.groups))
    else:
        system = case.system
        user = json.loads(json.dumps(case.user_payload, ensure_ascii=False))
        if variant == "no_outline":
            user.pop("documentOutline", None)
        user["benchmarkNonce"] = nonce
        schema = response_schema(variant, len(case.groups))
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": compact_json(user)},
        ],
        "max_tokens": case.max_tokens,
        "stream": True,
        "stream_options": {"include_usage": True},
        "response_format": schema,
        "google": {"thinking_config": {"thinking_level": level.upper()}},
    }
    return body, nonce


def normalize_response(text: str, case: shared.Case, variant: str) -> tuple[str, int, bool]:
    if variant == "compact_safe":
        parsed = json.loads(text)
        items = parsed.get("translations") if isinstance(parsed, dict) else None
        expected = {group["groupId"]: group for group in case.groups}
        wire_valid = (
            isinstance(items, list)
            and len(items) == len(expected)
            and {item.get("groupId") for item in items if isinstance(item, dict)} == set(expected)
            and all(
                isinstance(item, dict)
                and set(item) == {"groupId", "translatedText"}
                and isinstance(item.get("translatedText"), str)
                and item["translatedText"].strip()
                for item in items
            )
        )
        if not isinstance(items, list):
            return text, len(text.encode("utf-8")), False
        translated = [{
            "groupId": item.get("groupId", ""),
            "translatedText": item.get("translatedText", ""),
            "detectedSourceLanguage": expected.get(item.get("groupId"), {}).get("sourceLanguage") or "auto",
            "targetLanguage": expected.get(item.get("groupId"), {}).get("targetLanguage") or "auto",
        } for item in items if isinstance(item, dict)]
        return compact_json({"translations": translated}), len(text.encode("utf-8")), wire_valid
    if variant not in {"compact", "compact_ordered", "compact_keyed", "compact_bound"}:
        return text, len(text.encode("utf-8")), True
    parsed = json.loads(text)
    items = parsed.get("t") if isinstance(parsed, dict) else None
    if variant in {"compact_keyed", "compact_bound"}:
        expected_keys = {
            f"g{index:02d}" if variant == "compact_bound" else str(index)
            for index in range(len(case.groups))
        }
        wire_valid = (
            isinstance(items, dict)
            and set(items) == expected_keys
            and all(isinstance(value, str) and value.strip() for value in items.values())
        )
        if not isinstance(items, dict):
            return text, len(text.encode("utf-8")), False
        translated = [{
            "groupId": group["groupId"],
            "translatedText": items.get(f"g{index:02d}" if variant == "compact_bound" else str(index), ""),
            "detectedSourceLanguage": group.get("sourceLanguage") or "auto",
            "targetLanguage": group.get("targetLanguage") or "auto",
        } for index, group in enumerate(case.groups)]
        return compact_json({"translations": translated}), len(text.encode("utf-8")), wire_valid
    if not isinstance(items, list):
        return text, len(text.encode("utf-8")), False
    if variant == "compact_ordered":
        wire_valid = len(items) == len(case.groups) and all(
            isinstance(item, str) and item.strip() for item in items
        )
        translated = [{
            "groupId": group["groupId"],
            "translatedText": items[index] if index < len(items) else "",
            "detectedSourceLanguage": group.get("sourceLanguage") or "auto",
            "targetLanguage": group.get("targetLanguage") or "auto",
        } for index, group in enumerate(case.groups)]
        return compact_json({"translations": translated}), len(text.encode("utf-8")), wire_valid
    indices = [item.get("i") for item in items if isinstance(item, dict)]
    wire_valid = sorted(indices) == list(range(len(case.groups))) if all(
        isinstance(index, int) for index in indices
    ) and len(indices) == len(items) else False
    translated = []
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get("i"), int):
            continue
        index = item["i"]
        if not 0 <= index < len(case.groups):
            continue
        group = case.groups[index]
        translated.append({
            "groupId": group["groupId"],
            "translatedText": item.get("t", ""),
            "detectedSourceLanguage": group.get("sourceLanguage") or "auto",
            "targetLanguage": group.get("targetLanguage") or "auto",
        })
    return compact_json({"translations": translated}), len(text.encode("utf-8")), wire_valid


def run_once(
    endpoint: str,
    proxy: str,
    api_key: str,
    case: shared.Case,
    variant: str,
    model: str,
    level: str,
    timeout: int,
) -> dict[str, Any]:
    body, nonce = build_payload(case, variant, model, level)
    request_raw = compact_json(body)
    with tempfile.TemporaryDirectory(prefix="prompt-compression-ab-") as temporary:
        directory = Path(temporary)
        body_path = directory / "request.json"
        metrics_path = directory / "curl.json"
        body_path.write_text(request_raw, encoding="utf-8")
        write_out = json.dumps({
            "http_code": "%{http_code}", "dns": "%{time_namelookup}",
            "connect": "%{time_connect}", "tls": "%{time_appconnect}",
            "pretransfer": "%{time_pretransfer}", "headers": "%{time_starttransfer}",
            "total": "%{time_total}", "upload": "%{size_upload}", "download": "%{size_download}",
        })
        command = [
            "curl", "--silent", "--show-error", "--no-buffer", "--http1.1",
            "--proxy", proxy, "--connect-timeout", "20", "--max-time", str(timeout),
            "--header", f"Authorization: Bearer {api_key}",
            "--header", "Content-Type: application/json",
            "--header", "Accept: text/event-stream",
            "--header", "Connection: close",
            "--data-binary", f"@{body_path}",
            "--write-out", f"%output{{{metrics_path}}}{write_out}", endpoint,
        ]
        started = time.perf_counter()
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        first_text_at = None
        parts: list[str] = []
        usage: dict[str, Any] = {}
        api_errors: list[str] = []
        assert process.stdout is not None
        for raw_line in process.stdout:
            content, chunk_usage, api_error = shared.parse_sse(raw_line.strip())
            if content:
                first_text_at = first_text_at or time.perf_counter()
                parts.append(content)
            if chunk_usage:
                usage = chunk_usage
            if api_error:
                api_errors.append(api_error)
        stderr = process.stderr.read() if process.stderr else ""
        return_code = process.wait()
        completed = time.perf_counter()
        metrics = json.loads(metrics_path.read_text()) if metrics_path.exists() and metrics_path.stat().st_size else {}

    def milliseconds(key: str) -> float | None:
        try:
            return round(float(metrics[key]) * 1000, 1)
        except (KeyError, TypeError, ValueError):
            return None

    response_text = "".join(parts)
    try:
        canonical, response_bytes, wire_shape_valid = normalize_response(response_text, case, variant)
        checks = shared.validate(canonical, case)
        checks["schema_valid"] = bool(checks["schema_valid"] and wire_shape_valid)
        checks["wire_shape_valid"] = wire_shape_valid
        checks["json_quality"] = sum((
            20 if checks["json_valid"] else 0,
            30 if checks["schema_valid"] else 0,
            30 if checks["coverage_valid"] else 0,
            20 if checks["literals_valid"] else 0,
        ))
    except (json.JSONDecodeError, TypeError, ValueError) as error:
        response_bytes = len(response_text.encode("utf-8"))
        canonical = ""
        checks = {
            "json_valid": False, "schema_valid": False, "coverage_valid": False,
            "literals_valid": False, "json_quality": 0, "reference_similarity": 0.0,
            "translation_count": 0, "wire_shape_valid": False,
        }
        api_errors.append(str(error))
    details = usage.get("completion_tokens_details") or {}
    pretransfer_ms = milliseconds("pretransfer")
    ttft_ms = round((first_text_at - started) * 1000, 1) if first_text_at else None
    http_code = str(metrics.get("http_code", "000"))
    return {
        "case_id": case.case_id,
        "bucket": case.bucket,
        "variant": variant,
        "input_chars": case.input_chars,
        "group_count": case.group_count,
        "literal_count": literal_count(case),
        "fragmentation_score": fragmentation_score(case),
        "mixed_language_score": mixed_language_score(case),
        "model": model,
        "thinking_level": level,
        "nonce": nonce,
        "request_bytes": len(request_raw.encode("utf-8")),
        "system_chars": len(body["messages"][0]["content"]),
        "user_chars": len(body["messages"][1]["content"]),
        "schema_bytes": len(compact_json(body["response_format"]).encode("utf-8")),
        "response_bytes": response_bytes,
        "http_code": http_code,
        "success": return_code == 0 and http_code == "200" and checks["json_quality"] == 100,
        "dns_ms": milliseconds("dns"),
        "connect_ms": milliseconds("connect"),
        "tls_ms": milliseconds("tls"),
        "pretransfer_ms": pretransfer_ms,
        "headers_ms": milliseconds("headers"),
        "ttft_ms": ttft_ms,
        "model_wait_ms": round(ttft_ms - pretransfer_ms, 1) if ttft_ms is not None and pretransfer_ms is not None else None,
        "total_ms": round((completed - started) * 1000, 1),
        "prompt_tokens": usage.get("prompt_tokens"),
        "completion_tokens": usage.get("completion_tokens"),
        "reasoning_tokens": details.get("reasoning_tokens"),
        "total_tokens": usage.get("total_tokens"),
        "response_sha256": hashlib.sha256(response_text.encode()).hexdigest(),
        "response_text": response_text,
        "canonical_response": canonical,
        "error": "; ".join(filter(None, [stderr.strip(), *api_errors])) or None,
        **checks,
    }


def median(rows: list[dict[str, Any]], key: str) -> float | None:
    values = [float(row[key]) for row in rows if row.get(key) is not None]
    return round(statistics.median(values), 1) if values else None


def summarize(rows: list[dict[str, Any]], variants: list[str]) -> list[dict[str, Any]]:
    summary = []
    baseline = [
        row for row in rows
        if row["variant"] == "baseline" and row["http_code"] == "200"
        and row["json_valid"] and row["schema_valid"] and row["coverage_valid"]
    ]
    baseline_all = [row for row in rows if row["variant"] == "baseline"]
    baseline_request = median(baseline_all, "request_bytes")
    baseline_total = median(baseline, "total_ms")
    for variant in variants:
        selected = [row for row in rows if row["variant"] == variant]
        structurally_valid = [
            row for row in selected
            if row["http_code"] == "200" and row["json_valid"]
            and row["schema_valid"] and row["coverage_valid"]
        ]
        strict = [row for row in structurally_valid if row["literals_valid"]]
        request_bytes = median(selected, "request_bytes")
        total_ms = median(structurally_valid, "total_ms")
        summary.append({
            "variant": variant,
            "runs": len(selected),
            "http_successes": sum(row["http_code"] == "200" for row in selected),
            "json_valid": sum(bool(row["json_valid"]) for row in selected),
            "schema_valid": sum(bool(row["schema_valid"]) for row in selected),
            "wire_shape_valid": sum(bool(row.get("wire_shape_valid")) for row in selected),
            "coverage_valid": sum(bool(row["coverage_valid"]) for row in selected),
            "literal_valid": sum(bool(row["literals_valid"]) for row in selected),
            "structural_success_rate": round(len(structurally_valid) / len(selected), 3) if selected else 0,
            "strict_success_rate": round(len(strict) / len(selected), 3) if selected else 0,
            "request_bytes_median": request_bytes,
            "request_bytes_reduction_pct": round(100 * (baseline_request - request_bytes) / baseline_request, 1)
            if baseline_request and request_bytes is not None else None,
            "prompt_tokens_median": median(structurally_valid, "prompt_tokens"),
            "completion_tokens_median": median(structurally_valid, "completion_tokens"),
            "reasoning_tokens_median": median(structurally_valid, "reasoning_tokens"),
            "ttft_ms_median": median(structurally_valid, "ttft_ms"),
            "model_wait_ms_median": median(structurally_valid, "model_wait_ms"),
            "total_ms_median": total_ms,
            "total_ms_reduction_pct": round(100 * (baseline_total - total_ms) / baseline_total, 1)
            if baseline_total and total_ms is not None else None,
            "reference_similarity_median": median(structurally_valid, "reference_similarity"),
            "json_quality_median": median(selected, "json_quality"),
        })
    return summary


def main() -> int:
    args = parse_args()
    if not 1 <= args.rounds <= 10:
        raise SystemExit("--rounds must be between 1 and 10")
    if not 1 <= args.case_limit <= 6:
        raise SystemExit("--case-limit must be between 1 and 6")
    variants = [item.strip() for item in args.variants.split(",") if item.strip()]
    invalid_variants = set(variants) - set(VARIANTS)
    if not variants or invalid_variants:
        raise SystemExit(f"unsupported variants: {', '.join(sorted(invalid_variants))}")
    if "baseline" not in variants:
        raise SystemExit("--variants must include baseline for relative comparisons")
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    candidates = shared.load_candidates(Path(args.database))
    selected = select_cases(candidates, args.case_limit)
    requested_buckets = {item.strip() for item in args.buckets.split(",") if item.strip()}
    if requested_buckets:
        selected = [item for item in selected if item[0] in requested_buckets]
        missing_buckets = requested_buckets - {label for label, _case in selected}
        if missing_buckets:
            raise SystemExit(f"selected cases do not contain buckets: {', '.join(sorted(missing_buckets))}")
    cases = [case for _label, case in selected]
    coverage = [{
        "case_id": case.case_id,
        "bucket": label,
        "audit_id": case.audit_id,
        "input_chars": case.input_chars,
        "group_count": case.group_count,
        "literal_count": literal_count(case),
        "fragmentation_score": fragmentation_score(case),
        "mixed_language_score": mixed_language_score(case),
        "source_preview": source_text(case).replace("\n", " ")[:180],
    } for label, case in selected]
    (output / "cases.json").write_text(compact_json({
        "metadata": {"database": str(Path(args.database).resolve()), "eligible_records": len(candidates)},
        "coverage": coverage,
        "cases": [asdict(case) for case in cases],
    }) + "\n", encoding="utf-8")
    print(json.dumps(coverage, ensure_ascii=False, indent=2), flush=True)
    if args.select_only:
        return 0

    shared.load_env(Path(args.env_file))
    api_key = os.getenv("OPENLUX_API_KEY", "")
    if not api_key:
        raise SystemExit("OPENLUX_API_KEY is required")
    model = args.model
    endpoint = os.getenv("OPENLUX_BASE_URL", "https://api.openlux.ai/v1").rstrip("/") + "/chat/completions"
    rng = random.Random(args.seed)
    rows: list[dict[str, Any]] = []
    for round_index in range(1, args.rounds + 1):
        case_order = cases[:]
        rng.shuffle(case_order)
        for case in case_order:
            variant_order = variants[:]
            rng.shuffle(variant_order)
            for variant in variant_order:
                print(f"round={round_index} case={case.case_id} variant={variant}", flush=True)
                row = run_once(
                    endpoint, args.proxy, api_key, case, variant, model,
                    args.thinking_level, args.timeout,
                )
                row["round"] = round_index
                rows.append(row)
                print(
                    f"  ok={row['success']} http={row['http_code']} bytes={row['request_bytes']} "
                    f"tokens={row['prompt_tokens']}/{row['completion_tokens']}/{row['reasoning_tokens']} "
                    f"ttft={row['ttft_ms']} total={row['total_ms']} quality={row['json_quality']} "
                    f"similarity={row['reference_similarity']}",
                    flush=True,
                )
                time.sleep(max(0, args.request_delay))

    report = {
        "metadata": {
            "created_at": datetime.now(timezone.utc).isoformat(),
            "database": str(Path(args.database).resolve()),
            "eligible_records": len(candidates),
            "model": model,
            "thinking_level": args.thinking_level,
            "rounds": args.rounds,
            "case_count": len(cases),
            "seed": args.seed,
            "connection_policy": "cold curl HTTP/1.1 connection per run; randomized variant order",
            "cache_policy": "unique nonce in dynamic user payload; fixed system prefix",
            "language_coverage": "saved eligible records are English-to-Chinese; no genuine mixed-language case was available",
        },
        "coverage": coverage,
        "summary": summarize(rows, variants),
        "runs": rows,
    }
    (output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output / "runs.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2), flush=True)
    return 0 if all(item["structural_success_rate"] == 1 for item in report["summary"]) else 2


if __name__ == "__main__":
    raise SystemExit(main())
