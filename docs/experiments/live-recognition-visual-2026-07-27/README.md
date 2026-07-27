# OCR visible-render evidence

These artifacts validate the final debug compositor with the same overlay alpha used by the
production translation window.

- `run-3/` is a differential sample whose source-region and patch-area recalls both exceed 90%.
- `final-differential/` is a deliberately retained failure sample. Its report records 75.57%
  source-area recall and 77.84% patch-area recall, so the A/B decision is
  `ACCURACY_REGRESSION` even though real patches were visibly rendered.
- `candidate-preview.png` and `reference-preview.png` contain the current screenshot plus the
  actual candidate/reference `ScreenTranslationPatch` bitmaps.
- `candidate-displayed.png` records the preview shown on the device.

`visual_render_pass=true` proves that patch pixels were rendered without changing pixels outside
their bounds. It does not prove OCR or translation correctness. The current reports therefore
keep `semantic_quality_evaluated=false`; semantic acceptance requires a future golden-text corpus.
