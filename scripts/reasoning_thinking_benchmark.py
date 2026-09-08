#!/usr/bin/env python3
"""Benchmark reasoning_effort and Gemini thinkingLevel on an OpenAI-compatible API.

The two controls are intentionally tested in separate requests:

* reasoning_effort is sent as a top-level Chat Completions field.
* thinkingLevel is sent through OpenLux's Gemini passthrough path as
  google.thinking_config.thinking_level. With the OpenAI Python SDK, ``google``
  would be passed inside the SDK-only ``extra_body`` argument, which the SDK
  merges into the outgoing HTTP body. This script sends raw HTTP, so it sends
  the merged wire representation directly. The equivalent LangChain Python
  constructor argument is named thinking_level.

No parameter fallback is performed. A rejected case remains a failed case so
the report reflects what the endpoint and model actually support.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


DEFAULT_PROMPT = (
    "A farmer has 17 sheep. All but 9 run away. How many remain? "
    "Answer in Chinese with one short sentence and preserve the number."
)
REASONING_LEVELS = ("none", "minimal", "low", "medium", "high")
THINKING_LEVELS = ("minimal", "low", "medium", "high")


@dataclass(frozen=True)
class Case:
    family: str
    level: str

    @property
    def name(self) -> str:
        return "default" if self.family == "default" else f"{self.family}:{self.level}"


@dataclass
class RunResult:
    case: str
    family: str
    level: str
    round: int
    order: int
    success: bool
    status_code: int | None
    ttft_ms: int | None
    total_ms: int
    prompt_tokens: int | None
    completion_tokens: int | None
    reasoning_tokens: int | None
    total_tokens: int | None
    finish_reason: str | None
    response_preview: str
    error: str | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare reasoning_effort and Gemini thinkingLevel latency."
    )
    parser.add_argument("--env-file", default="demo-server/.env")
    parser.add_argument("--base-url", help="Defaults to OPENLUX_BASE_URL.")
    parser.add_argument("--api-key", help="Prefer OPENLUX_API_KEY instead of this option.")
    parser.add_argument("--model", help="Defaults to OPENLUX_MODEL.")
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--timeout", type=float, help="Defaults to OPENLUX_TIMEOUT_SECONDS.")
    parser.add_argument("--max-tokens", type=int, default=300)
    parser.add_argument("--prompt", default=DEFAULT_PROMPT)
    parser.add_argument(
        "--families",
        default="reasoning,thinking",
        help="Comma-separated: reasoning,thinking. The omitted-parameter baseline is always included.",
    )
    parser.add_argument(
        "--reasoning-levels",
        default=",".join(REASONING_LEVELS),
        help="Top-level reasoning_effort values.",
    )
    parser.add_argument(
        "--thinking-levels",
        default=",".join(THINKING_LEVELS),
        help="Gemini thinkingLevel values; sent through the OpenLux passthrough path.",
    )
    parser.add_argument(
        "--output-dir",
        help="Defaults to build/reports/reasoning-thinking-benchmark/<UTC timestamp>.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print redacted payloads only.")
    return parser.parse_args()


def load_env_file(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if value[:1] == value[-1:] and value.startswith(("'", '"')):
            value = value[1:-1]
        os.environ.setdefault(key, value)


def csv_values(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def endpoint(base_url: str) -> str:
    base = base_url.rstrip("/")
    return base if base.endswith("/chat/completions") else base + "/chat/completions"


def build_cases(args: argparse.Namespace) -> list[Case]:
    families = set(csv_values(args.families))
    unsupported = families - {"reasoning", "thinking"}
    if unsupported:
        raise ValueError(f"Unsupported families: {', '.join(sorted(unsupported))}")
    cases = [Case("default", "omitted")]
    if "reasoning" in families:
        cases.extend(Case("reasoning_effort", value) for value in csv_values(args.reasoning_levels))
    if "thinking" in families:
        cases.extend(Case("thinkingLevel", value) for value in csv_values(args.thinking_levels))
    return cases


def build_payload(model: str, prompt: str, max_tokens: int, case: Case) -> dict[str, Any]:
    body: dict[str, Any] = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": 1,
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    if case.family == "reasoning_effort":
        body["reasoning_effort"] = case.level
    elif case.family == "thinkingLevel":
        body["google"] = {"thinking_config": {"thinking_level": case.level}}
    return body


def nested_int(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def execute_case(
    url: str,
    api_key: str,
    body: dict[str, Any],
    case: Case,
    round_number: int,
    order: int,
    timeout: float,
) -> RunResult:
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        },
        method="POST",
    )
    started = time.perf_counter()
    first_content_at: float | None = None
    content_parts: list[str] = []
    usage: dict[str, Any] = {}
    finish_reason: str | None = None
    status_code: int | None = None
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status_code = response.status
            for raw_line in response:
                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if not data or data == "[DONE]":
                    continue
                chunk = json.loads(data)
                if isinstance(chunk.get("usage"), dict):
                    usage = chunk["usage"]
                choices = chunk.get("choices") or []
                if not choices:
                    continue
                choice = choices[0]
                finish_reason = choice.get("finish_reason") or finish_reason
                delta = choice.get("delta") or {}
                text = delta.get("content")
                if isinstance(text, str) and text:
                    if first_content_at is None:
                        first_content_at = time.perf_counter()
                    content_parts.append(text)
        completed = time.perf_counter()
        details = usage.get("completion_tokens_details") or {}
        return RunResult(
            case=case.name,
            family=case.family,
            level=case.level,
            round=round_number,
            order=order,
            success=True,
            status_code=status_code,
            ttft_ms=round((first_content_at - started) * 1000) if first_content_at else None,
            total_ms=round((completed - started) * 1000),
            prompt_tokens=nested_int(usage.get("prompt_tokens")),
            completion_tokens=nested_int(usage.get("completion_tokens")),
            reasoning_tokens=nested_int(details.get("reasoning_tokens")),
            total_tokens=nested_int(usage.get("total_tokens")),
            finish_reason=finish_reason,
            response_preview="".join(content_parts).replace("\n", " ")[:160],
            error=None,
        )
    except urllib.error.HTTPError as error:
        completed = time.perf_counter()
        response_text = error.read().decode("utf-8", errors="replace")[:500]
        return failed_result(case, round_number, order, error.code, started, completed, response_text)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        completed = time.perf_counter()
        return failed_result(case, round_number, order, status_code, started, completed, str(error))


def failed_result(
    case: Case,
    round_number: int,
    order: int,
    status_code: int | None,
    started: float,
    completed: float,
    message: str,
) -> RunResult:
    return RunResult(
        case=case.name,
        family=case.family,
        level=case.level,
        round=round_number,
        order=order,
        success=False,
        status_code=status_code,
        ttft_ms=None,
        total_ms=round((completed - started) * 1000),
        prompt_tokens=None,
        completion_tokens=None,
        reasoning_tokens=None,
        total_tokens=None,
        finish_reason=None,
        response_preview="",
        error=message,
    )


def median(values: Iterable[int | None]) -> int | None:
    present = [value for value in values if value is not None]
    return round(statistics.median(present)) if present else None


def summarize(results: list[RunResult]) -> list[dict[str, Any]]:
    summaries: list[dict[str, Any]] = []
    for case_name in dict.fromkeys(result.case for result in results):
        rows = [result for result in results if result.case == case_name]
        successes = [result for result in rows if result.success]
        summaries.append(
            {
                "case": case_name,
                "runs": len(rows),
                "successes": len(successes),
                "success_rate": round(len(successes) / len(rows), 3),
                "ttft_ms_median": median(row.ttft_ms for row in successes),
                "total_ms_median": median(row.total_ms for row in successes),
                "reasoning_tokens_median": median(row.reasoning_tokens for row in successes),
                "total_tokens_median": median(row.total_tokens for row in successes),
            }
        )
    return summaries


def write_reports(output_dir: Path, metadata: dict[str, Any], results: list[RunResult]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    summaries = summarize(results)
    (output_dir / "results.json").write_text(
        json.dumps(
            {"metadata": metadata, "summary": summaries, "runs": [asdict(row) for row in results]},
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    with (output_dir / "runs.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(asdict(results[0]).keys()))
        writer.writeheader()
        writer.writerows(asdict(row) for row in results)


def print_summary(summary: list[dict[str, Any]]) -> None:
    columns = ("case", "successes", "runs", "ttft_ms_median", "total_ms_median", "reasoning_tokens_median", "total_tokens_median")
    labels = ("case", "ok", "runs", "TTFT ms", "total ms", "reasoning tok", "total tok")
    widths = [max(len(labels[index]), *(len(str(row[key])) for row in summary)) for index, key in enumerate(columns)]
    print("  ".join(label.ljust(widths[index]) for index, label in enumerate(labels)))
    print("  ".join("-" * width for width in widths))
    for row in summary:
        print("  ".join(str(row[key]).ljust(widths[index]) for index, key in enumerate(columns)))


def main() -> int:
    args = parse_args()
    if not 1 <= args.rounds <= 20:
        raise SystemExit("--rounds must be between 1 and 20")
    load_env_file(Path(args.env_file))
    base_url = args.base_url or os.getenv("OPENLUX_BASE_URL", "https://api.openlux.ai/v1")
    api_key = args.api_key or os.getenv("OPENLUX_API_KEY", "")
    model = args.model or os.getenv("OPENLUX_MODEL", "gemini-3.5-flash-lite")
    timeout = args.timeout or float(os.getenv("OPENLUX_TIMEOUT_SECONDS", "210"))
    cases = build_cases(args)
    if not api_key and not args.dry_run:
        raise SystemExit("OPENLUX_API_KEY is required (or pass --api-key).")

    if args.dry_run:
        for case in cases:
            print(json.dumps({"case": case.name, "payload": build_payload(model, args.prompt, args.max_tokens, case)}, ensure_ascii=False, indent=2))
        return 0

    output_dir = Path(args.output_dir) if args.output_dir else Path(
        "build/reports/reasoning-thinking-benchmark/" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    )
    results: list[RunResult] = []
    url = endpoint(base_url)
    for round_index in range(args.rounds):
        ordered = cases[round_index % len(cases):] + cases[:round_index % len(cases)]
        for order_index, case in enumerate(ordered, start=1):
            print(f"[{round_index + 1}/{args.rounds} #{order_index}/{len(cases)}] {case.name}", flush=True)
            result = execute_case(
                url,
                api_key,
                build_payload(model, args.prompt, args.max_tokens, case),
                case,
                round_index + 1,
                order_index,
                timeout,
            )
            results.append(result)
            outcome = f"total={result.total_ms}ms ttft={result.ttft_ms}ms" if result.success else f"FAILED HTTP={result.status_code}"
            print(f"  {outcome}", flush=True)

    metadata = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "endpoint": url,
        "model": model,
        "rounds": args.rounds,
        "prompt": args.prompt,
        "max_tokens": args.max_tokens,
        "stream": True,
        "thinking_level_wire_path": "google.thinking_config.thinking_level",
        "openai_sdk_equivalent": "extra_body={'google': {'thinking_config': {'thinking_level': level}}}",
        "fallback_enabled": False,
    }
    write_reports(output_dir, metadata, results)
    print()
    print_summary(summarize(results))
    print(f"\nReports: {output_dir.resolve()}")
    return 0 if all(result.success for result in results) else 2


if __name__ == "__main__":
    sys.exit(main())
