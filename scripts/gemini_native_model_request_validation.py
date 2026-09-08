#!/usr/bin/env python3
"""Run one saved OpenAI-style translation request through native Gemini once."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("request", type=Path)
    parser.add_argument("--proxy", default=os.getenv("GEMINI_PROXY_URL", ""))
    parser.add_argument("--thinking-level", default="HIGH")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def load_case(path: Path) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    source = json.loads(path.read_text(encoding="utf-8"))
    messages = source.get("messages") or []
    system = next(item["content"] for item in messages if item.get("role") == "system")
    user = next(item["content"] for item in messages if item.get("role") == "user")
    user_payload = json.loads(user)
    groups = user_payload.get("translateGroups") or []
    schema = source["response_format"]["json_schema"]["schema"]
    body = {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": user}]}],
        "generationConfig": {
            "maxOutputTokens": source["max_tokens"],
            "responseMimeType": "application/json",
            "responseJsonSchema": schema,
        },
    }
    return source, body, groups


def validate(raw: str, groups: list[dict[str, Any]]) -> dict[str, Any]:
    result = json.loads(raw)
    translations = result.get("translations")
    if not isinstance(translations, list):
        raise ValueError("translations must be an array")
    expected = {group["groupId"]: group for group in groups}
    actual = {item.get("groupId"): item for item in translations}
    if len(actual) != len(translations) or set(actual) != set(expected):
        raise ValueError("response group IDs do not exactly match the request")
    for group_id, item in actual.items():
        for field in ("translatedText", "detectedSourceLanguage", "targetLanguage"):
            if not isinstance(item.get(field), str) or not item[field].strip():
                raise ValueError(f"{group_id} has invalid {field}")
        for literal in expected[group_id].get("requiredLiteralIdentifiers", []):
            if literal not in item["translatedText"]:
                raise ValueError(f"{group_id} lost required literal {literal}")
    return result


def main() -> int:
    args = parse_args()
    source, body, groups = load_case(args.request)
    api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    if not api_key:
        raise SystemExit("GEMINI_API_KEY or GOOGLE_API_KEY is required")
    if source["model"].lower().startswith("gemini-3"):
        body["generationConfig"]["thinkingConfig"] = {
            "includeThoughts": True,
            "thinkingLevel": args.thinking_level.upper(),
        }
    try:
        import httpx
    except ImportError as error:
        raise SystemExit("httpx is required") from error
    base_url = os.getenv(
        "GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta"
    ).rstrip("/")
    endpoint = f"{base_url}/models/{source['model']}:generateContent"
    started = time.perf_counter()
    with httpx.Client(proxy=args.proxy or None, timeout=args.timeout) as client:
        response = client.post(
            endpoint,
            headers={"x-goog-api-key": api_key, "Content-Type": "application/json"},
            json=body,
        )
        response.raise_for_status()
        envelope = response.json()
    parts = envelope.get("candidates", [{}])[0].get("content", {}).get("parts", [])
    answer = "".join(
        part.get("text", "") for part in parts if part.get("thought") is not True
    )
    result = validate(answer, groups)
    report = {
        "model": source["model"],
        "singleCall": True,
        "elapsedMs": round((time.perf_counter() - started) * 1000),
        "expectedGroupCount": len(groups),
        "translationCount": len(result["translations"]),
        "translations": result["translations"],
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
