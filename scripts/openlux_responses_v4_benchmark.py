#!/usr/bin/env python3
"""Run a V4 translation case through the OpenLux OpenAI Responses endpoint."""

from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any

import openlux_v4_cold_case_benchmark as chat_bench


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
                    "required": sorted(chat_bench.REQUIRED_FIELDS),
                    "properties": {
                        field: {"type": "string", "minLength": 1}
                        for field in chat_bench.REQUIRED_FIELDS
                    },
                },
            }
        },
    }


def build_payload(case: chat_bench.Case, model: str, level: str) -> tuple[dict[str, Any], str]:
    nonce = f"openlux-responses-cold-{uuid.uuid4()}"
    body: dict[str, Any] = {
        "model": model,
        "input": [
            {
                "role": "system",
                "content": [{"type": "input_text", "text": f"[benchmark_nonce:{nonce}]\n{case.system}{chat_bench.SYSTEM_SUFFIX}"}],
            },
            {
                "role": "user",
                "content": [{"type": "input_text", "text": json.dumps(case.user_payload, ensure_ascii=False)}],
            },
        ],
        "max_output_tokens": case.max_tokens,
        "text": {
            "format": {
                "type": "json_schema",
                "name": "semantic_translation",
                "strict": True,
                "schema": response_schema(),
            }
        },
    }
    if level != "omitted":
        body["reasoning"] = {"effort": level}
    return body, nonce


def output_text(envelope: Any) -> str:
    if not isinstance(envelope, dict):
        return ""
    direct = envelope.get("output_text")
    if isinstance(direct, str):
        return direct.strip()
    parts = []
    for item in envelope.get("output") or []:
        if not isinstance(item, dict):
            continue
        for content in item.get("content") or []:
            if isinstance(content, dict) and content.get("type") in {"output_text", "text"}:
                text = content.get("text")
                if isinstance(text, str):
                    parts.append(text)
    return "".join(parts).strip()


def run_once(endpoint: str, proxy: str, api_key: str, case: chat_bench.Case, model: str, level: str, timeout: int) -> dict[str, Any]:
    body, nonce = build_payload(case, model, level)
    with tempfile.TemporaryDirectory(prefix="openlux-responses-v4-") as temporary:
        directory = Path(temporary)
        body_path = directory / "request.json"
        response_path = directory / "response.json"
        metrics_path = directory / "metrics.json"
        body_path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        write_out = json.dumps({
            "http_code": "%{http_code}", "dns": "%{time_namelookup}",
            "connect": "%{time_connect}", "tls": "%{time_appconnect}",
            "headers": "%{time_starttransfer}", "total": "%{time_total}",
            "num_connects": "%{num_connects}",
        })
        command = [
            "curl", "--silent", "--show-error", "--http1.1",
            "--proxy", proxy, "--connect-timeout", "20", "--max-time", str(timeout),
            "--header", f"Authorization: Bearer {api_key}",
            "--header", "Content-Type: application/json",
            "--header", "Cache-Control: no-cache, no-store, max-age=0",
            "--header", "Pragma: no-cache", "--header", "Connection: close",
            "--data-binary", f"@{body_path}", "--output", str(response_path),
            "--write-out", f"%output{{{metrics_path}}}{write_out}", endpoint,
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
    text = output_text(envelope)
    checks = chat_bench.validate(text, case)
    usage = envelope.get("usage") or {} if isinstance(envelope, dict) else {}
    details = usage.get("output_tokens_details") or {}
    error_value = envelope.get("error") if isinstance(envelope, dict) else None
    if isinstance(error_value, dict):
        api_error = error_value.get("message") or json.dumps(error_value, ensure_ascii=False)
    else:
        api_error = str(error_value) if error_value else None

    def milliseconds(key: str) -> float | None:
        try:
            return round(float(metrics[key]) * 1000, 1)
        except (KeyError, TypeError, ValueError):
            return None

    http_code = str(metrics.get("http_code", "000"))
    return {
        "case_id": case.case_id, "input_chars": case.input_chars,
        "group_count": case.group_count, "model": model, "level": level,
        "control": "responses.reasoning.effort", "nonce": nonce,
        "http_code": http_code,
        "success": process.returncode == 0 and http_code == "200" and checks["json_quality"] == 100,
        "dns_ms": milliseconds("dns"), "connect_ms": milliseconds("connect"),
        "tls_ms": milliseconds("tls"), "headers_ms": milliseconds("headers"),
        "ttft_ms": None, "total_ms": milliseconds("total") or elapsed_ms,
        "prompt_tokens": usage.get("input_tokens"), "completion_tokens": usage.get("output_tokens"),
        "reasoning_tokens": details.get("reasoning_tokens"), "total_tokens": usage.get("total_tokens"),
        "num_connects": metrics.get("num_connects"),
        "response_sha256": hashlib.sha256(raw.encode()).hexdigest(),
        "response_text": text,
        "error": "; ".join(filter(None, [process.stderr.strip(), api_error])) or None,
        **checks,
    }
