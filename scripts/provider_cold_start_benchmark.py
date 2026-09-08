#!/usr/bin/env python3
"""Cold-connection benchmark for the production GPT, Claude, and Gemini paths."""

from __future__ import annotations

import argparse
import copy
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
from pathlib import Path
from typing import Any


PROVIDERS = (
    ("gpt", "gpt-4.1", "omitted", None),
    ("claude", "claude-sonnet-4-6", "reasoning_effort", "low"),
    ("gemini", "gemini-3.5-flash-lite", "thinking_level", "medium"),
)


def load_env(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip().strip("'\"")
        os.environ.setdefault(key.strip(), value)


def median(rows: list[dict[str, Any]], key: str) -> float | int | None:
    values = [row[key] for row in rows if row.get(key) is not None]
    return round(statistics.median(values), 1) if values else None


def percentile(rows: list[dict[str, Any]], key: str, fraction: float) -> float | None:
    values = sorted(float(row[key]) for row in rows if row.get(key) is not None)
    if not values:
        return None
    index = round((len(values) - 1) * fraction)
    return round(values[index], 1)


def prepare_payload(source: dict[str, Any], model: str, control: str, level: str | None) -> tuple[dict[str, Any], str]:
    body = copy.deepcopy(source)
    body["model"] = model
    body["stream"] = True
    body["stream_options"] = {"include_usage": True}
    body.pop("reasoning_effort", None)
    body.pop("google", None)
    if control == "reasoning_effort":
        body["reasoning_effort"] = level
    elif control == "thinking_level":
        body["google"] = {"thinking_config": {"thinking_level": level}}

    nonce = f"cold-{uuid.uuid4()}"
    messages = body.get("messages", [])
    if messages and isinstance(messages[0].get("content"), str):
        messages[0]["content"] = f"[benchmark_request_nonce: {nonce}]\n" + messages[0]["content"]
    return body, nonce


def parse_sse_line(line: str) -> tuple[str, dict[str, Any] | None]:
    if not line.startswith("data:"):
        return "", None
    data = line[5:].strip()
    if not data or data == "[DONE]":
        return "", None
    chunk = json.loads(data)
    choices = chunk.get("choices") or []
    text = ""
    if choices:
        delta = choices[0].get("delta") or {}
        content = delta.get("content")
        if isinstance(content, str):
            text = content
    return text, chunk.get("usage") if isinstance(chunk.get("usage"), dict) else None


def validate_response(response_text: str, source: dict[str, Any]) -> dict[str, Any]:
    try:
        parsed = json.loads(response_text)
    except json.JSONDecodeError:
        return {
            "response_json_valid": False,
            "group_coverage_valid": False,
            "literal_preservation_valid": False,
            "translation_count": None,
        }
    translations = parsed.get("translations") if isinstance(parsed, dict) else None
    translations = translations if isinstance(translations, list) else []
    expected_groups = json.loads(source["messages"][1]["content"])["translateGroups"]
    expected_ids = [group["groupId"] for group in expected_groups]
    actual_ids = [item.get("groupId") for item in translations if isinstance(item, dict)]
    translated_by_id = {
        item.get("groupId"): item.get("translatedText", "")
        for item in translations
        if isinstance(item, dict)
    }
    literals_valid = all(
        literal in translated_by_id.get(group["groupId"], "")
        for group in expected_groups
        for literal in group.get("requiredLiteralIdentifiers", [])
    )
    return {
        "response_json_valid": isinstance(parsed, dict) and isinstance(parsed.get("translations"), list),
        "group_coverage_valid": len(actual_ids) == len(expected_ids) and sorted(actual_ids) == sorted(expected_ids),
        "literal_preservation_valid": literals_valid,
        "translation_count": len(translations),
    }


def run_once(
    endpoint: str,
    proxy: str,
    api_key: str,
    source: dict[str, Any],
    provider: str,
    model: str,
    control: str,
    level: str | None,
    round_number: int,
    timeout: int,
) -> dict[str, Any]:
    body, nonce = prepare_payload(source, model, control, level)
    with tempfile.TemporaryDirectory(prefix="provider-benchmark-") as temp_dir:
        body_path = Path(temp_dir) / "request.json"
        metrics_path = Path(temp_dir) / "curl-metrics.json"
        body_path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        write_out = json.dumps(
            {
                "http_code": "%{http_code}",
                "time_namelookup": "%{time_namelookup}",
                "time_connect": "%{time_connect}",
                "time_appconnect": "%{time_appconnect}",
                "time_pretransfer": "%{time_pretransfer}",
                "time_starttransfer": "%{time_starttransfer}",
                "time_total": "%{time_total}",
                "remote_ip": "%{remote_ip}",
                "num_connects": "%{num_connects}",
            }
        )
        command = [
            "curl", "--silent", "--show-error", "--no-buffer", "--http1.1",
            "--proxy", proxy, "--connect-timeout", "20", "--max-time", str(timeout),
            "--header", f"Authorization: Bearer {api_key}",
            "--header", "Content-Type: application/json",
            "--header", "Accept: text/event-stream",
            "--header", "Cache-Control: no-cache, no-store",
            "--header", "Pragma: no-cache",
            "--header", "Connection: close",
            "--data-binary", f"@{body_path}",
            "--write-out", f"%output{{{metrics_path}}}{write_out}", endpoint,
        ]
        started = time.perf_counter()
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        first_text_at: float | None = None
        response_parts: list[str] = []
        usage: dict[str, Any] = {}
        parse_errors: list[str] = []
        assert process.stdout is not None
        for raw_line in process.stdout:
            try:
                text, chunk_usage = parse_sse_line(raw_line.strip())
                if text:
                    first_text_at = first_text_at or time.perf_counter()
                    response_parts.append(text)
                if chunk_usage:
                    usage = chunk_usage
            except json.JSONDecodeError as error:
                parse_errors.append(str(error))
        stderr = process.stderr.read() if process.stderr else ""
        return_code = process.wait()
        completed = time.perf_counter()
        curl_metrics: dict[str, Any] = {}
        if metrics_path.exists() and metrics_path.stat().st_size:
            curl_metrics = json.loads(metrics_path.read_text(encoding="utf-8"))

    def ms(name: str) -> float | None:
        value = curl_metrics.get(name)
        try:
            return round(float(value) * 1000, 1)
        except (TypeError, ValueError):
            return None

    connect_ms = ms("time_connect")
    tls_ms = ms("time_appconnect")
    pretransfer_ms = ms("time_pretransfer")
    headers_ms = ms("time_starttransfer")
    total_ms = round((completed - started) * 1000, 1)
    ttft_ms = round((first_text_at - started) * 1000, 1) if first_text_at else None
    details = usage.get("completion_tokens_details") or {}
    response_text = "".join(response_parts)
    validation = validate_response(response_text, source)
    success = (
        return_code == 0
        and str(curl_metrics.get("http_code")) == "200"
        and bool(response_text)
        and all(validation[key] for key in ("response_json_valid", "group_coverage_valid", "literal_preservation_valid"))
    )
    result = {
        "provider": provider,
        "model": model,
        "control": control,
        "level": level,
        "round": round_number,
        "nonce": nonce,
        "success": success,
        "http_code": curl_metrics.get("http_code"),
        "proxy_dns_ms": ms("time_namelookup"),
        "proxy_connect_ms": connect_ms,
        "tls_ready_ms": tls_ms,
        "request_sent_ms": pretransfer_ms,
        "response_headers_ms": headers_ms,
        "server_wait_after_request_ms": round(headers_ms - pretransfer_ms, 1) if headers_ms is not None and pretransfer_ms is not None else None,
        "ttft_ms": ttft_ms,
        "generation_ms": round(total_ms - ttft_ms, 1) if ttft_ms is not None else None,
        "total_ms": total_ms,
        "prompt_tokens": usage.get("prompt_tokens"),
        "completion_tokens": usage.get("completion_tokens"),
        "reasoning_tokens": details.get("reasoning_tokens"),
        "total_tokens": usage.get("total_tokens"),
        "num_connects": curl_metrics.get("num_connects"),
        "remote_ip": curl_metrics.get("remote_ip"),
        "response_chars": len(response_text),
        "response_sha256": hashlib.sha256(response_text.encode("utf-8")).hexdigest(),
        "response_preview": response_text.replace("\n", " ")[:180],
        "response_text": response_text,
        "error": "; ".join(filter(None, [stderr.strip(), *parse_errors])) or None,
    }
    result.update(validation)
    return result


def summarize(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    summaries = []
    for provider, model, control, level in PROVIDERS:
        selected = [row for row in rows if row["provider"] == provider]
        transported = [
            row for row in selected
            if str(row.get("http_code")) == "200" and row.get("response_chars", 0) > 0
        ]
        summaries.append(
            {
                "provider": provider,
                "model": model,
                "control": control,
                "level": level,
                "successes": sum(bool(row["success"]) for row in selected),
                "transport_successes": len(transported),
                "runs": len(selected),
                "proxy_connect_ms_median": median(transported, "proxy_connect_ms"),
                "tls_ready_ms_median": median(transported, "tls_ready_ms"),
                "server_wait_ms_median": median(transported, "server_wait_after_request_ms"),
                "ttft_ms_median": median(transported, "ttft_ms"),
                "ttft_ms_p90": percentile(transported, "ttft_ms", 0.9),
                "generation_ms_median": median(transported, "generation_ms"),
                "total_ms_median": median(transported, "total_ms"),
                "total_ms_p90": percentile(transported, "total_ms", 0.9),
                "prompt_tokens_median": median(transported, "prompt_tokens"),
                "completion_tokens_median": median(transported, "completion_tokens"),
                "reasoning_tokens_median": median(transported, "reasoning_tokens"),
                "total_tokens_median": median(transported, "total_tokens"),
                "valid_json_runs": sum(bool(row["response_json_valid"]) for row in selected),
                "valid_group_coverage_runs": sum(bool(row["group_coverage_valid"]) for row in selected),
                "valid_literal_preservation_runs": sum(bool(row["literal_preservation_valid"]) for row in selected),
            }
        )
    return summaries


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--env-file", default="demo-server/.env")
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--proxy", default="http://127.0.0.1:7897")
    parser.add_argument("--timeout", type=int, default=210)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    load_env(Path(args.env_file))
    api_key = os.getenv("OPENLUX_API_KEY", "")
    if not api_key:
        raise SystemExit("OPENLUX_API_KEY is required")
    base_url = os.getenv("OPENLUX_BASE_URL", "https://api.openlux.ai/v1").rstrip("/")
    endpoint = base_url + "/chat/completions"
    source = json.loads(Path(args.input).read_text(encoding="utf-8"))
    rows: list[dict[str, Any]] = []
    for round_number in range(1, args.rounds + 1):
        ordered = PROVIDERS[round_number % len(PROVIDERS):] + PROVIDERS[:round_number % len(PROVIDERS)]
        for provider, model, control, level in ordered:
            print(f"[{round_number}/{args.rounds}] {provider} {model} {control}={level}", flush=True)
            row = run_once(endpoint, args.proxy, api_key, source, provider, model, control, level, round_number, args.timeout)
            rows.append(row)
            print(f"  success={row['success']} ttft={row['ttft_ms']}ms total={row['total_ms']}ms", flush=True)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "metadata": {
            "created_at": datetime.now(timezone.utc).isoformat(),
            "endpoint": endpoint,
            "proxy": args.proxy,
            "rounds": args.rounds,
            "cold_start_policy": "new curl process and TCP connection, Connection: close, no-cache headers, unique leading system-message nonce",
            "input_file": str(Path(args.input).resolve()),
            "source_max_tokens": source.get("max_tokens"),
        },
        "summary": summarize(rows),
        "runs": rows,
    }
    (output_dir / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output_dir / "runs.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0 if all(row["success"] for row in rows) else 2


if __name__ == "__main__":
    raise SystemExit(main())
