#!/usr/bin/env python3
"""Benchmark native Gemini thinking levels using an OpenAI-style JSON payload."""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_LEVELS = ("minimal", "low", "medium", "high")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("payload", type=Path)
    parser.add_argument("--levels", default=",".join(DEFAULT_LEVELS))
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--proxy", default=os.getenv("GEMINI_PROXY_URL", "http://127.0.0.1:7897"))
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def load_payload(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    messages = payload.get("messages")
    if not isinstance(messages, list) or not messages:
        raise ValueError("payload.messages must be a non-empty array")
    response_format = payload.get("response_format", {})
    schema = response_format.get("json_schema", {}).get("schema")
    if not isinstance(schema, dict):
        raise ValueError("payload.response_format.json_schema.schema is required")
    return payload


def joined_contents(messages: list[dict[str, Any]]) -> str:
    parts = []
    for message in messages:
        role = str(message.get("role", "user"))
        content = message.get("content")
        if not isinstance(content, str):
            raise ValueError("every message content must be a string")
        parts.append(f"{role}:\n{content}")
    return "\n".join(parts)


def expected_group_ids(messages: list[dict[str, Any]]) -> list[str]:
    user_messages = [item for item in messages if item.get("role") == "user"]
    if not user_messages:
        raise ValueError("payload must contain a user message")
    document = json.loads(user_messages[-1]["content"])
    groups = document.get("translateGroups") or []
    ids = [group.get("groupId") for group in groups]
    if not ids or any(not isinstance(group_id, str) for group_id in ids):
        raise ValueError("user document must contain translateGroups with groupId")
    return ids


def validate_response(raw: str, expected_ids: list[str]) -> list[dict[str, Any]]:
    parsed = json.loads(raw)
    translations = parsed.get("translations")
    if not isinstance(translations, list):
        raise ValueError("response.translations must be an array")
    actual_ids = [item.get("groupId") for item in translations]
    if len(actual_ids) != len(set(actual_ids)):
        raise ValueError("response contains duplicate groupId values")
    if set(actual_ids) != set(expected_ids):
        raise ValueError("response groupId set does not match translateGroups")
    for item in translations:
        if not str(item.get("translatedText", "")).strip():
            raise ValueError(f"empty translatedText for {item.get('groupId')}")
    return translations


def usage_dict(response: Any) -> dict[str, Any] | None:
    usage = getattr(response, "usage_metadata", None)
    return usage.model_dump(exclude_none=True) if usage else None


def run_case(
    genai: Any,
    types: Any,
    api_key: str,
    proxy: str,
    payload: dict[str, Any],
    level: str,
    expected_ids: list[str],
    timeout_ms: int,
) -> dict[str, Any]:
    schema = payload["response_format"]["json_schema"]["schema"]
    config = types.GenerateContentConfig(
        max_output_tokens=payload.get("max_tokens"),
        response_mime_type="application/json",
        response_json_schema=schema,
        thinking_config=types.ThinkingConfig(
            include_thoughts=True,
            thinking_level=level.upper(),
        ),
        http_options=types.HttpOptions(timeout=timeout_ms),
    )
    client_args = {"proxy": proxy} if proxy else None
    started = time.perf_counter()
    client = None
    try:
        client = genai.Client(
            api_key=api_key,
            http_options=types.HttpOptions(
                timeout=timeout_ms,
                client_args=client_args,
            ),
        )
        response = client.models.generate_content(
            model=payload["model"],
            contents=joined_contents(payload["messages"]),
            config=config,
        )
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        translations = validate_response(response.text or "", expected_ids)
        return {
            "level": level,
            "success": True,
            "elapsedMs": elapsed_ms,
            "usageMetadata": usage_dict(response),
            "translations": translations,
            "error": None,
        }
    except Exception as error:
        return {
            "level": level,
            "success": False,
            "elapsedMs": round((time.perf_counter() - started) * 1000),
            "usageMetadata": None,
            "translations": None,
            "error": str(error),
        }
    finally:
        if client is not None:
            client.close()


def percentile(values: list[int], fraction: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    interpolated = ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)
    return round(interpolated)


def main() -> int:
    args = parse_args()
    if not 1 <= args.rounds <= 10:
        raise SystemExit("--rounds must be between 1 and 10")
    levels = [item.strip().lower() for item in args.levels.split(",") if item.strip()]
    invalid = set(levels) - set(DEFAULT_LEVELS)
    if invalid:
        raise SystemExit(f"unsupported thinking levels: {', '.join(sorted(invalid))}")
    api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    if not api_key:
        raise SystemExit("GEMINI_API_KEY or GOOGLE_API_KEY is required")

    try:
        from google import genai
        from google.genai import types
    except ImportError as error:
        raise SystemExit("google-genai is required") from error

    payload = load_payload(args.payload)
    ids = expected_group_ids(payload["messages"])
    runs = []
    for round_index in range(args.rounds):
        order = levels[round_index % len(levels):] + levels[:round_index % len(levels)]
        for level in order:
            print(f"round={round_index + 1} level={level} coldStart=true", flush=True)
            result = run_case(
                genai,
                types,
                api_key,
                args.proxy,
                payload,
                level,
                ids,
                round(args.timeout * 1000),
            )
            result["round"] = round_index + 1
            result["coldStart"] = True
            runs.append(result)
            print(
                f"  success={result['success']} elapsedMs={result['elapsedMs']}",
                flush=True,
            )

    summary = []
    for level in levels:
        rows = [row for row in runs if row["level"] == level]
        successful = [row for row in rows if row["success"]]
        elapsed = [row["elapsedMs"] for row in successful]
        thoughts = [
            row["usageMetadata"].get("thoughts_token_count")
            for row in successful
            if row["usageMetadata"] and row["usageMetadata"].get("thoughts_token_count") is not None
        ]
        summary.append(
            {
                "level": level,
                "runs": len(rows),
                "successes": len(successful),
                "successRate": round(len(successful) / len(rows), 3),
                "medianElapsedMs": round(statistics.median(elapsed)) if elapsed else None,
                "p25ElapsedMs": percentile(elapsed, 0.25),
                "p75ElapsedMs": percentile(elapsed, 0.75),
                "minElapsedMs": min(elapsed) if elapsed else None,
                "maxElapsedMs": max(elapsed) if elapsed else None,
                "elapsedMs": elapsed,
                "medianThoughtsTokens": round(statistics.median(thoughts)) if thoughts else None,
            }
        )

    report = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "payload": str(args.payload),
        "model": payload["model"],
        "maxOutputTokens": payload.get("max_tokens"),
        "proxy": args.proxy or "direct",
        "coldStart": "new Gemini Client and HTTP connection pool for every request",
        "groupCount": len(ids),
        "summary": summary,
        "runs": runs,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
        print(f"report: {args.output.resolve()}")
    return 0 if all(row["success"] for row in runs) else 2


if __name__ == "__main__":
    sys.exit(main())
