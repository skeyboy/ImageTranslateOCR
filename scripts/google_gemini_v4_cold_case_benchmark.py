#!/usr/bin/env python3
"""Cold-benchmark V4 cases through Google's native Gemini endpoint and a proxy."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import statistics
import subprocess
import tempfile
import time
import uuid
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any


LEVELS = ("omitted", "minimal", "low", "medium", "high")
REQUIRED_FIELDS = {"groupId", "translatedText", "detectedSourceLanguage", "targetLanguage"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path("demo-server/.env"))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--request-delay", type=float, default=2.0)
    parser.add_argument("--rate-limit-cooldown", type=float, default=10.0)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--proxy")
    parser.add_argument("--model")
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


def response_schema() -> dict[str, Any]:
    return {
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
    }


def request_body(case: dict[str, Any], level: str) -> tuple[dict[str, Any], str]:
    nonce = f"google-native-cold-{uuid.uuid4()}"
    system = (
        f"[benchmark_nonce:{nonce}]\n{case['system']}\n"
        "Return only a strict JSON object containing a translations array. "
        "Each translation object must contain exactly groupId, translatedText, "
        "detectedSourceLanguage, and targetLanguage."
    )
    generation_config: dict[str, Any] = {
        "maxOutputTokens": case.get("max_tokens") or 1800,
        "temperature": 0,
        "responseMimeType": "application/json",
        "responseJsonSchema": response_schema(),
    }
    if level != "omitted":
        generation_config["thinkingConfig"] = {
            "includeThoughts": True,
            "thinkingLevel": level.upper(),
        }
    body = {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{
            "role": "user",
            "parts": [{"text": json.dumps(case["user_payload"], ensure_ascii=False)}],
        }],
        "generationConfig": generation_config,
    }
    return body, nonce


def answer_text(envelope: Any) -> str:
    try:
        parts = envelope["candidates"][0]["content"]["parts"]
    except (KeyError, IndexError, TypeError):
        return ""
    return "".join(
        part.get("text", "") for part in parts
        if isinstance(part, dict) and part.get("thought") is not True
    ).strip()


def validate(text: str, case: dict[str, Any]) -> dict[str, Any]:
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
    expected_ids = [group["groupId"] for group in case["groups"]]
    actual_ids = [item.get("groupId") for item in translations if isinstance(item, dict)]
    coverage_valid = len(actual_ids) == len(expected_ids) and sorted(actual_ids) == sorted(expected_ids)
    by_id = {item.get("groupId"): item.get("translatedText", "") for item in translations if isinstance(item, dict)}
    literals_valid = all(
        literal in by_id.get(group["groupId"], "")
        for group in case["groups"]
        for literal in (group.get("requiredLiteralIdentifiers") or [])
    )
    reference_by_id = {item["groupId"]: item["translatedText"] for item in case.get("references", [])}
    similarities = [
        SequenceMatcher(
            None,
            "".join(reference_by_id.get(group_id, "").split()).lower(),
            "".join(by_id.get(group_id, "").split()).lower(),
        ).ratio()
        for group_id in expected_ids
        if group_id in reference_by_id
    ]
    reference_similarity = round(100 * statistics.mean(similarities), 1) if similarities else None
    quality = sum((
        20 if isinstance(parsed, dict) else 0,
        30 if schema_valid else 0,
        30 if coverage_valid else 0,
        20 if literals_valid else 0,
    ))
    return {
        "json_valid": isinstance(parsed, dict),
        "schema_valid": schema_valid,
        "coverage_valid": coverage_valid,
        "literals_valid": literals_valid,
        "json_quality": quality,
        "reference_similarity": reference_similarity,
        "translation_count": len(translations),
    }


def run_once(endpoint: str, proxy: str, api_key: str, model: str, case: dict[str, Any], level: str, timeout: int) -> dict[str, Any]:
    body, nonce = request_body(case, level)
    with tempfile.TemporaryDirectory(prefix="google-gemini-v4-cold-") as temporary:
        directory = Path(temporary)
        body_path = directory / "request.json"
        response_path = directory / "response.json"
        metrics_path = directory / "metrics.json"
        body_path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        write_out = json.dumps({
            "http_code": "%{http_code}",
            "dns": "%{time_namelookup}",
            "connect": "%{time_connect}",
            "tls": "%{time_appconnect}",
            "headers": "%{time_starttransfer}",
            "total": "%{time_total}",
            "num_connects": "%{num_connects}",
        })
        command = [
            "curl", "--silent", "--show-error", "--http1.1",
            "--proxy", proxy, "--connect-timeout", "20", "--max-time", str(timeout),
            "--header", f"x-goog-api-key: {api_key}",
            "--header", "Content-Type: application/json",
            "--header", "Cache-Control: no-cache, no-store, max-age=0",
            "--header", "Pragma: no-cache",
            "--header", "Connection: close",
            "--data-binary", f"@{body_path}",
            "--output", str(response_path),
            "--write-out", f"%output{{{metrics_path}}}{write_out}",
            endpoint,
        ]
        started = time.perf_counter()
        process = subprocess.run(command, capture_output=True, text=True)
        elapsed_ms = round((time.perf_counter() - started) * 1000, 1)
        raw = response_path.read_text(encoding="utf-8") if response_path.exists() else ""
        metrics = json.loads(metrics_path.read_text()) if metrics_path.exists() and metrics_path.stat().st_size else {}

    try:
        envelope = json.loads(raw)
    except json.JSONDecodeError:
        envelope = None
    text = answer_text(envelope)
    checks = validate(text, case)
    usage = envelope.get("usageMetadata", {}) if isinstance(envelope, dict) else {}
    api_error = envelope.get("error", {}).get("message") if isinstance(envelope, dict) else None

    def milliseconds(key: str) -> float | None:
        try:
            return round(float(metrics[key]) * 1000, 1)
        except (KeyError, TypeError, ValueError):
            return None

    http_code = str(metrics.get("http_code", "000"))
    return {
        "case_id": case["case_id"], "input_chars": case["input_chars"],
        "group_count": case["group_count"], "model": model, "level": level,
        "thinking_parameter": None if level == "omitted" else "generationConfig.thinkingConfig.thinkingLevel",
        "nonce": nonce, "http_code": http_code,
        "success": process.returncode == 0 and http_code == "200" and checks["json_quality"] == 100,
        "dns_ms": milliseconds("dns"), "connect_ms": milliseconds("connect"),
        "tls_ms": milliseconds("tls"), "headers_ms": milliseconds("headers"),
        "total_ms": milliseconds("total") or elapsed_ms,
        "num_connects": metrics.get("num_connects"),
        "prompt_tokens": usage.get("promptTokenCount"),
        "thoughts_tokens": usage.get("thoughtsTokenCount"),
        "candidates_tokens": usage.get("candidatesTokenCount"),
        "total_tokens": usage.get("totalTokenCount"),
        "response_sha256": hashlib.sha256(raw.encode()).hexdigest(),
        "response_text": text,
        "error": "; ".join(filter(None, [process.stderr.strip(), api_error])) or None,
        **checks,
    }


def main() -> int:
    args = parse_args()
    if args.request_delay < 0 or args.rate_limit_cooldown < 0:
        raise SystemExit("delays must be non-negative")
    load_env(args.env_file)
    api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    if not api_key:
        raise SystemExit("GEMINI_API_KEY or GOOGLE_API_KEY is required")
    base_url = os.getenv("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
    model = args.model or os.getenv("GEMINI_MODEL", "gemini-3.5-flash-lite")
    proxy = args.proxy if args.proxy is not None else os.getenv("GEMINI_PROXY_URL", "http://127.0.0.1:7897")
    if not proxy:
        raise SystemExit("a proxy is required for this benchmark")
    endpoint = f"{base_url}/models/{model}:generateContent"
    case_document = json.loads(args.cases.read_text(encoding="utf-8"))
    cases = case_document.get("cases") or []
    if len(cases) != 5:
        raise SystemExit(f"expected five cases, found {len(cases)}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for case in cases:
        for level in LEVELS:
            print(f"[{case['case_id']}] {model} thinkingLevel={level}", flush=True)
            row = run_once(endpoint, proxy, api_key, model, case, level, args.timeout)
            rows.append(row)
            print(
                f"  http={row['http_code']} ok={row['success']} total={row['total_ms']} "
                f"json={row['json_quality']} thoughts={row['thoughts_tokens']}",
                flush=True,
            )
            if row["http_code"] == "429":
                time.sleep(args.rate_limit_cooldown)
            time.sleep(args.request_delay)

    summary = []
    for level in LEVELS:
        selected = [row for row in rows if row["level"] == level]
        successful = [row for row in selected if row["success"]]
        summary.append({
            "level": level,
            "successes": len(successful),
            "runs": len(selected),
            "total_ms_median": round(statistics.median(row["total_ms"] for row in successful), 1) if successful else None,
            "json_quality_median": round(statistics.median(row["json_quality"] for row in selected), 1),
            "thoughts_tokens_median": round(statistics.median(row["thoughts_tokens"] for row in successful if row["thoughts_tokens"] is not None), 1)
            if any(row["thoughts_tokens"] is not None for row in successful) else None,
        })
    report = {
        "metadata": {
            "created_at": datetime.now(timezone.utc).isoformat(),
            "transport": "Google native Gemini generateContent",
            "base_url": base_url,
            "endpoint_template": f"{base_url}/models/{{model}}:generateContent",
            "model": model,
            "proxy": proxy,
            "cold_start": "new curl process/TCP connection; Connection: close; no-cache headers; unique nonce",
            "concurrency": 1,
            "request_delay_seconds": args.request_delay,
        },
        "summary": summary,
        "runs": rows,
    }
    (args.output_dir / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (args.output_dir / "runs.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
