#!/usr/bin/env python3

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import openlux_v4_cold_case_benchmark as shared
import prompt_compression_ab_benchmark as benchmark


def sample_case() -> shared.Case:
    groups = [{
        "groupId": "server-v4-long-id",
        "sourceText": "Captured 276 suspects.",
        "sourceLanguage": "en",
        "targetLanguage": "zh",
        "requiredLiteralIdentifiers": ["276"],
        "role": "BODY",
        "normalizedBounds": [0.1, 0.2, 0.8, 0.3],
    }]
    return shared.Case(
        case_id="case-test",
        bucket="test",
        audit_id="audit-test",
        input_chars=22,
        group_count=1,
        source_model="test",
        source_created_at="2026-08-25T00:00:00Z",
        system="Translate every translateGroups item and return strict JSON.",
        user_payload={
            "translationMode": "AUTO_BIDIRECTIONAL",
            "documentOutline": [{"groupId": "server-v4-long-id"}],
            "translateGroups": groups,
        },
        groups=groups,
        references=[{
            "groupId": "server-v4-long-id",
            "translatedText": "抓获276名嫌疑人。",
            "detectedSourceLanguage": "en",
            "targetLanguage": "zh",
        }],
        max_tokens=1024,
    )


class PromptCompressionBenchmarkTest(unittest.TestCase):
    def test_safe_payload_removes_outline_and_keeps_full_binding(self) -> None:
        case = sample_case()
        body, _nonce = benchmark.build_payload(
            case, "compact_safe", "gemini-3.1-flash-lite", "medium"
        )
        user = json.loads(body["messages"][1]["content"])
        self.assertNotIn("documentOutline", user)
        self.assertEqual(user["translateGroups"][0]["groupId"], "server-v4-long-id")
        self.assertEqual(body["google"]["thinking_config"]["thinking_level"], "MEDIUM")

    def test_safe_response_restores_local_language_fields(self) -> None:
        raw = json.dumps({
            "translations": [{
                "groupId": "server-v4-long-id",
                "translatedText": "抓获276名嫌疑人。",
            }]
        }, ensure_ascii=False)
        canonical, _size, wire_valid = benchmark.normalize_response(
            raw, sample_case(), "compact_safe"
        )
        item = json.loads(canonical)["translations"][0]
        self.assertTrue(wire_valid)
        self.assertEqual(item["detectedSourceLanguage"], "en")
        self.assertEqual(item["targetLanguage"], "zh")

    def test_ordered_wire_rejects_extra_translations(self) -> None:
        raw = json.dumps({"t": ["抓获276名嫌疑人。", "额外内容"]}, ensure_ascii=False)
        _canonical, _size, wire_valid = benchmark.normalize_response(
            raw, sample_case(), "compact_ordered"
        )
        self.assertFalse(wire_valid)


if __name__ == "__main__":
    unittest.main()
