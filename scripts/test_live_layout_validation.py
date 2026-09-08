#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

from scripts.live_layout_validation import (
    Bounds,
    accessibility_nodes,
    accessibility_fragments_continue,
    add_pipeline_failure_finding,
    expected_blocks_from_config,
    incomplete_archive_report,
    looks_like_accessibility_title,
    performance_analysis,
    split_boundary_diagnostics,
    validate_visuals,
)


class LiveLayoutValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary_directory.name)
        self.source = np.full((100, 100, 3), 255, dtype=np.uint8)
        self.group = {
            "groupId": "group-1",
            "layoutShape": "RECT",
            "memberRegionIds": ["line-1", "line-2"],
            "renderSlots": [{"left": 10, "top": 10, "right": 90, "bottom": 90}],
        }
        self.block = {
            "id": "paragraph",
            "source": "TEST",
            "bounds": Bounds(10, 10, 90, 90),
            "expectedShape": "RECT",
            "groupIds": ["group-1"],
        }

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def validate(self, translated: np.ndarray):
        source_path = self.directory / "source.png"
        translated_path = self.directory / "translated.png"
        Image.fromarray(self.source).save(source_path)
        Image.fromarray(translated).save(translated_path)
        findings = []
        results = validate_visuals(
            [self.block],
            {"group-1": self.group},
            source_path,
            translated_path,
            {"width": 100, "height": 100},
            findings,
        )
        return results[0], findings

    def test_single_rendered_rect_matches_theoretical_merge(self) -> None:
        translated = self.source.copy()
        translated[10:90, 10:90] = 180

        result, findings = self.validate(translated)

        self.assertTrue(result["theoretical"]["merged"])
        self.assertTrue(result["actualMerged"])
        self.assertTrue(result["mergeConsistent"])
        self.assertEqual([], findings)

    def test_fixed_expectation_requires_translation_role_and_render_mode(self) -> None:
        group = {
            "groupId": "title-group",
            "sourceText": "Al for Humanity Com...",
            "role": "CONTROL",
            "layoutShape": "RECT",
            "memberRegionIds": ["title-region"],
            "bounds": {"left": 10, "top": 10, "right": 90, "bottom": 30},
        }
        expectation = {
            "expectedBlocks": [
                {
                    "id": "chat-title",
                    "sourceContains": ["Humanity"],
                    "layoutShape": "RECT",
                    "role": "TITLE",
                    "status": "TRANSLATED",
                    "renderMode": "GROUP",
                }
            ]
        }
        findings = []

        blocks = expected_blocks_from_config(
            expectation,
            [group],
            {
                "title-group": {
                    "groupId": "title-group",
                    "status": "PRESERVED",
                    "renderMode": "NONE",
                }
            },
            findings,
        )

        self.assertEqual(["title-group"], blocks[0]["groupIds"])
        self.assertEqual(
            {
                "EXPECTED_ROLE_MISMATCH",
                "EXPECTED_TRANSLATION_STATUS_MISMATCH",
                "EXPECTED_RENDER_MODE_MISMATCH",
            },
            {finding["code"] for finding in findings},
        )

    def test_fragmented_rendered_rect_fails_merge_consistency(self) -> None:
        translated = self.source.copy()
        translated[10:40, 10:90] = 180
        translated[60:90, 10:90] = 180

        result, findings = self.validate(translated)

        self.assertTrue(result["theoretical"]["merged"])
        self.assertFalse(result["actualMerged"])
        self.assertFalse(result["mergeConsistent"])
        codes = {finding["code"] for finding in findings}
        self.assertIn("THEORETICAL_ACTUAL_MERGE_MISMATCH", codes)
        self.assertIn("RECT_VISUALLY_FRAGMENTED", codes)
        mismatch = next(
            finding
            for finding in findings
            if finding["code"] == "THEORETICAL_ACTUAL_MERGE_MISMATCH"
        )
        self.assertEqual("RENDERER", mismatch["analysis"]["owner"])

    def test_performance_analysis_identifies_remote_provider_bottleneck(self) -> None:
        (self.directory / "timings.json").write_text(
            json.dumps(
                {
                    "endToEndMs": 4000,
                    "ocrMs": 800,
                    "translationMs": 2900,
                    "prepareMs": 20,
                    "providerTotalMs": 2600,
                    "rustCompleteMs": 10,
                    "renderMs": 250,
                    "presentationMs": 10,
                    "thinkingLevel": "medium",
                }
            ),
            encoding="utf-8",
        )

        result = performance_analysis(self.directory, {})

        self.assertEqual("remoteAi", result["bottleneck"]["name"])
        self.assertEqual(65.0, result["bottleneck"]["percentOfEndToEnd"])
        self.assertEqual("remoteAi", result["recommendations"][0]["stage"])

    def test_split_boundary_attributes_same_client_group_to_server(self) -> None:
        regions = [
            {
                "regionId": "line-0",
                "groupId": "client-paragraph",
                "blockId": "ocr-block",
                "lineIndex": 0,
                "readingOrder": 0,
                "text": "A paragraph continues",
                "estimatedTextHeightPx": 42,
                "bounds": {"left": 10, "top": 10, "right": 90, "bottom": 30},
            },
            {
                "regionId": "line-1",
                "groupId": "client-paragraph",
                "blockId": "ocr-block",
                "lineIndex": 1,
                "readingOrder": 1,
                "text": "on the next line.",
                "estimatedTextHeightPx": 36,
                "bounds": {"left": 11, "top": 36, "right": 85, "bottom": 56},
            },
        ]
        member_to_group = {
            "line-0": {"groupId": "server-0"},
            "line-1": {"groupId": "server-1"},
        }

        boundaries = split_boundary_diagnostics(regions, member_to_group)
        self.assertEqual(1, len(boundaries))
        boundary = boundaries[0]

        self.assertTrue(boundary["sameBlock"])
        self.assertTrue(boundary["sameClientGroup"])
        self.assertTrue(boundary["consecutiveLineIndex"])
        self.assertEqual("SERVER_PLANNER", boundary["likelyRejectLayer"])

    def test_incomplete_rate_limited_archive_recommends_translation_only_retry(self) -> None:
        (self.directory / "manifest.json").write_text(
            json.dumps({"status": "FAILED", "provider": "openlux", "model": "gemini"}),
            encoding="utf-8",
        )
        (self.directory / "error.json").write_text(
            json.dumps({"message": "AI provider returned HTTP 429", "retryable": True}),
            encoding="utf-8",
        )

        report = incomplete_archive_report(self.directory, {"requestId": "request-1"})

        self.assertIsNotNone(report)
        finding = report["findings"][0]
        self.assertEqual("REMOTE_PROVIDER_RATE_LIMIT", finding["code"])
        self.assertIn("do not rerun OCR", finding["analysis"]["suggestedFix"])

    def test_render_failure_is_a_deterministic_error(self) -> None:
        findings = []

        add_pipeline_failure_finding(
            {
                "translationFailedCount": 0,
                "renderFailedCount": 1,
                "renderFailures": [{"reason": "TEXT_DOES_NOT_FIT"}],
            },
            findings,
        )

        self.assertEqual(1, len(findings))
        self.assertEqual("PIPELINE_FAILURE", findings[0]["code"])
        self.assertEqual("ERROR", findings[0]["severity"])

    def test_accessibility_inline_fragments_are_reconstructed_before_mapping(self) -> None:
        xml_path = self.directory / "ui.xml"
        xml_path.write_text(
            """<hierarchy><node class="android.webkit.WebView" bounds="[0,0][100,200]">
            <node class="android.widget.TextView" text="Paragraph starts" bounds="[10,10][90,40]">
              <node class="android.widget.TextView" text="inline link" bounds="[40,10][70,40]" />
            </node>
            <node class="android.widget.TextView" text="and continues" bounds="[10,30][90,60]" />
            <node class="android.widget.TextView" text="onto the next line" bounds="[10,65][90,85]" />
            <node class="android.widget.TextView" text="A separate paragraph." bounds="[10,130][90,160]" />
            </node></hierarchy>""",
            encoding="utf-8",
        )

        nodes = accessibility_nodes(xml_path, minimum_characters=5)

        self.assertEqual(2, len(nodes))
        self.assertEqual(3, nodes[0]["fragmentCount"])
        self.assertNotIn("inline link", nodes[0]["text"])
        self.assertEqual(Bounds(10, 10, 90, 85), nodes[0]["bounds"])

    def test_accessibility_url_line_is_attached_to_its_paragraph(self) -> None:
        xml_path = self.directory / "url-ui.xml"
        xml_path.write_text(
            """<hierarchy><node class="android.webkit.WebView" bounds="[0,0][200,240]">
            <node class="android.widget.TextView" text="Reserve yours here:" bounds="[10,10][190,60]" />
            <node class="android.view.View" bounds="[10,65][190,105]">
              <node class="android.widget.TextView" text="https://example.com/event" bounds="[10,65][190,105]" />
            </node>
            <node class="android.widget.TextView" text="A separate paragraph." bounds="[10,150][190,190]" />
            </node></hierarchy>""",
            encoding="utf-8",
        )

        nodes = accessibility_nodes(xml_path, minimum_characters=5)

        self.assertEqual(2, len(nodes))
        self.assertIn("https://example.com/event", nodes[0]["text"])
        self.assertEqual(2, nodes[0]["fragmentCount"])

    def test_accessibility_list_markers_keep_adjacent_items_separate(self) -> None:
        xml_path = self.directory / "list-ui.xml"
        xml_path.write_text(
            """<hierarchy><node class="android.webkit.WebView" bounds="[0,0][200,200]">
            <node class="android.view.View" text="•" bounds="[10,10][20,50]" />
            <node class="android.widget.TextView" text="First unfinished item (" bounds="[30,10][190,50]" />
            <node class="android.view.View" text="•" bounds="[10,55][20,95]" />
            <node class="android.widget.TextView" text="Second list item" bounds="[30,55][190,95]" />
            </node></hierarchy>""",
            encoding="utf-8",
        )

        nodes = accessibility_nodes(xml_path, minimum_characters=5)

        self.assertEqual(2, len(nodes))

    def test_accessibility_title_case_subject_is_not_joined_to_comment(self) -> None:
        self.assertTrue(
            looks_like_accessibility_title("The Browser's Main Thread Is Expensive")
        )
        self.assertTrue(
            looks_like_accessibility_title("Paint.net 5.2 alpha now runs on Linux")
        )
        self.assertFalse(
            looks_like_accessibility_title(
                "Such an excellently written article with really nice visualizations!"
            )
        )
        self.assertFalse(
            accessibility_fragments_continue(
                {
                    "text": "The Raspberry Pi Interactive Timeline · 2006–2026",
                    "bounds": Bounds(10, 10, 90, 30),
                },
                {
                    "text": "> You can get a far more capable desktop.",
                    "bounds": Bounds(10, 35, 90, 65),
                },
            )
        )


if __name__ == "__main__":
    unittest.main()
