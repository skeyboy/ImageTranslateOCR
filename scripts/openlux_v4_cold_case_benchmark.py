#!/usr/bin/env python3
"""Sample successful V4 database cases and cold-benchmark OpenLux models."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import random
import re
import sqlite3
import statistics
import subprocess
import tempfile
import time
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any


MODELS = (
    ("gemini-3.1-flash-lite", "thinkingLevel", ("minimal", "low", "medium", "high"), "minimal"),
    ("gpt-5.4-nano", "reasoning_effort", ("minimal", "high"), "minimal"),
    ("gpt-5.6-terra", "model_suffix", ("low", "medium", "high"), "low"),
    ("gpt-5.6-luna", "model_suffix", ("low", "medium", "high"), "low"),
    ("gpt-5.6-sol", "model_suffix", ("low", "medium", "high"), "low"),
    ("qwen3.5-35b-a3b", "reasoning_effort", ("minimal", "low", "medium", "high"), "low"),
)
REQUIRED_FIELDS = {"groupId", "translatedText", "detectedSourceLanguage", "targetLanguage"}
SYSTEM_SUFFIX = (
    "\nReturn only a strict JSON object containing a translations array. "
    "Each translation object must contain exactly groupId, translatedText, "
    "detectedSourceLanguage, and targetLanguage."
)


@dataclass
class Case:
    case_id: str
    bucket: str
    audit_id: str
    input_chars: int
    group_count: int
    source_model: str
    source_created_at: str
    system: str
    user_payload: dict[str, Any]
    groups: list[dict[str, Any]]
    references: list[dict[str, Any]]
    max_tokens: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default="demo-server/demo-server.sqlite3")
    parser.add_argument("--env-file", default="demo-server/.env")
    parser.add_argument("--seed", type=int, default=20260813)
    parser.add_argument("--request-delay", type=float, default=2.0)
    parser.add_argument("--rate-limit-cooldown", type=float, default=10.0)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--proxy", default="http://127.0.0.1:7897")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--select-only", action="store_true")
    return parser.parse_args()


def load_env(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


def text_parts(container: Any) -> str:
    if not isinstance(container, dict):
        return ""
    return "\n".join(
        part["text"] for part in container.get("parts", [])
        if isinstance(part, dict) and isinstance(part.get("text"), str)
    )


def unwrap_model_request(raw: str) -> dict[str, Any] | None:
    try:
        value = json.loads(raw)
    except (TypeError, json.JSONDecodeError):
        return None
    if isinstance(value.get("request"), dict):
        return value["request"]
    return value if isinstance(value, dict) else None


def canonical_prompt(request: dict[str, Any]) -> tuple[str, dict[str, Any], int] | None:
    if isinstance(request.get("messages"), list):
        system = "\n".join(
            message.get("content", "") for message in request["messages"]
            if message.get("role") == "system" and isinstance(message.get("content"), str)
        )
        user = next(
            (message.get("content") for message in request["messages"]
             if message.get("role") == "user" and isinstance(message.get("content"), str)),
            None,
        )
        if not user:
            return None
        try:
            payload = json.loads(user)
        except json.JSONDecodeError:
            return None
        return system, payload, int(request.get("max_tokens") or 1800)

    contents = request.get("contents") or []
    user_text = "\n".join(text_parts(content) for content in contents if isinstance(content, dict))
    if not user_text:
        return None
    try:
        payload = json.loads(user_text)
    except json.JSONDecodeError:
        return None
    config = request.get("generationConfig") or {}
    return text_parts(request.get("systemInstruction")), payload, int(config.get("maxOutputTokens") or 1800)


def reference_rows(response: dict[str, Any], groups: list[dict[str, Any]]) -> list[dict[str, Any]] | None:
    results = response.get("results")
    if not isinstance(results, list) or len(results) != len(groups):
        return None
    rows = []
    for index, item in enumerate(results):
        if not isinstance(item, dict) or not isinstance(item.get("translatedText"), str) or not item["translatedText"].strip():
            return None
        rows.append(
            {
                "groupId": groups[index]["groupId"],
                "translatedText": item["translatedText"],
                "detectedSourceLanguage": item.get("detectedSourceLanguage", ""),
                "targetLanguage": item.get("targetLanguage", ""),
            }
        )
    return rows


def natural_language_chars(groups: list[dict[str, Any]]) -> int:
    text = "".join(str(group.get("sourceText") or "") for group in groups)
    return len(re.findall(r"[A-Za-z\u4e00-\u9fff]", text))


def source_text(groups: list[dict[str, Any]]) -> str:
    return "\n".join(str(group.get("sourceText") or "") for group in groups)


def load_candidates(database: Path) -> list[dict[str, Any]]:
    uri = f"file:{database.resolve()}?mode=ro"
    connection = sqlite3.connect(uri, uri=True)
    connection.row_factory = sqlite3.Row
    rows = connection.execute(
        """
        SELECT a.id, a.input_chars, a.group_count, a.model, a.created_at,
               p.request_json, p.response_json, p.model_request_json
        FROM request_audits a
        JOIN request_payloads p ON p.audit_id = a.id
        WHERE a.status = 'SUCCEEDED'
          AND json_extract(p.request_json, '$.schemaVersion') = 4
          AND json_valid(p.request_json)
          AND json_valid(p.response_json)
          AND p.model_request_json IS NOT NULL
        """
    ).fetchall()
    connection.close()

    candidates = []
    for row in rows:
        original = json.loads(row["request_json"])
        response = json.loads(row["response_json"])
        model_request = unwrap_model_request(row["model_request_json"])
        prompt = canonical_prompt(model_request or {})
        if not prompt:
            continue
        system, payload, max_tokens = prompt
        groups = payload.get("translateGroups") if isinstance(payload, dict) else None
        if not isinstance(groups, list) or not groups:
            continue
        if any(not isinstance(group, dict) or not isinstance(group.get("groupId"), str) for group in groups):
            continue
        references = reference_rows(response, groups)
        source = source_text(groups)
        longest_group = max(len(str(group.get("sourceText") or "")) for group in groups)
        if not references or natural_language_chars(groups) < 60 or longest_group < 80:
            continue
        metrics = response.get("metrics") or {}
        if metrics.get("failedGroupCount") not in (None, 0):
            continue
        literal_valid = all(
            literal in references[index]["translatedText"]
            for index, group in enumerate(groups)
            for literal in (group.get("requiredLiteralIdentifiers") or [])
        )
        if not literal_valid:
            continue
        actual_chars = sum(len(str(group.get("sourceText") or "")) for group in groups)
        candidates.append(
            {
                "audit_id": row["id"],
                "input_chars": actual_chars,
                "group_count": len(groups),
                "source_model": row["model"],
                "source_created_at": row["created_at"],
                "system": system,
                "user_payload": payload,
                "groups": groups,
                "references": references,
                "max_tokens": max_tokens,
                "original_request_id": original.get("requestId"),
                "source_text": source,
            }
        )
    return candidates


def sample_cases(candidates: list[dict[str, Any]], seed: int) -> list[Case]:
    if len(candidates) < 5:
        raise RuntimeError(f"need at least 5 valid V4 candidates, found {len(candidates)}")
    rng = random.Random(seed)
    buckets = (
        ("XS", 80, 299),
        ("S", 300, 599),
        ("M", 600, 899),
        ("L", 900, 1149),
        ("XL", 1150, 10**9),
    )
    cases = []
    selected_sources: list[str] = []
    for index, (label, minimum, maximum) in enumerate(buckets):
        pool = [
            row for row in candidates
            if minimum <= row["input_chars"] <= maximum
            and all(
                SequenceMatcher(None, normalized(row["source_text"]), normalized(previous)).ratio() < 0.72
                for previous in selected_sources
            )
        ]
        if not pool:
            raise RuntimeError(f"no distinct candidate for {label} range {minimum}-{maximum}")
        selected = rng.choice(sorted(pool, key=lambda row: row["audit_id"]))
        selected_sources.append(selected["source_text"])
        cases.append(
            Case(
                case_id=f"case-{index + 1}-{label.lower()}",
                bucket=f"{label} {minimum}-{maximum if maximum < 10**9 else '+'}",
                **{key: selected[key] for key in (
                    "audit_id", "input_chars", "group_count", "source_model", "source_created_at",
                    "system", "user_payload", "groups", "references", "max_tokens",
                )},
            )
        )
    return cases


def build_payload(case: Case, model: str, control: str, level: str) -> tuple[dict[str, Any], str]:
    nonce = f"cold-{uuid.uuid4()}"
    body: dict[str, Any] = {
        "model": f"{model}-{level}" if control == "model_suffix" else model,
        "messages": [
            {"role": "system", "content": f"[benchmark_nonce:{nonce}]\n{case.system}{SYSTEM_SUFFIX}"},
            {"role": "user", "content": json.dumps(case.user_payload, ensure_ascii=False)},
        ],
        "max_tokens": case.max_tokens,
        "temperature": 0,
        "stream": True,
        "stream_options": {"include_usage": True},
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "semantic_translation",
                "strict": True,
                "schema": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["translations"],
                    "properties": {
                        "translations": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "additionalProperties": False,
                                "required": sorted(REQUIRED_FIELDS),
                                "properties": {
                                    field: {"type": "string", "minLength": 1}
                                    for field in REQUIRED_FIELDS
                                },
                            },
                        }
                    },
                },
            },
        },
    }
    if control == "thinkingLevel" and level != "omitted":
        body["google"] = {"thinking_config": {"thinking_level": level}}
    elif control == "reasoning_effort" and level != "omitted":
        body["reasoning_effort"] = level
    return body, nonce


def parse_sse(line: str) -> tuple[str, dict[str, Any] | None, str | None]:
    raw = line[5:].strip() if line.startswith("data:") else line.strip()
    if not raw or raw == "[DONE]":
        return "", None, None
    try:
        chunk = json.loads(raw)
    except json.JSONDecodeError:
        return "", None, None
    error = chunk.get("error")
    if isinstance(error, dict):
        api_error = error.get("message") or json.dumps(error, ensure_ascii=False)
    elif error:
        api_error = str(error)
    else:
        api_error = None
    choices = chunk.get("choices") or []
    content = ""
    if choices:
        value = (choices[0].get("delta") or {}).get("content")
        content = value if isinstance(value, str) else ""
    return content, chunk.get("usage") if isinstance(chunk.get("usage"), dict) else None, api_error


def normalized(text: str) -> str:
    return re.sub(r"\s+", "", text).lower()


def validate(text: str, case: Case) -> dict[str, Any]:
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        parsed = None
    translations = parsed.get("translations") if isinstance(parsed, dict) else None
    translations = translations if isinstance(translations, list) else []
    schema_valid = bool(translations) and all(
        isinstance(item, dict)
        and set(item) == REQUIRED_FIELDS
        and all(isinstance(item.get(field), str) and item[field].strip() for field in REQUIRED_FIELDS)
        for item in translations
    )
    expected_ids = [group["groupId"] for group in case.groups]
    actual_ids = [item.get("groupId") for item in translations if isinstance(item, dict)]
    coverage_valid = len(actual_ids) == len(expected_ids) and sorted(actual_ids) == sorted(expected_ids)
    by_id = {item.get("groupId"): item.get("translatedText", "") for item in translations if isinstance(item, dict)}
    literals_valid = all(
        literal in by_id.get(group["groupId"], "")
        for group in case.groups
        for literal in (group.get("requiredLiteralIdentifiers") or [])
    )
    reference_by_id = {item["groupId"]: item["translatedText"] for item in case.references}
    similarities = [
        SequenceMatcher(None, normalized(reference_by_id[group_id]), normalized(by_id.get(group_id, ""))).ratio()
        for group_id in expected_ids
    ]
    reference_similarity = round(100 * statistics.mean(similarities), 1) if similarities else 0.0
    json_quality = sum((20 if isinstance(parsed, dict) else 0, 30 if schema_valid else 0, 30 if coverage_valid else 0, 20 if literals_valid else 0))
    return {
        "json_valid": isinstance(parsed, dict),
        "schema_valid": schema_valid,
        "coverage_valid": coverage_valid,
        "literals_valid": literals_valid,
        "json_quality": json_quality,
        "reference_similarity": reference_similarity,
        "translation_count": len(translations),
    }


def run_cold(endpoint: str, proxy: str, api_key: str, case: Case, model: str, control: str, level: str, timeout: int) -> dict[str, Any]:
    body, nonce = build_payload(case, model, control, level)
    with tempfile.TemporaryDirectory(prefix="openlux-v4-cold-") as temporary:
        directory = Path(temporary)
        body_path = directory / "request.json"
        metrics_path = directory / "curl.json"
        body_path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        write_out = json.dumps({
            "http_code": "%{http_code}", "dns": "%{time_namelookup}",
            "connect": "%{time_connect}", "tls": "%{time_appconnect}",
            "headers": "%{time_starttransfer}", "total": "%{time_total}",
            "num_connects": "%{num_connects}",
        })
        command = [
            "curl", "--silent", "--show-error", "--no-buffer", "--http1.1",
            "--proxy", proxy, "--connect-timeout", "20", "--max-time", str(timeout),
            "--header", f"Authorization: Bearer {api_key}",
            "--header", "Content-Type: application/json",
            "--header", "Accept: text/event-stream",
            "--header", "Cache-Control: no-cache, no-store, max-age=0",
            "--header", "Pragma: no-cache",
            "--header", "Connection: close",
            "--data-binary", f"@{body_path}",
            "--write-out", f"%output{{{metrics_path}}}{write_out}", endpoint,
        ]
        started = time.perf_counter()
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        first_text_at = None
        parts: list[str] = []
        usage: dict[str, Any] = {}
        parse_errors: list[str] = []
        api_errors: list[str] = []
        assert process.stdout is not None
        for raw_line in process.stdout:
            try:
                content, chunk_usage, api_error = parse_sse(raw_line.strip())
                if content:
                    first_text_at = first_text_at or time.perf_counter()
                    parts.append(content)
                if chunk_usage:
                    usage = chunk_usage
                if api_error:
                    api_errors.append(api_error)
            except json.JSONDecodeError as error:
                parse_errors.append(str(error))
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
    checks = validate(response_text, case)
    details = usage.get("completion_tokens_details") or {}
    http_code = str(metrics.get("http_code", "000"))
    return {
        "case_id": case.case_id, "bucket": case.bucket, "input_chars": case.input_chars,
        "group_count": case.group_count, "model": model, "control": control, "level": level,
        "nonce": nonce, "success": return_code == 0 and http_code == "200" and checks["json_quality"] == 100,
        "http_code": http_code, "dns_ms": milliseconds("dns"), "connect_ms": milliseconds("connect"),
        "tls_ms": milliseconds("tls"), "headers_ms": milliseconds("headers"),
        "ttft_ms": round((first_text_at - started) * 1000, 1) if first_text_at else None,
        "total_ms": round((completed - started) * 1000, 1),
        "prompt_tokens": usage.get("prompt_tokens"), "completion_tokens": usage.get("completion_tokens"),
        "reasoning_tokens": details.get("reasoning_tokens"), "total_tokens": usage.get("total_tokens"),
        "num_connects": metrics.get("num_connects"), "response_sha256": hashlib.sha256(response_text.encode()).hexdigest(),
        "response_text": response_text, "error": "; ".join(filter(None, [stderr.strip(), *parse_errors, *api_errors])) or None,
        **checks,
    }


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[round((len(ordered) - 1) * fraction)], 1)


def summarize(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    summary = []
    for model, control, levels, preferred in MODELS:
        selected = [row for row in rows if row["model"] == model]
        preferred_rows = [row for row in selected if row["level"] == preferred]
        valid = [row for row in preferred_rows if row["success"]]
        summary.append({
            "model": model, "control": control, "preferred_level": preferred,
            "preferred_successes": len(valid), "preferred_runs": len(preferred_rows),
            "ttft_ms_median": round(statistics.median(row["ttft_ms"] for row in valid), 1) if valid else None,
            "total_ms_median": round(statistics.median(row["total_ms"] for row in valid), 1) if valid else None,
            "total_ms_p90": percentile([row["total_ms"] for row in valid], 0.9),
            "json_quality_median": round(statistics.median(row["json_quality"] for row in preferred_rows), 1) if preferred_rows else 0,
            "reference_similarity_median": round(statistics.median(row["reference_similarity"] for row in valid), 1) if valid else 0,
            "all_level_successes": sum(row["success"] for row in selected), "all_level_runs": len(selected),
            "levels": {
                level: {
                    "successes": sum(row["success"] for row in selected if row["level"] == level),
                    "runs": sum(1 for row in selected if row["level"] == level),
                    "total_ms_median": round(statistics.median(row["total_ms"] for row in selected if row["level"] == level), 1)
                    if any(row["level"] == level for row in selected) else None,
                    "reasoning_tokens_median": round(statistics.median(row["reasoning_tokens"] for row in selected if row["level"] == level and row["reasoning_tokens"] is not None), 1)
                    if any(row["level"] == level and row["reasoning_tokens"] is not None for row in selected) else None,
                } for level in levels
            },
        })
    return summary


def main() -> int:
    args = parse_args()
    if args.request_delay < 0 or args.rate_limit_cooldown < 0:
        raise SystemExit("delays must be non-negative")
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    candidates = load_candidates(Path(args.database))
    cases = sample_cases(candidates, args.seed)
    (output / "cases.json").write_text(json.dumps({
        "metadata": {"database": str(Path(args.database).resolve()), "seed": args.seed, "eligible_records": len(candidates)},
        "cases": [asdict(case) for case in cases],
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for case in cases:
        (output / f"{case.case_id}.json").write_text(json.dumps(asdict(case), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps([{"case": case.case_id, "bucket": case.bucket, "chars": case.input_chars, "groups": case.group_count, "audit": case.audit_id} for case in cases], ensure_ascii=False, indent=2))
    if args.select_only:
        return 0

    load_env(Path(args.env_file))
    api_key = os.getenv("OPENLUX_API_KEY", "")
    if not api_key:
        raise SystemExit("OPENLUX_API_KEY is required")
    endpoint = os.getenv("OPENLUX_BASE_URL", "https://api.openlux.ai/v1").rstrip("/") + "/chat/completions"
    rows = []
    for case in cases:
        for model, control, levels, _preferred in MODELS:
            for level in levels:
                print(f"[{case.case_id}] {model} {control}={level}", flush=True)
                row = run_cold(endpoint, args.proxy, api_key, case, model, control, level, args.timeout)
                rows.append(row)
                print(f"  http={row['http_code']} ok={row['success']} ttft={row['ttft_ms']} total={row['total_ms']} json={row['json_quality']} similarity={row['reference_similarity']}", flush=True)
                if row["http_code"] == "429":
                    time.sleep(args.rate_limit_cooldown)
                    break
                time.sleep(args.request_delay)

    report = {
        "metadata": {
            "created_at": datetime.now(timezone.utc).isoformat(), "database": str(Path(args.database).resolve()),
            "seed": args.seed, "eligible_records": len(candidates), "cold_start": True,
            "cache_policy": "new curl process/TCP connection; Connection: close; no-cache headers; unique nonce",
            "concurrency": 1, "request_delay_seconds": args.request_delay,
        },
        "cases": [asdict(case) for case in cases], "summary": summarize(rows), "runs": rows,
    }
    (output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output / "runs.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
