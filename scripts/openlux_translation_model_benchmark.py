#!/usr/bin/env python3
"""Crawl OpenLux's model market and benchmark short-document translation.

The benchmark deliberately distinguishes a parameter being accepted from it
being observably effective. Gemini models receive thinkingLevel through the
OpenLux Google passthrough path; GPT reasoning models receive reasoning_effort.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PRICING_URL = "https://api.openlux.ai/api/pricing_new"
BASELINE_MODEL = "gemini-3.5-flash-lite"
DEFAULT_MODELS = (
    BASELINE_MODEL,
    "gemini-3.1-flash-lite",
    "gemini-3.1-flash-lite-preview",
    "gemini-2.5-flash",
    "gemini-flash-lite-latest",
    "gemini-flash-latest",
    "gpt-5.4-nano",
    "gpt-5.4-mini",
    "claude-haiku-4-5-20251001",
    "claude-3-haiku-20240307",
    "qwen-flash",
    "qwen3.5-27b",
    "qwen3.5-35b-a3b",
    "qwen3-vl-flash",
    "deepseek-v4-flash",
    "deepseek-v3.2",
    "deepseek-v3.1",
    "grok-4-1-fast-reasoning",
    "grok-4-1-fast-non-reasoning",
    "grok-4-fast-reasoning",
)
LEVELS = {
    "thinkingLevel": ("minimal", "low", "medium", "high"),
    "reasoning_effort": ("minimal", "high"),
    "model_suffix": ("low", "medium", "high"),
    "none": ("omitted",),
}
GROUPS = (
    {
        "groupId": "title",
        "sourceText": "Reliable OCR translation for short technical documents",
        "requiredLiterals": ["OCR"],
        "qualityTerms": ["可靠", "翻译", "技术文档"],
    },
    {
        "groupId": "body-1",
        "sourceText": "Correctness comes first, but users should not wait several seconds for a simple paragraph.",
        "requiredLiterals": [],
        "qualityTerms": ["正确", "用户", "等待", "秒"],
    },
    {
        "groupId": "body-2",
        "sourceText": "Preserve product names, version numbers, and API paths exactly while keeping the Chinese natural.",
        "requiredLiterals": ["API"],
        "qualityTerms": ["产品", "版本", "路径", "自然"],
    },
    {
        "groupId": "literal",
        "sourceText": "Gemini 3.5 Flash-Lite processes 12 pages through POST /v1/chat/completions.",
        "requiredLiterals": ["Gemini 3.5 Flash-Lite", "12", "POST /v1/chat/completions"],
        "qualityTerms": ["处理", "页"],
    },
    {
        "groupId": "code",
        "sourceText": "thinkingLevel = minimal",
        "requiredLiterals": ["thinkingLevel = minimal"],
        "qualityTerms": [],
    },
)


@dataclass
class Run:
    model: str
    control: str
    level: str
    round: int
    success: bool
    http_code: int | None
    ttft_ms: int | None
    total_ms: int
    prompt_tokens: int | None
    completion_tokens: int | None
    reasoning_tokens: int | None
    quality_score: int
    json_valid: bool
    coverage_valid: bool
    literals_valid: bool
    schema_valid: bool
    terms_hit: int
    terms_total: int
    response_text: str
    error: str | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default="demo-server/.env")
    parser.add_argument(
        "--input",
        type=Path,
        help="Gemini-native request JSON; contents text must contain translateGroups JSON.",
    )
    parser.add_argument("--pricing-url", default=PRICING_URL)
    parser.add_argument("--base-url")
    parser.add_argument("--api-key")
    parser.add_argument("--proxy", help="For example http://127.0.0.1:7897")
    parser.add_argument("--models", default=",".join(DEFAULT_MODELS))
    parser.add_argument("--rounds", type=int, default=2)
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--max-tokens", type=int, help="Defaults to the input request limit, or 1200.")
    parser.add_argument(
        "--request-delay",
        type=float,
        default=2.0,
        help="Seconds to wait between model requests; requests are always sequential.",
    )
    parser.add_argument(
        "--rate-limit-cooldown",
        type=float,
        default=10.0,
        help="Seconds to wait after HTTP 429; remaining levels for that model are skipped.",
    )
    parser.add_argument("--output-dir", default="build/reports/openlux-translation-model-benchmark")
    parser.add_argument("--crawl-only", action="store_true")
    return parser.parse_args()


def load_env(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


def opener(proxy: str | None) -> urllib.request.OpenerDirector:
    if proxy:
        return urllib.request.build_opener(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    return urllib.request.build_opener()


def fetch_market(client: urllib.request.OpenerDirector, url: str, timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "ImageTranslateOCR-benchmark/1"})
    with client.open(request, timeout=timeout) as response:
        payload = json.load(response)
    if not payload.get("success") or not isinstance(payload.get("data"), list):
        raise RuntimeError(f"unexpected pricing response: {payload.get('message', 'missing data')}")
    return payload


def redact_market(market: dict[str, Any]) -> dict[str, Any]:
    """Persist only public catalog fields needed to reproduce candidate selection."""
    return {
        "success": market.get("success"),
        "vendors": market.get("vendors", []),
        "supported_endpoint": market.get("supported_endpoint", []),
        "data": market.get("data", []),
    }


def control_for(model: str) -> str:
    if model.startswith("gemini-"):
        return "thinkingLevel"
    if model.startswith("gpt-5.6-") or model == "gpt-5.5":
        return "model_suffix"
    if model.startswith(("gpt-5", "claude-", "qwen", "deepseek-")):
        return "reasoning_effort"
    if model in {"grok-4-1-fast-reasoning", "grok-4-fast-reasoning"}:
        return "reasoning_effort"
    if model == "grok-4-1-fast-non-reasoning":
        return "none"
    return "unsupported"


def control_evidence(model: str) -> str:
    if model.startswith("gemini-") or model.startswith("gpt-5"):
        return "family_known"
    if model == "grok-4-1-fast-non-reasoning":
        return "fixed_model_variant"
    if control_for(model) == "reasoning_effort":
        return "compatibility_probe"
    return "unsupported"


def shortlist(market: dict[str, Any], requested: list[str]) -> list[dict[str, Any]]:
    by_name = {row.get("model_name"): row for row in market["data"]}
    rows = []
    for model in requested:
        source = by_name.get(model)
        if not source:
            rows.append(
                {
                    "model": model,
                    "listed": False,
                    "control": control_for(model),
                    "control_evidence": control_evidence(model),
                }
            )
            continue
        endpoints = source.get("supported_endpoint_types") or []
        rows.append(
            {
                "model": model,
                "listed": True,
                "control": control_for(model),
                "control_evidence": control_evidence(model),
                "chat_completions_compatible": "openai" in endpoints,
                "description": source.get("description", ""),
                "tags": source.get("tags", ""),
                "usage_count": source.get("usage_count", 0),
                "model_ratio": source.get("model_ratio"),
                "completion_ratio": source.get("completion_ratio"),
                "supported_endpoint_types": endpoints,
            }
        )
    return rows


def default_case() -> tuple[str, dict[str, Any], list[dict[str, Any]], tuple[str, ...], int]:
    system = (
        "Translate each English group into concise, natural Simplified Chinese. "
        "Return only JSON matching {\"translations\":[{\"groupId\":string,\"translatedText\":string}]}. "
        "Keep every requiredLiterals item byte-for-byte. Do not translate the code group."
    )
    groups = [dict(group) for group in GROUPS]
    source = {
        "translateGroups": [
            {k: v for k, v in group.items() if k not in {"qualityTerms", "requiredLiterals"}}
            for group in groups
        ]
    }
    return system, source, groups, ("groupId", "translatedText"), 1200


def text_parts(value: Any) -> str:
    if not isinstance(value, dict):
        return ""
    return "\n".join(
        part["text"] for part in value.get("parts", [])
        if isinstance(part, dict) and isinstance(part.get("text"), str)
    )


def load_input_case(path: Path) -> tuple[str, dict[str, Any], list[dict[str, Any]], tuple[str, ...], int]:
    outer = json.loads(path.read_text(encoding="utf-8"))
    system = text_parts(outer.get("systemInstruction"))
    user_parts = []
    for content in outer.get("contents", []):
        if isinstance(content, dict):
            user_parts.extend(
                part["text"] for part in content.get("parts", [])
                if isinstance(part, dict) and isinstance(part.get("text"), str)
            )
    if not user_parts:
        raise ValueError("input contents must contain at least one text part")
    source = json.loads("\n".join(user_parts))
    raw_groups = source.get("translateGroups") if isinstance(source, dict) else None
    if not isinstance(raw_groups, list) or not raw_groups:
        raise ValueError("input contents text must contain a non-empty translateGroups array")
    groups = []
    for group in raw_groups:
        if not isinstance(group, dict) or not isinstance(group.get("groupId"), str):
            raise ValueError("every translateGroups entry must contain a string groupId")
        groups.append(
            {
                **group,
                "requiredLiterals": group.get("requiredLiteralIdentifiers") or [],
                "qualityTerms": [],
            }
        )
    schema = (outer.get("generationConfig") or {}).get("responseJsonSchema") or {}
    item_schema = (((schema.get("properties") or {}).get("translations") or {}).get("items") or {})
    required_fields = tuple(item_schema.get("required") or ("groupId", "translatedText"))
    max_tokens = (outer.get("generationConfig") or {}).get("maxOutputTokens")
    return system, source, groups, required_fields, max_tokens if isinstance(max_tokens, int) else 1200


def request_body(
    model: str,
    control: str,
    level: str,
    max_tokens: int,
    system: str,
    source_payload: dict[str, Any],
    groups: list[dict[str, Any]],
    required_fields: tuple[str, ...],
) -> dict[str, Any]:
    schema_instruction = (
        " Return only one strict JSON object with a translations array. "
        f"Each translation object must contain exactly these required fields: {', '.join(required_fields)}."
    )
    body: dict[str, Any] = {
        "model": f"{model}-{level}" if control == "model_suffix" else model,
        "messages": [
            {"role": "system", "content": system + schema_instruction},
            {"role": "user", "content": json.dumps(source_payload, ensure_ascii=False)},
        ],
        "temperature": 0,
        "max_tokens": max_tokens,
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    if control == "thinkingLevel":
        body["google"] = {"thinking_config": {"thinking_level": level}}
    elif control == "reasoning_effort":
        body["reasoning_effort"] = level
    return body


def validate(
    text: str,
    groups: list[dict[str, Any]],
    required_fields: tuple[str, ...],
) -> dict[str, Any]:
    parsed: Any = None
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        pass
    translations = parsed.get("translations") if isinstance(parsed, dict) else None
    translations = translations if isinstance(translations, list) else []
    actual = {
        item.get("groupId"): item.get("translatedText", "")
        for item in translations
        if isinstance(item, dict) and isinstance(item.get("groupId"), str)
    }
    expected_ids = {group["groupId"] for group in groups}
    coverage = set(actual) == expected_ids and len(translations) == len(groups)
    literals = all(
        literal in actual.get(group["groupId"], "")
        for group in groups
        for literal in group["requiredLiterals"]
    )
    term_hits = sum(term in actual.get(group["groupId"], "") for group in groups for term in group["qualityTerms"])
    term_total = sum(len(group["qualityTerms"]) for group in groups)
    json_valid = isinstance(parsed, dict) and isinstance(parsed.get("translations"), list)
    required_field_set = set(required_fields)
    schema_valid = bool(translations) and all(
        set(item) == required_field_set
        and all(field in item and isinstance(item.get(field), str) and bool(item[field].strip()) for field in required_fields)
        for item in translations
        if isinstance(item, dict)
    ) and len(translations) == sum(isinstance(item, dict) for item in translations)
    score = (20 if json_valid else 0) + (25 if coverage else 0) + (20 if literals else 0) + (25 if schema_valid else 0)
    score += round(10 * term_hits / term_total) if term_total else 10
    return {
        "quality_score": score,
        "json_valid": json_valid,
        "coverage_valid": coverage,
        "literals_valid": literals,
        "schema_valid": schema_valid,
        "terms_hit": term_hits,
        "terms_total": term_total,
    }


def int_or_none(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def run_once(
    client: urllib.request.OpenerDirector,
    endpoint: str,
    api_key: str,
    model: str,
    control: str,
    level: str,
    round_number: int,
    timeout: float,
    max_tokens: int,
    system: str,
    source_payload: dict[str, Any],
    groups: list[dict[str, Any]],
    required_fields: tuple[str, ...],
) -> Run:
    body = request_body(model, control, level, max_tokens, system, source_payload, groups, required_fields)
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(body, ensure_ascii=False).encode(),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json", "Accept": "text/event-stream"},
        method="POST",
    )
    started = time.perf_counter()
    first_text: float | None = None
    parts: list[str] = []
    usage: dict[str, Any] = {}
    status: int | None = None
    error_text: str | None = None
    try:
        with client.open(request, timeout=timeout) as response:
            status = response.status
            for raw in response:
                line = raw.decode("utf-8", errors="replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if not data or data == "[DONE]":
                    continue
                chunk = json.loads(data)
                if isinstance(chunk.get("usage"), dict):
                    usage = chunk["usage"]
                choices = chunk.get("choices") or []
                if choices:
                    content = (choices[0].get("delta") or {}).get("content")
                    if isinstance(content, str) and content:
                        first_text = first_text or time.perf_counter()
                        parts.append(content)
    except urllib.error.HTTPError as error:
        status = error.code
        error_text = error.read().decode("utf-8", errors="replace")[:1000]
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        error_text = str(error)
    completed = time.perf_counter()
    text = "".join(parts)
    checks = validate(text, groups, required_fields)
    details = usage.get("completion_tokens_details") or {}
    return Run(
        model=model,
        control=control,
        level=level,
        round=round_number,
        success=(
            status == 200
            and checks["json_valid"]
            and checks["coverage_valid"]
            and checks["literals_valid"]
            and checks["schema_valid"]
        ),
        http_code=status,
        ttft_ms=round((first_text - started) * 1000) if first_text else None,
        total_ms=round((completed - started) * 1000),
        prompt_tokens=int_or_none(usage.get("prompt_tokens")),
        completion_tokens=int_or_none(usage.get("completion_tokens")),
        reasoning_tokens=int_or_none(details.get("reasoning_tokens")),
        response_text=text,
        error=error_text,
        **checks,
    )


def median(rows: list[Run], field: str) -> int | None:
    values = [getattr(row, field) for row in rows if getattr(row, field) is not None]
    return round(statistics.median(values)) if values else None


def summarize(runs: list[Run]) -> list[dict[str, Any]]:
    output = []
    for model in dict.fromkeys(run.model for run in runs):
        selected = [run for run in runs if run.model == model]
        levels: dict[str, Any] = {}
        for level in dict.fromkeys(run.level for run in selected):
            level_rows = [run for run in selected if run.level == level]
            levels[level] = {
                "successes": sum(run.success for run in level_rows),
                "runs": len(level_rows),
                "json_valid_runs": sum(run.json_valid for run in level_rows),
                "schema_valid_runs": sum(run.schema_valid for run in level_rows),
                "coverage_valid_runs": sum(run.coverage_valid for run in level_rows),
                "literals_valid_runs": sum(run.literals_valid for run in level_rows),
                "ttft_ms_median": median(level_rows, "ttft_ms"),
                "total_ms_median": median(level_rows, "total_ms"),
                "quality_score_median": median(level_rows, "quality_score"),
                "reasoning_tokens_median": median(level_rows, "reasoning_tokens"),
            }
        configured_levels = LEVELS.get(selected[0].control, ())
        low, high = (configured_levels[0], configured_levels[-1]) if len(configured_levels) > 1 else ("omitted", "omitted")
        low_total = levels.get(low, {}).get("total_ms_median")
        high_total = levels.get(high, {}).get("total_ms_median")
        low_reasoning = levels.get(low, {}).get("reasoning_tokens_median") or 0
        high_reasoning = levels.get(high, {}).get("reasoning_tokens_median") or 0
        observable = selected[0].control != "none" and bool(
            low_total
            and high_total
            and levels.get(low, {}).get("successes", 0) > 0
            and levels.get(high, {}).get("successes", 0) > 0
            and (high_total >= low_total * 1.15 or high_reasoning >= low_reasoning + 32)
        )
        output.append(
            {
                "model": model,
                "control": selected[0].control,
                "successes": sum(run.success for run in selected),
                "runs": len(selected),
                "ttft_ms_median": median(selected, "ttft_ms"),
                "total_ms_median": median(selected, "total_ms"),
                "quality_score_median": median(selected, "quality_score"),
                "parameter_accepted": all(run.http_code == 200 for run in selected),
                "level_effect_observable": observable,
                "preferred_level": low,
                "preferred_successes": levels.get(low, {}).get("successes", 0),
                "preferred_runs": levels.get(low, {}).get("runs", 0),
                "preferred_ttft_ms_median": levels.get(low, {}).get("ttft_ms_median"),
                "preferred_total_ms_median": levels.get(low, {}).get("total_ms_median"),
                "preferred_quality_score_median": levels.get(low, {}).get("quality_score_median"),
                "levels": levels,
            }
        )
    return output


def render_markdown(
    metadata: dict[str, Any],
    summary: list[dict[str, Any]],
    market: dict[str, Any],
) -> str:
    baseline = next((row for row in summary if row["model"] == BASELINE_MODEL), None)
    baseline_ms = baseline.get("preferred_total_ms_median") if baseline else None
    baseline_quality = baseline.get("preferred_quality_score_median") if baseline else None
    lines = [
        "# OpenLux 前 10 页模型短文档 JSON 翻译验证",
        "",
        f"- 生成时间：{metadata['created_at']}",
        f"- 市场模型总数：{metadata['market_model_count']}",
        f"- 参照模型：`{BASELINE_MODEL}`",
        "- 参数被接口接受不代表推理等级实际生效；需要严格 JSON 成功及耗时或 reasoning token 差异共同证明。",
        "",
        f"- 输入附件：`{metadata['input_file']}`",
        f"- 输入分组：{metadata['input_group_count']}；每条译文必需字段：{', '.join(metadata['required_response_fields'])}",
        f"- 访问限制：单线程串行，每次请求间隔 {metadata['request_delay_seconds']} 秒，429 后冷却 {metadata['rate_limit_cooldown_seconds']} 秒",
        "",
        "## 结果摘要",
        "",
        "- `gemini-3.1-flash-lite` 是本轮严格 JSON 最快的模型，thinkingLevel 四档全部达到 100 分；但四档延迟不单调，等级效果未证实。",
        "- `gpt-5.4-nano` 是推理强度可调性最清晰的低延迟候选：minimal 保持严格 JSON，high 会显著增加耗时和 reasoning tokens。",
        "- `qwen3.5-35b-a3b` 的 high 档推理最重，适合质量优先的按需通道，不适合短文档默认路径。",
        "- `gemini-3.5-flash-lite` 与 `gemini-3.6-flash` 的译文内容大多存在，但被 Markdown 围栏或分析文字包裹，不能直接转换为附件要求的 JSON。",
        "",
        "| 模型 | 推理控制 | 首选档成功 | TTFT ms | 完整响应 ms | JSON 质量 /100 | 参数接受 | 等级效果 | 相对基线 |",
        "|---|---|---:|---:|---:|---:|---|---|---:|",
    ]
    ranked = sorted(
        summary,
        key=lambda row: (
            -(row["preferred_quality_score_median"] or 0),
            row["preferred_total_ms_median"] or 10**9,
        ),
    )
    for row in ranked:
        ratio = "-"
        if baseline_ms and row["preferred_total_ms_median"]:
            ratio = f"{row['preferred_total_ms_median'] / baseline_ms:.2f}x"
        lines.append(
            f"| `{row['model']}` | {row['control']} | {row['preferred_successes']}/{row['preferred_runs']} | "
            f"{row['preferred_ttft_ms_median'] or '-'} | {row['preferred_total_ms_median'] or '-'} | "
            f"{row['preferred_quality_score_median']} | {row['parameter_accepted']} | "
            f"{row['level_effect_observable']} | {ratio} |"
        )
    suitable = [
        row for row in ranked
        if row["preferred_runs"] > 0 and row["preferred_successes"] == row["preferred_runs"]
        and baseline_ms and row["preferred_total_ms_median"] and row["preferred_total_ms_median"] <= baseline_ms * 1.5
        and baseline_quality is not None and row["preferred_quality_score_median"] >= baseline_quality - 5
        and row["parameter_accepted"]
        and row["control"] != "none"
    ]
    lines.extend(["", "## 严格可用模型", ""])
    lines.extend(f"- `{row['model']}`" for row in suitable)
    if not suitable:
        lines.append("- 没有候选同时满足严格 JSON、质量和基线相对速度门槛。")
    lines.extend(
        [
            "",
            "## 判定说明",
            "",
            "只有最低档与最高档都通过严格 JSON 验证，且最高档耗时至少高 15% 或 reasoning token 明显增加，才标记等级效果可观察。"
            "该结论只适用于本附件请求，不代表模型的通用能力保证。",
            "",
        ]
    )
    lines.extend(
        [
            "## 各推理等级结果",
            "",
            "| 模型 | 控制方式 | 等级 | 成功 | TTFT ms | 完整响应 ms | JSON 质量 | JSON 可解析 | Schema | 7 组覆盖 | 字面量 | Reasoning tokens |",
            "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for row in ranked:
        for level, metrics in row["levels"].items():
            lines.append(
                f"| `{row['model']}` | {row['control']} | {level} | "
                f"{metrics['successes']}/{metrics['runs']} | {metrics['ttft_ms_median'] or '-'} | "
                f"{metrics['total_ms_median'] or '-'} | {metrics['quality_score_median']} | "
                f"{metrics['json_valid_runs']}/{metrics['runs']} | {metrics['schema_valid_runs']}/{metrics['runs']} | "
                f"{metrics['coverage_valid_runs']}/{metrics['runs']} | {metrics['literals_valid_runs']}/{metrics['runs']} | "
                f"{metrics['reasoning_tokens_median'] if metrics['reasoning_tokens_median'] is not None else '-'} |"
            )

    first_ten_pages = market["data"][:300]
    usable = [
        (position, row)
        for position, row in enumerate(first_ten_pages, start=1)
        if row.get("model_type") in {"对话", "chat"}
        and "openai" in (row.get("supported_endpoint_types") or [])
    ]
    tested_models = {row["model"] for row in summary}
    lines.extend(
        [
            "",
            "## 前 10 页目录可调用模型",
            "",
            f"模型广场默认每页 30 条，前 10 页共 300 条目录记录，其中 {len(usable)} 条是暴露 OpenAI Chat Completions 接口的文本对话模型。",
            "目录可调用不等于已通过 JSON 翻译验证；只有状态为 `已实测` 的行在本轮实际发起了请求。",
            "",
            "| 排位 | 页码 | 模型 | 状态 | 推理控制推测 | 标签 | 市场调用量 |",
            "|---:|---:|---|---|---|---|---:|",
        ]
    )
    for position, row in usable:
        model = row.get("model_name", "")
        lines.append(
            f"| {position} | {(position - 1) // 30 + 1} | `{model}` | "
            f"{'已实测' if model in tested_models else '仅目录可调用'} | {control_for(model)} | "
            f"{str(row.get('tags') or '').replace('|', '/')} | {row.get('usage_count') or 0} |"
        )
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    if args.request_delay < 0 or args.rate_limit_cooldown < 0:
        raise SystemExit("request delays must be non-negative")
    load_env(Path(args.env_file))
    if args.input:
        system, source_payload, groups, required_fields, input_max_tokens = load_input_case(args.input)
    else:
        system, source_payload, groups, required_fields, input_max_tokens = default_case()
    max_tokens = args.max_tokens or input_max_tokens
    proxy = args.proxy or os.getenv("GEMINI_PROXY_URL") or os.getenv("HTTPS_PROXY")
    client = opener(proxy)
    market = fetch_market(client, args.pricing_url, args.timeout)
    models = [item.strip() for item in args.models.split(",") if item.strip()]
    candidates = shortlist(market, models)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output_dir = Path(args.output_dir) / stamp
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "market-snapshot.json").write_text(
        json.dumps(redact_market(market), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (output_dir / "candidates.json").write_text(json.dumps(candidates, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"crawled {len(market['data'])} models; selected {len(candidates)} candidates")
    if args.crawl_only:
        print(output_dir.resolve())
        return 0

    api_key = args.api_key or os.getenv("OPENLUX_API_KEY", "")
    if not api_key:
        raise SystemExit("OPENLUX_API_KEY is required unless --crawl-only is used")
    base_url = (args.base_url or os.getenv("OPENLUX_BASE_URL", "https://api.openlux.ai/v1")).rstrip("/")
    endpoint = base_url if base_url.endswith("/chat/completions") else base_url + "/chat/completions"
    runs: list[Run] = []
    runnable = [row for row in candidates if row.get("listed") and row.get("chat_completions_compatible") and row["control"] in LEVELS]
    for round_number in range(1, args.rounds + 1):
        ordered = runnable[round_number % len(runnable):] + runnable[:round_number % len(runnable)] if runnable else []
        for row in ordered:
            for level in LEVELS[row["control"]]:
                print(f"[{round_number}/{args.rounds}] {row['model']} {row['control']}={level}", flush=True)
                result = run_once(
                    client, endpoint, api_key, row["model"], row["control"], level,
                    round_number, args.timeout, max_tokens, system, source_payload, groups, required_fields,
                )
                runs.append(result)
                print(f"  http={result.http_code} ok={result.success} ttft={result.ttft_ms} total={result.total_ms} quality={result.quality_score}", flush=True)
                if result.http_code == 429:
                    if args.rate_limit_cooldown:
                        print(f"  rate limited; cooling down {args.rate_limit_cooldown:g}s and skipping remaining levels", flush=True)
                        time.sleep(args.rate_limit_cooldown)
                    break
                if args.request_delay:
                    time.sleep(args.request_delay)

    summary = summarize(runs)
    metadata = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "pricing_url": args.pricing_url,
        "endpoint": endpoint,
        "proxy_used": bool(proxy),
        "market_model_count": len(market["data"]),
        "rounds_per_level": args.rounds,
        "request_delay_seconds": args.request_delay,
        "rate_limit_cooldown_seconds": args.rate_limit_cooldown,
        "concurrency": 1,
        "input_file": str(args.input.resolve()) if args.input else "built-in fixture",
        "input_group_count": len(groups),
        "required_response_fields": list(required_fields),
        "max_tokens": max_tokens,
        "baseline_model": BASELINE_MODEL,
        "thinking_level_wire_path": "google.thinking_config.thinking_level",
        "reasoning_effort_wire_path": "reasoning_effort",
        "model_suffix_wire_format": "<model>-<level>",
    }
    report = {"metadata": metadata, "candidates": candidates, "summary": summary, "runs": [asdict(run) for run in runs]}
    (output_dir / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if runs:
        with (output_dir / "runs.csv").open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(asdict(runs[0])))
            writer.writeheader()
            writer.writerows(asdict(run) for run in runs)
    (output_dir / "REPORT.md").write_text(render_markdown(metadata, summary, market), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(output_dir.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
