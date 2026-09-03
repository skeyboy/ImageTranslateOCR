# OCR translation layout validation, Android v166

## Result

All six production-device samples passed with `thinkingLevel=medium`.

- 6/6 samples passed.
- 25/25 DOM or fixed-expectation paragraph blocks matched the actual rendered merge shape.
- 61 patches rendered, with 0 translation failures and 0 render failures.
- Hacker News `newcomments` was sampled twice against changing live content.
- The original failure, where the final paragraph line became a separate smaller patch, was not reproduced.

## Root cause

The failure was not a single font-size problem. It was a decision mismatch across three layers:

1. OCR could assign a shorter final line a smaller element height or a different block/group boundary.
2. Client grouping and Rust planning treated typography confidence too strongly, even when line order, alignment, spacing and paragraph punctuation indicated continuation.
3. Rendering then used the isolated line box as its typography anchor, making the semantic split visually obvious as a smaller final line.

The earlier automated HN failure was a separate validator false positive: Accessibility exposed the discussion subject and comment as adjacent text. The oracle joined them and reported a mismatch even though the app output was correct. Compact non-sentence subjects are now recognized as boundaries.

## Implemented corrections

- Client and Rust grouping accept relaxed typography only for strongly constrained continuation cases: same OCR block, consecutive lines, compatible body role, continuous spacing/alignment, or a compact cross-block tail with paragraph evidence.
- Rust planning performs singleton-tail recovery and can collapse confirmed paragraph continuations into one `RECT` even when client advisory groups differ.
- Explicit controls/identifiers are preserved, while accidental role drift no longer blocks body continuation.
- Rendering uses a stable paragraph-level geometry/typography fallback for compact `RECT`, title and list-item cases.
- Accessibility supplies the visible Chrome content bounds so the capture toolbar is excluded from OCR.
- Low-confidence icon-like and large image-artifact OCR is filtered before translation.
- HTTP 429 is retried once at the provider layer; an upstream translation failure no longer repeats the full OCR pass.
- Compact provider responses accept both `id` and legacy `groupId`, fixing cache decoding.

## Automated validation contract

For every run the harness now records and compares:

1. Chrome Accessibility DOM or an explicit fixed paragraph expectation.
2. OCR regions and the client's first grouping result.
3. Rust `documentPlan`, group members, layout shape and render-slot count.
4. Source and translated screenshots, including changed-pixel connectivity and row/column bands.
5. Translation/render failures and per-stage timings.

The run fails with `THEORETICAL_ACTUAL_MERGE_MISMATCH` when a theoretically merged paragraph appears as multiple disconnected rendered patches. Incomplete provider archives are classified separately, including `REMOTE_PROVIDER_RATE_LIMIT`, rather than being mistaken for layout failures.

## Production-device samples

| Page | Result | Expected/actual blocks | Patches | End-to-end | OCR | Remote AI | Report |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| Xinhua fixed article | PASS | 4/4 | 4 | 4117 ms | 625 ms | 3091 ms | `xinhua/run-1-dom/layout-validation.json` |
| Hacker News run 1 | PASS | 6/6 | 11 | 7828 ms | 1475 ms | 5790 ms | `hacker-news/run-1-dom/layout-validation.json` |
| Hacker News run 2 | PASS | 8/8 | 20 | 7101 ms | 1255 ms | 5252 ms | `hacker-news/run-2-dom/layout-validation.json` |
| MDN JavaScript Guide | PASS | 2/2 | 15 | 4423 ms | 978 ms | 2945 ms | `mdn/run-1-dom/layout-validation.json` |
| Rust Book | PASS | 3/3 | 7 | 5283 ms | 1070 ms | 3819 ms | `rust-book/run-1-dom/layout-validation.json` |
| Wikipedia OCR | PASS | 2/2 | 4 | 6271 ms | 1285 ms | 4553 ms | `wikipedia/run-1-dom-retry/layout-validation.json` |

All reports record `thinkingControlMode=THINKING_LEVEL` and `thinkingLevel=medium`.

## Latency analysis

Across the six final samples:

- End-to-end latency was 4117-7828 ms, average 5837 ms.
- Remote AI averaged 4242 ms and 72.4% of total time. This is the primary bottleneck.
- OCR averaged 1115 ms and 19.1%. This is the second bottleneck.
- Local prompt/planning, response assembly, rendering and presentation are individually small compared with remote inference.

Recommended order, while keeping `thinkingLevel=medium`:

1. P0: cache translations at the planned semantic-group layer, then rebuild the current `documentPlan` and layout hints. Do not reuse stale client-only layout results.
2. P0: retain provider HTTP connections and the bounded 429 translation-only retry already implemented.
3. P1: keep the OCR recognizer warm and reuse unchanged screen regions; measure cropped/differential OCR before lowering capture resolution.
4. P1: if progressive presentation is added, emit only complete paragraph units so latency work cannot reintroduce split-tail layout defects.
5. P2: do not prioritize local prompt serialization or archive JPEG encoding; current measurements show they are not material bottlenecks.

## Regression checks

- Android JVM unit tests: PASS with Java 17.
- Rust core tests: 56 PASS.
- Layout validator tests: 8 PASS.
- Real-device renderer instrumentation: 3 PASS.
- Shell syntax and `git diff --check`: PASS.

APK under test: `dist/ImageTranslateOCR-pipeline-validation-20260903-v166-debug.apk`.
