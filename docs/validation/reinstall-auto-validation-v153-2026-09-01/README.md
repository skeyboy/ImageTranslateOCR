# Reinstall and automatic layout validation - v153

## Result

The APK was rebuilt with the embedded Rust planner, reinstalled on device
`192.168.0.46:43139`, and validated against the Xinhua article and Hacker News
new comments page. All five final live runs passed.

The layout oracle checks both sides of the contract:

1. The expected DOM or fixed paragraph must resolve to one server group with one
   `RECT` render slot.
2. The source/rendered screenshot difference inside that theoretical rectangle
   must form one vertical band and one horizontal band.
3. Split DOM blocks, foreign members, missing patch coverage, internal gaps,
   translation failures, and render failures fail or block the run.

The fixed Xinhua expectations used by the runner are stored at
`scripts/layout-expectations/xinhua-paragraphs.json`.

## Root causes and fixes

- A four-line Hacker News paragraph had heights `42/39/32/32`. The streaming
  planner compared the accumulated `42` maximum against the third line even
  though the `39 -> 32` boundary was locally compatible. It split one client
  BODY advisory into two server groups. The planner now recognizes a sustained
  typography transition only when both sides contain at least two stable lines,
  the OCR block and client advisory are the same, line order and spacing are
  continuous, the previous text is unfinished, and the relaxed range stays at
  or above `0.65`. A single drifting tail remains protected by the existing
  typography-boundary regression.
- A `235 x 51 px` one-line BODY rectangle could not fit `Sure,buddy.` translated
  as `Of course, buddy.` in Chinese at the generic `0.82` retry limit. A compact
  single-line RECT fallback now permits a `0.60` source-line-height floor for at
  most 12 translated non-space characters. Multi-line paragraph typography and
  global merge thresholds are unchanged.
- The validator now emits explicit theoretical group/member/slot metrics,
  horizontal and vertical rendered bands, `actualMerged`, and
  `mergeConsistent`. A theoretical/actual mismatch has its own failing code.

## Live samples

| Page | Runs | Consistent multi-line blocks | Rendered patches | Failures | End-to-end ms |
| --- | ---: | ---: | ---: | ---: | --- |
| Xinhua | 2/2 PASS | 8/8 | 8 | 0 | 3199, 3212 |
| Hacker News | 3/3 PASS | 16/16 | 44 | 0 | 5493, 5576, 5776 |
| Total | 5/5 PASS | 24/24 | 52 | 0 | median 5493 |

Every validated block had zero internal vertical and horizontal gap. The minimum
source-to-rendered changed-pixel coverage was `96.82%`. The fixed Xinhua blood
test paragraph resolved to five OCR members, one group, one RECT, and one real
rendered band in both runs.

The preceding v152 Hacker News sample remains useful negative evidence: the
oracle detected one DOM paragraph split into two server groups before the Rust
planner fix. The earlier v138 Xinhua archive also remains a fixed negative case
for the original singleton-tail defect.

## Verification

- Rust core: 54 tests passed.
- Android debug unit tests: passed.
- Android instrumentation regression for the compact one-line rectangle: passed.
- Python theory-to-rendered oracle: 2 tests passed, including a deliberately
  fragmented synthetic rectangle.
- Python compilation, shell syntax, and changed-file whitespace checks: passed.

Final APK SHA-256:
`1f2cfdf1153c14d0f04f5b0bf31213414b839e8070febc3d6ba5aaff99d1a7b3`.
