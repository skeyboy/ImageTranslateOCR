#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

from scripts.live_layout_validation import Bounds, validate_visuals


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


if __name__ == "__main__":
    unittest.main()
