# Provider Prompt Compression V5 - English Chrome Validation

Date: 2026-08-26

## Scope

- Only `SUCCEEDED` historical records were used for the compression baseline.
- Real-device validation covers English pages in Chrome only, using Hacker News comments.
- Device: Xiaomi `23113RKC6C`, ADB serial `192.168.0.46:43439`.
- Provider/model: OpenLux / `gemini-3.5-flash-lite`.
- Baseline Git commit: `3a73948ad17750994ed046613a2ae5342c37811e`.
- Work branch: `codex/provider-prompt-compression`.

## Implemented Protocol

- Domain structs retain semantic Rust field names.
- The compact Provider DTO emits `mode`, `groups`, and group keys
  `id/text/src/dst/role/box/scale/type/keep`.
- Canonical group IDs are mapped to request-local aliases (`g0`, `g1`, ...).
- Bounds use integer coordinates in the `0..1000` range and scale uses a fixed
  integer percentage, avoiding floating-point serialization tails.
- The repeated `documentOutline`, task text, scene, reading order, previews,
  empty `keep` arrays, and full canonical IDs are omitted from compact prompts.
- The response schema requests only `id` and translated `text`.
- The decoder restores the canonical group ID and input source/target languages,
  rejects unknown/duplicate/missing aliases, and keeps legacy responses readable.
- `compactProviderPrompt=false` preserves the full geometry prompt and legacy
  response schema as a rollback path.

## Verification

- `cargo test -p ocr-translation-core`: 48 passed.
- `cargo test -p image-translate-demo-server`: 49 passed across unit/API suites.
- `./gradlew testDebugUnitTest`: passed.
- `./gradlew assembleDebug`: passed, including the Android Rust JNI build.
- APK: `app/build/outputs/apk/debug/ImageTranslateOCR-20260826-v1.0.132-debug.apk`.
- APK SHA-256: `8f2521a6c68dc71aea1d34e09c92b630b876f7f492699e398d2b96c84dd707b7`.
- APK installed with `adb install -r`; application data and Provider settings were retained.

## Real-Device Results

Eight completed English Chrome audits were inspected. Every Provider request:

- omitted `documentOutline`;
- omitted canonical `server-v4-*` IDs;
- used compact integer geometry;
- returned an `id/text` response that decoded successfully.

| Metric | Historical successful baseline | V5 English device median | Change |
|---|---:|---:|---:|
| Prompt tokens | 3606 | 1632 | -54.7% |
| Visible completion tokens | 1538 | 838 | -45.5% |
| Total tokens | 7977 | 3360 | -57.9% |
| Provider latency | 6922 ms | 5885 ms | -15.0% observed |

The latency comparison is observational rather than a same-screen controlled
A/B. Two Provider requests had upstream long tails of 26-33 seconds; compact
serialization does not eliminate model/network variance.

Manual English scroll completions captured after installation were 6786 ms,
4871 ms, 6841 ms, and 5279 ms. No crash, response parse failure, unknown alias,
duplicate alias, or missing alias occurred.

## Accuracy Guard

Two groups across the eight requests triggered the existing
`IDENTIFIER_PRESERVATION_FAILED` guard because the model translated `US`/`UK`
instead of copying the required literal. These groups safely kept the source
content rather than pasting an invalid translation. This is a residual model
literal-following issue, not an ID reconstruction failure. The compact system
prompt now explicitly states that every `keep` value must appear verbatim in
the same group's translated text.

## Evidence

- `evidence/english-after-wait.png`: initial English page translation.
- `evidence/english-after-scrolls.png`: translated page after repeated manual-style swipes.
- `evidence/english-final-build-after-scroll.png`: final APK after the strengthened
  literal instruction and a manual-style swipe.
- `evidence/english-screen-capture.log`: three earlier scroll completions.
- `evidence/english-final-build.log`: final-build scroll completion.

## Rollback

1. Runtime prompt rollback: submit the request with `compactProviderPrompt=false`.
2. Code rollback: switch back to commit
   `3a73948ad17750994ed046613a2ae5342c37811e` or revert the V5 commit.
3. Device rollback: reinstall a prior signed APK from `dist/` with `adb install -r`.
