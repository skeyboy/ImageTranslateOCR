#!/usr/bin/env python3
"""Probe GPT-5.3 Codex Spark through Google Native and OpenLux Responses."""

from __future__ import annotations

import argparse
import json
import os
import time
from dataclasses import fields
from datetime import datetime, timezone
from pathlib import Path

import google_gemini_v4_cold_case_benchmark as google_bench
import openlux_responses_v4_benchmark as responses_bench
import openlux_v4_cold_case_benchmark as chat_bench


MODEL = "gpt-5.3-codex-spark"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path("demo-server/.env"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--proxy")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--probe-delay", type=float, default=10.0)
    args = parser.parse_args()
    google_bench.load_env(args.env_file)
    google_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    openlux_key = os.getenv("OPENLUX_API_KEY")
    if not google_key or not openlux_key:
        raise SystemExit("GEMINI_API_KEY and OPENLUX_API_KEY are required")
    proxy = args.proxy if args.proxy is not None else os.getenv("GEMINI_PROXY_URL", "http://127.0.0.1:7897")
    document = json.loads(args.cases.read_text(encoding="utf-8"))
    case_value = next(case for case in document["cases"] if case["case_id"] == "case-3-m")
    allowed = {field.name for field in fields(chat_bench.Case)}
    case = chat_bench.Case(**{key: value for key, value in case_value.items() if key in allowed})

    google_base = os.getenv("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
    google_endpoint = f"{google_base}/models/{MODEL}:generateContent"
    openlux_endpoint = os.getenv("OPENLUX_BASE_URL", "https://api.openlux.ai/v1").rstrip("/") + "/responses"
    google_row = google_bench.run_once(
        google_endpoint, proxy, google_key, MODEL, case_value, "omitted", args.timeout,
    )
    google_row["provider"] = "google-native"
    google_row["endpoint"] = google_endpoint

    openlux_rows = []
    for iteration in range(1, 4):
        row = responses_bench.run_once(
            openlux_endpoint, proxy, openlux_key, case, MODEL, "omitted", args.timeout,
        )
        row.update({"provider": "openlux", "endpoint": openlux_endpoint, "iteration": iteration})
        openlux_rows.append(row)
        print(
            f"openlux probe {iteration}/3 http={row['http_code']} success={row['success']} "
            f"total={row['total_ms']} error={row['error']}",
            flush=True,
        )
        if iteration < 3:
            time.sleep(args.probe_delay)

    report = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "model": MODEL,
        "proxy": proxy,
        "google_native": google_row,
        "openlux_responses": openlux_rows,
        "openlux_recovered": all(row["success"] for row in openlux_rows),
        "decision": "run-full-75" if all(row["success"] for row in openlux_rows) else "stop-upstream-unavailable",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"google-native http={google_row['http_code']} success={google_row['success']} "
        f"error={google_row['error']}",
        flush=True,
    )
    print(f"decision={report['decision']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
