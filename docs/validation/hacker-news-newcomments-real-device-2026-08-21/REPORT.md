# Hacker News New Comments Android real-device validation

Date: 2026-08-21 (Asia/Shanghai)

## Environment

- Device: Xiaomi `23113RKC6C`, Android 16, 1440 x 3200
- Target app: mobile Chrome (`com.android.chrome`)
- Page: `https://news.ycombinator.com/newcomments`
- APK: debug 1.0, SHA-256 `d6c2d05dd20dcb7081d0da9cf193228a7ca5617a1b98e8b9bbfecdc5ff4b7475`
- Translation: `EMBEDDED_V4`, OpenLux, `gemini-3.5-flash-lite`, thinking level `medium`
- Request archive and source/rendered capture export: enabled

## Manual flow

1. Build and install the current debug APK with application data retained.
2. Open the requested page in mobile Chrome and wait for visible comments.
3. Open ImageTranslateOCR, enable request archive export, and start the recognition overlay.
4. Wait for the first translated viewport, then perform one upward swipe.
5. Wait for the settled viewport translation and inspect the actual presented-screen capture.

Chrome initially returned `ERR_CONNECTION_RESET`; force-stopping and reopening Chrome loaded the page successfully. The failed network frame was not counted as a translation result.

## Result

The scrolled stable viewport completed successfully:

| Metric | Value |
| --- | ---: |
| Recognized regions | 42 |
| Translated patches | 10 |
| Failed regions | 0 |
| OCR | 385 ms |
| Translation | 6006 ms |
| Render | 99 ms |
| Presentation | 7 ms |
| End to end | 6698 ms |
| Source Latin tokens retained | 21 / 328 (6.40%) |

Visual review passed the original regression target: Chinese lines do not overlap, dense paragraphs do not use visibly oversized glyphs, and text remains inside each patch. The 9-line and 8-line source paragraphs are both `BODY`, each reflowed into one `RECT` render slot with `maximumTextScale=1`.

The remaining visual issue is background occupancy. Shorter Chinese translations erase the full source paragraph union, leaving large gray empty areas and faint source text under the standard translucent material. This is not text overlap, but it still makes the page look heavier than the original.

The stationary first viewport also triggered repeated automatic captures. Reported `shiftY` values jumped between large positive and negative values even without an intentional swipe, producing several extra translations and ZIP files. The overlay or completion toast is likely entering change detection and should be excluded from viewport registration.

## Timing diagnosis

Three consecutive schema-v2 archives produced end-to-end times of 5990, 6316, and 6698 ms. Their provider request-to-headers times were 5151, 5413, and 5867 ms, while response download stayed at 1 ms and rendering stayed between 84 and 99 ms.

The current 6-7 second latency is therefore provider/model wait, not OCR, response download, layout, or presentation. The selected request used 2207 prompt tokens, 3863 completion tokens, and 2742 reasoning tokens. Reducing thinking level or output/reasoning tokens is the highest-impact performance experiment.

## Evidence

- `evidence/chrome-retry.png`: loaded Chrome source viewport
- `evidence/translated-first.png`: first translated viewport
- `evidence/translated-after-scroll.png`: translated viewport after manual swipe
- `evidence/scroll-run.log`: client timing and coverage telemetry
- `evidence/20260821T032411Z_2ca04474-954d-45b7-805e-9b7e2e0ecbd7.zip`: selected schema-v2 request archive
- `evidence/selected-archive/`: extracted request, response, captures, render audit, and timings

## Verdict

- APK packaging and installation: PASS
- Mobile Chrome page acquisition: PASS after one network retry
- OCR and translation execution: PASS
- No translated-text overlap: PASS
- Multi-line BODY classification and continuous reflow: PASS
- Scroll and re-capture: PASS
- ZIP schema-v2 segmented timing: PASS
- 4-5 second performance target: FAIL; observed provider-dominated 6.0-6.7 second end-to-end latency
- Stationary viewport stability: FAIL; repeated false movement/re-capture events were observed
