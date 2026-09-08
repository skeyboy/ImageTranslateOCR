#!/usr/bin/env python3
"""Benchmark GPT-4.1 translation while treating reasoning control as unsupported."""

from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import openlux_v4_cold_case_benchmark as bench


PROBE_LEVELS = ("minimal", "low", "medium", "high")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path("demo-server/.env"))
    parser.add_argument("--model", default="gpt-4.1")
    parser.add_argument("--continuous-case", default="case-3-m")
    parser.add_argument("--coverage-delay", type=float, default=2.0)
    parser.add_argument("--continuous-delay", type=float, default=1.0)
    parser.add_argument("--cooldown", type=float, default=10.0)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--proxy", default="http://127.0.0.1:7897")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    bench.load_env(args.env_file)
    api_key = os.getenv("OPENLUX_API_KEY")
    if not api_key:
        raise SystemExit("OPENLUX_API_KEY is required")
    endpoint = os.getenv("OPENLUX_BASE_URL", "https://api.openlux.ai/v1").rstrip("/") + "/chat/completions"
    raw_cases = json.loads(args.cases.read_text(encoding="utf-8")).get("cases") or []
    cases = [bench.Case(**case) for case in raw_cases]
    if len(cases) != 5:
        raise SystemExit(f"expected five cases, found {len(cases)}")
    continuous_case = next((case for case in cases if case.case_id == args.continuous_case), None)
    if continuous_case is None:
        raise SystemExit(f"continuous case not found: {args.continuous_case}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, Any]] = []

    metadata = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "model": args.model, "endpoint": endpoint, "proxy": args.proxy,
        "reasoning_support_assumption": "unsupported; omitted is the production configuration",
        "parameter_probes": list(PROBE_LEVELS),
        "coverage_cases": len(cases), "continuous_case": args.continuous_case,
        "continuous_rounds": 10, "concurrency": 1,
        "coverage_delay_seconds": args.coverage_delay,
        "continuous_delay_seconds": args.continuous_delay,
        "cache_policy": "new curl/TCP connection, no-cache headers, unique nonce",
    }

    def record(row: dict[str, Any], phase: str, iteration: int | None = None) -> None:
        row.update({"provider": "openlux", "phase": phase, "iteration": iteration})
        rows.append(row)
        (args.output_dir / "checkpoint.json").write_text(
            json.dumps({"metadata": metadata, "runs": rows}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(
            f"  http={row['http_code']} ok={row['success']} total={row['total_ms']} "
            f"json={row['json_quality']} reasoning={row['reasoning_tokens']} error={row['error']}",
            flush=True,
        )

    for index, level in enumerate(PROBE_LEVELS):
        print(f"[parameter-probe] {args.model} reasoning_effort={level}", flush=True)
        row = bench.run_cold(endpoint, args.proxy, api_key, continuous_case, args.model, "reasoning_effort", level, args.timeout)
        record(row, "parameter-probe")
        if index < len(PROBE_LEVELS) - 1:
            time.sleep(args.cooldown if row["http_code"] == "429" else args.coverage_delay)

    for index, case in enumerate(cases):
        print(f"[coverage] {args.model} {case.case_id} reasoning=omitted", flush=True)
        row = bench.run_cold(endpoint, args.proxy, api_key, case, args.model, "reasoning_effort", "omitted", args.timeout)
        record(row, "coverage")
        if index < len(cases) - 1:
            time.sleep(args.cooldown if row["http_code"] == "429" else args.coverage_delay)

    batch_started = time.perf_counter()
    batch_rows = []
    for iteration in range(1, 11):
        print(f"[continuous] {args.model} reasoning=omitted run={iteration}/10", flush=True)
        row = bench.run_cold(endpoint, args.proxy, api_key, continuous_case, args.model, "reasoning_effort", "omitted", args.timeout)
        record(row, "continuous", iteration)
        batch_rows.append(row)
        if iteration < 10:
            time.sleep(args.cooldown if row["http_code"] == "429" else args.continuous_delay)

    batch = {
        "runs": 10, "successes": sum(row["success"] for row in batch_rows),
        "request_time_sum_ms": round(sum(row["total_ms"] for row in batch_rows), 1),
        "wall_time_ms": round((time.perf_counter() - batch_started) * 1000, 1),
        "total_ms_median": round(statistics.median(row["total_ms"] for row in batch_rows), 1),
        "json_quality_median": round(statistics.median(row["json_quality"] for row in batch_rows), 1),
        "reference_similarity_median": round(statistics.median(row["reference_similarity"] for row in batch_rows), 1),
    }
    report = {"metadata": metadata, "continuous_batch": batch, "runs": rows}
    (args.output_dir / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    fieldnames = list(dict.fromkeys(key for row in rows for key in row))
    with (args.output_dir / "runs.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps(batch, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
