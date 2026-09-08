#!/usr/bin/env python3
"""Compare Google Gemini and an OpenLux model with coverage and 10-run batches."""

from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import time
from dataclasses import fields
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import google_gemini_v4_cold_case_benchmark as google_bench
import openlux_responses_v4_benchmark as openlux_responses
import openlux_v4_cold_case_benchmark as openlux_bench


LEVELS = ("omitted", "minimal", "low", "medium", "high")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path("demo-server/.env"))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--google-model", default="gemini-3.5-flash-lite")
    parser.add_argument("--openlux-model", default="gpt-5.3-codex-spark")
    parser.add_argument("--continuous-case", default="case-3-m")
    parser.add_argument("--continuous-rounds", type=int, default=10)
    parser.add_argument("--coverage-delay", type=float, default=2.0)
    parser.add_argument("--continuous-delay", type=float, default=1.0)
    parser.add_argument("--rate-limit-cooldown", type=float, default=10.0)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--proxy")
    parser.add_argument("--providers", default="google-native,openlux")
    parser.add_argument("--phases", default="coverage,continuous")
    return parser.parse_args()


def write_checkpoint(output: Path, metadata: dict[str, Any], rows: list[dict[str, Any]], batches: list[dict[str, Any]]) -> None:
    (output / "checkpoint.json").write_text(
        json.dumps({"metadata": metadata, "runs": rows, "continuous_batches": batches}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def sleep_after(row: dict[str, Any], normal_delay: float, cooldown: float) -> None:
    time.sleep(cooldown if row.get("http_code") == "429" else normal_delay)


def summarize(rows: list[dict[str, Any]], phase: str) -> list[dict[str, Any]]:
    summary = []
    for provider in ("google-native", "openlux"):
        for level in LEVELS:
            selected = [row for row in rows if row["phase"] == phase and row["provider"] == provider and row["level"] == level]
            successful = [row for row in selected if row["success"]]
            summary.append({
                "provider": provider,
                "model": selected[0]["model"] if selected else None,
                "phase": phase,
                "level": level,
                "successes": len(successful),
                "runs": len(selected),
                "total_ms_sum": round(sum(row["total_ms"] for row in selected), 1),
                "total_ms_median": round(statistics.median(row["total_ms"] for row in successful), 1) if successful else None,
                "json_quality_median": round(statistics.median(row["json_quality"] for row in selected), 1) if selected else None,
                "reference_similarity_median": round(statistics.median(
                    row["reference_similarity"] for row in successful if row.get("reference_similarity") is not None
                ), 1) if any(row.get("reference_similarity") is not None for row in successful) else None,
            })
    return summary


def main() -> int:
    args = parse_args()
    if args.continuous_rounds != 10:
        raise SystemExit("--continuous-rounds must be 10 for this benchmark")
    if min(args.coverage_delay, args.continuous_delay, args.rate_limit_cooldown) < 0:
        raise SystemExit("delays must be non-negative")
    providers = tuple(item.strip() for item in args.providers.split(",") if item.strip())
    phases = tuple(item.strip() for item in args.phases.split(",") if item.strip())
    if not providers or set(providers) - {"google-native", "openlux"}:
        raise SystemExit("--providers must contain google-native and/or openlux")
    if not phases or set(phases) - {"coverage", "continuous"}:
        raise SystemExit("--phases must contain coverage and/or continuous")
    google_bench.load_env(args.env_file)
    api_key_google = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    api_key_openlux = os.getenv("OPENLUX_API_KEY")
    if not api_key_google or not api_key_openlux:
        raise SystemExit("GEMINI_API_KEY and OPENLUX_API_KEY are required")
    proxy = args.proxy if args.proxy is not None else os.getenv("GEMINI_PROXY_URL", "http://127.0.0.1:7897")
    if not proxy:
        raise SystemExit("a proxy is required")
    google_base = os.getenv("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
    google_endpoint = f"{google_base}/models/{args.google_model}:generateContent"
    openlux_endpoint = os.getenv("OPENLUX_BASE_URL", "https://api.openlux.ai/v1").rstrip("/") + "/responses"

    document = json.loads(args.cases.read_text(encoding="utf-8"))
    google_cases = document.get("cases") or []
    if len(google_cases) != 5:
        raise SystemExit(f"expected five cases, found {len(google_cases)}")
    allowed = {field.name for field in fields(openlux_bench.Case)}
    openlux_cases = [openlux_bench.Case(**{key: value for key, value in case.items() if key in allowed}) for case in google_cases]
    google_continuous = next((case for case in google_cases if case["case_id"] == args.continuous_case), None)
    openlux_continuous = next((case for case in openlux_cases if case.case_id == args.continuous_case), None)
    if google_continuous is None or openlux_continuous is None:
        raise SystemExit(f"continuous case not found: {args.continuous_case}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    metadata = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "cases": str(args.cases.resolve()),
        "levels": list(LEVELS),
        "google": {"model": args.google_model, "endpoint": google_endpoint, "parameter": "generationConfig.thinkingConfig.thinkingLevel"},
        "openlux": {"model": args.openlux_model, "endpoint": openlux_endpoint, "parameter": "reasoning_effort"},
        "proxy": proxy,
        "coverage_delay_seconds": args.coverage_delay,
        "continuous_delay_seconds": args.continuous_delay,
        "continuous_rounds": args.continuous_rounds,
        "continuous_case": args.continuous_case,
        "concurrency": 1,
        "cache_policy": "new curl/TCP connection, no-cache headers, unique nonce",
    }
    rows: list[dict[str, Any]] = []
    batches: list[dict[str, Any]] = []

    def record(row: dict[str, Any], provider: str, phase: str, iteration: int | None) -> None:
        row.update({"provider": provider, "phase": phase, "iteration": iteration})
        rows.append(row)
        print(
            f"  http={row['http_code']} ok={row['success']} total={row['total_ms']} "
            f"json={row['json_quality']} similarity={row.get('reference_similarity')}",
            flush=True,
        )
        write_checkpoint(args.output_dir, metadata, rows, batches)

    if "coverage" in phases:
        for provider in providers:
            for case_index in range(5):
                for level in LEVELS:
                    case_id = google_cases[case_index]["case_id"]
                    print(f"[coverage] {provider} {case_id} level={level}", flush=True)
                    if provider == "google-native":
                        row = google_bench.run_once(
                            google_endpoint, proxy, api_key_google, args.google_model,
                            google_cases[case_index], level, args.timeout,
                        )
                    else:
                        row = openlux_responses.run_once(
                            openlux_endpoint, proxy, api_key_openlux, openlux_cases[case_index],
                            args.openlux_model, level, args.timeout,
                        )
                    record(row, provider, "coverage", None)
                    is_last = provider == providers[-1] and case_index == 4 and level == LEVELS[-1]
                    if not is_last:
                        sleep_after(row, args.coverage_delay, args.rate_limit_cooldown)

    if "continuous" in phases:
        for provider in providers:
            for level in LEVELS:
                batch_started = time.perf_counter()
                batch_rows = []
                for iteration in range(1, args.continuous_rounds + 1):
                    print(f"[continuous] {provider} {args.continuous_case} level={level} run={iteration}/10", flush=True)
                    if provider == "google-native":
                        row = google_bench.run_once(
                            google_endpoint, proxy, api_key_google, args.google_model,
                            google_continuous, level, args.timeout,
                        )
                    else:
                        row = openlux_responses.run_once(
                            openlux_endpoint, proxy, api_key_openlux, openlux_continuous,
                            args.openlux_model, level, args.timeout,
                        )
                    record(row, provider, "continuous", iteration)
                    batch_rows.append(row)
                    if iteration < args.continuous_rounds:
                        sleep_after(row, args.continuous_delay, args.rate_limit_cooldown)
                batches.append({
                    "provider": provider,
                    "model": batch_rows[0]["model"],
                    "level": level,
                    "case_id": args.continuous_case,
                    "runs": len(batch_rows),
                    "successes": sum(row["success"] for row in batch_rows),
                    "request_time_sum_ms": round(sum(row["total_ms"] for row in batch_rows), 1),
                    "wall_time_ms": round((time.perf_counter() - batch_started) * 1000, 1),
                    "json_quality_median": round(statistics.median(row["json_quality"] for row in batch_rows), 1),
                })
                write_checkpoint(args.output_dir, metadata, rows, batches)

    report = {
        "metadata": metadata,
        "coverage_summary": summarize(rows, "coverage"),
        "continuous_summary": summarize(rows, "continuous"),
        "continuous_batches": batches,
        "runs": rows,
    }
    (args.output_dir / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    fieldnames = list(dict.fromkeys(key for row in rows for key in row))
    with (args.output_dir / "runs.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps({"coverage": report["coverage_summary"], "continuous": batches}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
