#!/usr/bin/env python3
"""Validate native Gemini structured translation with a project OCR fixture.

Use Python 3.10+ and install the only runtime dependency with:
    python3 -m pip install --upgrade google-genai

The API key is read from GEMINI_API_KEY or GOOGLE_API_KEY. It is never included
in the generated report.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlparse


DEFAULT_FIXTURE = Path("demo-server/examples/v4-article-request.json")
DEFAULT_MODEL = "gemini-3.5-flash-lite"
DEFAULT_PROXY = "http://127.0.0.1:7897"
DEFAULT_PROMPT = """You are a professional screen OCR translation engine.
Translate the supplied group from English to Chinese faithfully and completely.
Use documentContext only to disambiguate meaning. Do not summarize, explain,
invent IDs, or reproduce the input document. Preserve identifiers, numbers, and
currency meaning. Return only the structured response requested by the schema."""


def uses_gemini_thinking_level(model_id: str) -> bool:
    """Match the Go behavior: Gemini 3 models use a level, older models a budget."""
    return model_id.strip().lower().startswith("gemini-3")


def translation_response_json_schema(group_id: str) -> dict[str, Any]:
    return {
        "type": "object",
        "additionalProperties": False,
        "required": ["translations"],
        "properties": {
            "translations": {
                "type": "array",
                "minItems": 1,
                "maxItems": 1,
                "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": [
                        "groupId",
                        "translatedText",
                        "detectedSourceLanguage",
                        "targetLanguage",
                    ],
                    "properties": {
                        "groupId": {"type": "string", "enum": [group_id]},
                        "translatedText": {"type": "string", "minLength": 1},
                        "detectedSourceLanguage": {"type": "string", "minLength": 2},
                        "targetLanguage": {"type": "string", "enum": ["zh"]},
                    },
                },
            }
        },
    }


def build_gemini_generate_content_config(
    model_id: str,
    budget: int,
    response_schema: Optional[dict[str, Any]] = None,
) -> Optional[dict[str, Any]]:
    """Python equivalent of buildGeminiGenerateContentConfig."""
    if budget <= 0 and response_schema is None:
        return None

    config: dict[str, Any] = {}
    if response_schema is not None:
        config["response_mime_type"] = "application/json"
        config["response_json_schema"] = response_schema
    if budget <= 0:
        return config

    thinking_config: dict[str, Any] = {"include_thoughts": True}
    if uses_gemini_thinking_level(model_id):
        thinking_config["thinking_level"] = "HIGH"
    else:
        thinking_config["thinking_budget"] = budget
    config["thinking_config"] = thinking_config
    return config


def validate_proxy_url(proxy_url: Optional[str]) -> Optional[str]:
    if not proxy_url:
        return None
    parsed = urlparse(proxy_url)
    if not parsed.scheme or not parsed.netloc:
        raise ValueError("proxy URL must be an absolute URL")
    return proxy_url


def extract_fixture_case(path: Path, group_id: Optional[str]) -> tuple[str, str, dict[str, Any]]:
    request = json.loads(path.read_text(encoding="utf-8"))
    groups = request.get("groups") or []
    if not groups:
        raise ValueError(f"fixture has no groups: {path}")
    if group_id:
        selected = next((group for group in groups if group.get("groupId") == group_id), None)
        if selected is None:
            raise ValueError(f"groupId not found in fixture: {group_id}")
    else:
        selected = next(
            (group for group in groups if group.get("role") == "BODY"), groups[0]
        )

    selected_id = selected.get("groupId")
    source_text = selected.get("sourceText")
    if not isinstance(selected_id, str) or not isinstance(source_text, str):
        raise ValueError("selected fixture group must contain string groupId and sourceText")

    payload = {
        "documentContext": request.get("documentContext", {}),
        "translateGroup": {
            "groupId": selected_id,
            "role": selected.get("role"),
            "sourceText": source_text,
        },
    }
    return selected_id, source_text, payload


def gemini_translate_by_google(
    text: str,
    prompt: str,
    model_id: str,
    budget: int,
    response_schema: dict[str, Any],
    api_key: str,
    proxy_url: Optional[str],
    timeout_seconds: float,
) -> tuple[str, Any]:
    """Python equivalent of GeminiTranslateByGoogleContext."""
    try:
        from google import genai
        from google.genai import types
    except ImportError as error:
        raise RuntimeError(
            "google-genai is required; install it with: python3 -m pip install google-genai"
        ) from error

    proxy_url = validate_proxy_url(proxy_url)
    client_args = {"proxy": proxy_url} if proxy_url else None
    http_options = types.HttpOptions(
        timeout=int(timeout_seconds * 1000),
        client_args=client_args,
    )
    client = genai.Client(api_key=api_key, http_options=http_options)
    full_prompt = f"{prompt}\nuser:\n{text}"
    config = build_gemini_generate_content_config(model_id, budget, response_schema)
    try:
        try:
            typed_config = types.GenerateContentConfig.model_validate(config)
        except Exception as error:
            if uses_gemini_thinking_level(model_id):
                raise RuntimeError(
                    "the installed google-genai does not support thinking_level; "
                    "use Python 3.10+ and upgrade google-genai"
                ) from error
            raise
        response = client.models.generate_content(
            model=model_id, contents=full_prompt, config=typed_config
        )
        return response.text or "", response
    finally:
        client.close()


def validate_translation(raw: str, expected_group_id: str) -> dict[str, Any]:
    parsed = json.loads(raw)
    translations = parsed.get("translations")
    if not isinstance(translations, list) or len(translations) != 1:
        raise ValueError("response must contain exactly one translation")
    translation = translations[0]
    if translation.get("groupId") != expected_group_id:
        raise ValueError("response groupId does not match the fixture")
    if not str(translation.get("translatedText", "")).strip():
        raise ValueError("translatedText is empty")
    if translation.get("targetLanguage") != "zh":
        raise ValueError("targetLanguage must be zh")
    return translation


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run one project OCR fixture group through the native Gemini API."
    )
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--group-id", help="Defaults to the first BODY group.")
    parser.add_argument("--model", default=os.getenv("GEMINI_MODEL", DEFAULT_MODEL))
    parser.add_argument("--budget", type=int, default=1024)
    parser.add_argument("--prompt", default=DEFAULT_PROMPT)
    parser.add_argument(
        "--proxy",
        default=os.getenv("GEMINI_PROXY_URL", DEFAULT_PROXY),
        help="Pass an empty value to connect directly.",
    )
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    proxy_url = validate_proxy_url(args.proxy)
    group_id, source_text, fixture_payload = extract_fixture_case(
        args.fixture, args.group_id
    )
    schema = translation_response_json_schema(group_id)
    config = build_gemini_generate_content_config(args.model, args.budget, schema)
    request_text = json.dumps(fixture_payload, ensure_ascii=False, separators=(",", ":"))

    print(f"fixture: {args.fixture}")
    print(f"groupId: {group_id}")
    print(f"sourceText: {source_text}")
    print(f"model: {args.model}")
    print(f"thinkingConfig: {json.dumps(config.get('thinking_config'), ensure_ascii=False)}")
    print(f"proxy: {proxy_url or '<direct>'}")
    if args.dry_run:
        print(json.dumps({"contents": f"{args.prompt}\nuser:\n{request_text}", "config": config}, ensure_ascii=False, indent=2))
        return 0

    api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    if not api_key:
        raise SystemExit("GEMINI_API_KEY or GOOGLE_API_KEY is required (or use --dry-run).")

    started = time.perf_counter()
    raw, response = gemini_translate_by_google(
        text=request_text,
        prompt=args.prompt,
        model_id=args.model,
        budget=args.budget,
        response_schema=schema,
        api_key=api_key,
        proxy_url=proxy_url,
        timeout_seconds=args.timeout,
    )
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    translation = validate_translation(raw, group_id)
    usage = getattr(response, "usage_metadata", None)
    report = {
        "fixture": str(args.fixture),
        "groupId": group_id,
        "model": args.model,
        "budget": args.budget,
        "elapsedMs": elapsed_ms,
        "sourceText": source_text,
        "translation": translation,
        "usageMetadata": usage.model_dump(exclude_none=True) if usage else None,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
        print(f"report: {args.output.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
