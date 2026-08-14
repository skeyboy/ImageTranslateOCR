#!/usr/bin/env python3
"""Benchmark two Gemini models through Google Native and OpenLux with thinking controls."""

from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import time
from dataclasses import fields
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any

import google_gemini_v4_cold_case_benchmark as google_bench
import openlux_v4_cold_case_benchmark as openlux_bench


MODELS = ("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
LEVELS = ("omitted", "minimal", "low", "medium", "high")
PROVIDERS = ("google-native", "openlux")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path("demo-server/.env"))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--request-delay", type=float, default=2.0)
    parser.add_argument("--rate-limit-cooldown", type=float, default=10.0)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--proxy")
    parser.add_argument("--continuous-repeats", type=int, default=10)
    parser.add_argument("--continuous-case", default="case-3-m")
    parser.add_argument("--resume", action="store_true")
    return parser.parse_args()


def normalized(text: str) -> str:
    return "".join(text.split()).lower()


def add_reference_similarity(row: dict[str, Any], case: dict[str, Any]) -> None:
    try:
        translations = json.loads(row["response_text"])["translations"]
    except (KeyError, TypeError, json.JSONDecodeError):
        row["reference_similarity"] = 0.0
        return
    actual = {
        item.get("groupId"): item.get("translatedText", "")
        for item in translations if isinstance(item, dict)
    }
    scores = [
        SequenceMatcher(
            None,
            normalized(reference["translatedText"]),
            normalized(actual.get(reference["groupId"], "")),
        ).ratio()
        for reference in case["references"]
    ]
    row["reference_similarity"] = round(100 * statistics.mean(scores), 1) if scores else 0.0


def run_key(row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        row["phase"], row["provider"], row["model"], row["level"],
        row["case_id"], row["iteration"],
    )


def load_checkpoint(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def append_checkpoint(path: Path, row: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def summarize(rows: list[dict[str, Any]], request_delay: float) -> dict[str, Any]:
    coverage = []
    continuous = []
    for provider in PROVIDERS:
        for model in MODELS:
            for level in LEVELS:
                selected = [
                    row for row in rows
                    if row["provider"] == provider and row["model"] == model and row["level"] == level
                ]
                coverage_rows = [row for row in selected if row["phase"] == "coverage"]
                valid_coverage = [row for row in coverage_rows if row["success"]]
                coverage.append({
                    "provider": provider, "model": model, "level": level,
                    "successes": len(valid_coverage), "runs": len(coverage_rows),
                    "total_ms_median": round(statistics.median(row["total_ms"] for row in valid_coverage), 1) if valid_coverage else None,
                    "total_ms_p90": openlux_bench.percentile([row["total_ms"] for row in valid_coverage], 0.9),
                    "json_quality_median": round(statistics.median(row["json_quality"] for row in coverage_rows), 1) if coverage_rows else None,
                    "reference_similarity_median": round(statistics.median(row["reference_similarity"] for row in valid_coverage), 1) if valid_coverage else None,
                    "thoughts_tokens_median": round(statistics.median(row["thoughts_tokens"] for row in valid_coverage if row.get("thoughts_tokens") is not None), 1)
                    if any(row.get("thoughts_tokens") is not None for row in valid_coverage) else None,
                })
                sequence_rows = [row for row in selected if row["phase"] == "continuous-10"]
                valid_sequence = [row for row in sequence_rows if row["success"]]
                request_total = round(sum(row["total_ms"] for row in sequence_rows), 1)
                continuous.append({
                    "provider": provider, "model": model, "level": level,
                    "successes": len(valid_sequence), "runs": len(sequence_rows),
                    "request_total_ms": request_total,
                    "request_mean_ms": round(statistics.mean(row["total_ms"] for row in sequence_rows), 1) if sequence_rows else None,
                    "request_median_ms": round(statistics.median(row["total_ms"] for row in sequence_rows), 1) if sequence_rows else None,
                    "scheduled_wall_ms": round(request_total + max(0, len(sequence_rows) - 1) * request_delay * 1000, 1),
                    "json_quality_median": round(statistics.median(row["json_quality"] for row in sequence_rows), 1) if sequence_rows else None,
                    "reference_similarity_median": round(statistics.median(row["reference_similarity"] for row in valid_sequence), 1) if valid_sequence else None,
                    "unique_response_hashes": len({row["response_sha256"] for row in valid_sequence}),
                    "thoughts_tokens_total": sum(row.get("thoughts_tokens") or 0 for row in valid_sequence),
                })
    return {"coverage": coverage, "continuous_10": continuous}


def write_report(output: Path, metadata: dict[str, Any], rows: list[dict[str, Any]], request_delay: float) -> None:
    summary = summarize(rows, request_delay)
    document = {"metadata": metadata, "summary": summary, "runs": rows}
    (output / "results.json").write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if rows:
        fieldnames = sorted({key for row in rows for key in row})
        with (output / "runs.csv").open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    if args.request_delay < 0 or args.rate_limit_cooldown < 0:
        raise SystemExit("delays must be non-negative")
    if args.continuous_repeats < 1:
        raise SystemExit("--continuous-repeats must be positive")
    google_bench.load_env(args.env_file)
    google_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    openlux_key = os.getenv("OPENLUX_API_KEY")
    if not google_key or not openlux_key:
        raise SystemExit("GEMINI_API_KEY/GOOGLE_API_KEY and OPENLUX_API_KEY are required")
    proxy = args.proxy if args.proxy is not None else os.getenv("GEMINI_PROXY_URL", "http://127.0.0.1:7897")
    if not proxy:
        raise SystemExit("a proxy is required")
    google_base = os.getenv("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
    openlux_base = os.getenv("OPENLUX_BASE_URL", "https://api.openlux.ai/v1").rstrip("/")
    openlux_endpoint = openlux_base if openlux_base.endswith("/chat/completions") else openlux_base + "/chat/completions"

    case_document = json.loads(args.cases.read_text(encoding="utf-8"))
    cases = case_document.get("cases") or []
    if len(cases) != 5:
        raise SystemExit(f"expected five cases, found {len(cases)}")
    by_case = {case["case_id"]: case for case in cases}
    if args.continuous_case not in by_case:
        raise SystemExit(f"continuous case not found: {args.continuous_case}")
    case_fields = {field.name for field in fields(openlux_bench.Case)}
    openlux_cases = {
        case["case_id"]: openlux_bench.Case(**{key: value for key, value in case.items() if key in case_fields})
        for case in cases
    }

    args.output_dir.mkdir(parents=True, exist_ok=True)
    checkpoint = args.output_dir / "runs.jsonl"
    if checkpoint.exists() and not args.resume:
        raise SystemExit(f"checkpoint exists; use --resume or a new output directory: {checkpoint}")
    rows = load_checkpoint(checkpoint) if args.resume else []
    completed = {run_key(row) for row in rows}
    metadata = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "models": list(MODELS), "providers": list(PROVIDERS), "levels": list(LEVELS),
        "google_endpoint_template": f"{google_base}/models/{{model}}:generateContent",
        "openlux_endpoint": openlux_endpoint, "proxy": proxy,
        "cases": [case["case_id"] for case in cases],
        "continuous_case": args.continuous_case, "continuous_repeats": args.continuous_repeats,
        "cold_start": "new curl process/TCP connection; Connection: close; no-cache headers; unique nonce",
        "concurrency": 1, "request_delay_seconds": args.request_delay,
        "planned_requests": len(PROVIDERS) * len(MODELS) * len(LEVELS) * (len(cases) + args.continuous_repeats),
    }

    def execute(provider: str, model: str, level: str, case: dict[str, Any], phase: str, iteration: int) -> None:
        key = (phase, provider, model, level, case["case_id"], iteration)
        if key in completed:
            return
        print(f"[{len(rows) + 1}/{metadata['planned_requests']}] {phase} {provider} {model} level={level} case={case['case_id']} iteration={iteration}", flush=True)
        if provider == "google-native":
            endpoint = f"{google_base}/models/{model}:generateContent"
            row = google_bench.run_once(endpoint, proxy, google_key, model, case, level, args.timeout)
        else:
            row = openlux_bench.run_cold(
                openlux_endpoint, proxy, openlux_key, openlux_cases[case["case_id"]],
                model, "thinkingLevel", level, args.timeout,
            )
            row["thoughts_tokens"] = row.pop("reasoning_tokens", None)
        row.update({"phase": phase, "provider": provider, "iteration": iteration})
        add_reference_similarity(row, case)
        append_checkpoint(checkpoint, row)
        rows.append(row)
        completed.add(key)
        write_report(args.output_dir, metadata, rows, args.request_delay)
        print(
            f"  http={row['http_code']} ok={row['success']} total={row['total_ms']} "
            f"json={row['json_quality']} similarity={row['reference_similarity']} thoughts={row.get('thoughts_tokens')}",
            flush=True,
        )
        time.sleep(args.rate_limit_cooldown if row["http_code"] == "429" else args.request_delay)

    for provider in PROVIDERS:
        for model in MODELS:
            for level in LEVELS:
                for index, case in enumerate(cases, start=1):
                    execute(provider, model, level, case, "coverage", index)

    continuous_case = by_case[args.continuous_case]
    for provider in PROVIDERS:
        for model in MODELS:
            for level in LEVELS:
                for iteration in range(1, args.continuous_repeats + 1):
                    execute(provider, model, level, continuous_case, "continuous-10", iteration)

    metadata["completed_at"] = datetime.now(timezone.utc).isoformat()
    write_report(args.output_dir, metadata, rows, args.request_delay)
    print(json.dumps(summarize(rows, args.request_delay), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
