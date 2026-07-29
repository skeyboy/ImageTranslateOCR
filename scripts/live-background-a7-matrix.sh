#!/usr/bin/env bash
set -euo pipefail

serial="${ANDROID_SERIAL:-}"
base_url="${A7_BASE_URL:-http://127.0.0.1:8765/fixtures/a7-fixture.html}"
output_directory="${A7_OUTPUT_DIR:-docs/validation/content-viewport-background-2026-07-29/matrix}"
page_load_seconds="${A7_PAGE_LOAD_SECONDS:-1.5}"
cases_per_category="${A7_CASES_PER_CATEGORY:-10}"
package_name="com.example.imagetranslate"
page_package="${A7_PAGE_PACKAGE:-com.android.browser}"

if [[ -z "$serial" ]]; then
    echo "ANDROID_SERIAL is required" >&2
    exit 1
fi

adb_command=(adb -s "$serial")
original_rotation=$("${adb_command[@]}" shell settings get system user_rotation | tr -d '\r')
original_accelerometer=$("${adb_command[@]}" shell settings get system accelerometer_rotation | tr -d '\r')
original_night=$("${adb_command[@]}" shell cmd uimode night | tr -d '\r')
temporary_directory=$(mktemp -d /tmp/imagetranslate-a7.XXXXXX)

restore_device() {
    "${adb_command[@]}" shell settings put system user_rotation "$original_rotation" >/dev/null
    "${adb_command[@]}" shell settings put system accelerometer_rotation \
        "$original_accelerometer" >/dev/null
    if [[ "$original_night" == *"yes"* ]]; then
        "${adb_command[@]}" shell cmd uimode night yes >/dev/null
    else
        "${adb_command[@]}" shell cmd uimode night no >/dev/null
    fi
    rm -rf "$temporary_directory"
}
trap restore_device EXIT

mkdir -p "$output_directory/reports" "$output_directory/thumbs" "$output_directory/samples"
results_file="$output_directory/results.jsonl"
: > "$results_file"

run_index=0
run_case() {
    local category="$1"
    local case_index="$2"
    local theme="$3"
    local language="$4"
    local scenario="$5"
    local orientation="$6"
    local recognition_mode="$7"
    local translation_mode="$8"
    local night_mode="$9"
    run_index=$((run_index + 1))

    "${adb_command[@]}" shell cmd uimode night "$night_mode" >/dev/null
    "${adb_command[@]}" shell settings put system accelerometer_rotation 0 >/dev/null
    if [[ "$orientation" == "landscape" ]]; then
        "${adb_command[@]}" shell settings put system user_rotation 1 >/dev/null
        swipe="1600 1050 1600 360 500"
    else
        "${adb_command[@]}" shell settings put system user_rotation 0 >/dev/null
        swipe="720 2250 720 850 600"
    fi
    sleep 0.4

    local url="${base_url}?theme=${theme}&lang=${language}&scenario=${scenario}&seed=${case_index}"
    local escaped_url="${url//&/\\&}"
    "${adb_command[@]}" shell am force-stop "$package_name" >/dev/null
    "${adb_command[@]}" shell am force-stop "$page_package" >/dev/null
    "${adb_command[@]}" shell am start -a android.intent.action.VIEW \
        -d "$escaped_url" -p "$page_package" >/dev/null
    sleep "$page_load_seconds"

    local run_directory="$temporary_directory/run-$(printf '%03d' "$run_index")"
    mkdir -p "$run_directory"
    local report
    report=$(ANDROID_SERIAL="$serial" \
        AB_CANDIDATE=FULL_FRAME \
        AB_REFERENCE=FULL_FRAME \
        AB_CANDIDATE_CONTEXT=ACCURACY \
        AB_REFERENCE_CONTEXT=ACCURACY \
        AB_CANDIDATE_RENDERING=PARALLEL \
        AB_REFERENCE_RENDERING=PARALLEL \
        AB_CANDIDATE_BACKGROUND=THEME_SURFACE \
        AB_REFERENCE_BACKGROUND=BLUR_TINT \
        AB_RECOGNITION_MODE="$recognition_mode" \
        AB_TRANSLATION_MODE="$translation_mode" \
        AB_FULL_PAGE_BACKGROUND=true \
        AB_FULL_PAGE_BACKGROUND_SCOPE=CONTENT_VIEWPORT \
        AB_OVERLAY_ALPHA=1 \
        AB_SWIPE="$swipe" \
        AB_SETTLE_SECONDS=0.6 \
        AB_VISUAL_OUTPUT_DIR="$run_directory" \
        scripts/live-recognition-ab.sh | rg '^\{"schema"')

    local enriched
    enriched=$(jq -c \
        --arg category "$category" \
        --argjson case_index "$case_index" \
        --arg theme "$theme" \
        --arg language "$language" \
        --arg scenario "$scenario" \
        --arg orientation "$orientation" \
        '. + {a7_category: $category, a7_case: $case_index, page_theme: $theme,
            page_language: $language, page_scenario: $scenario,
            page_orientation: $orientation}' <<< "$report")
    printf '%s\n' "$enriched" | tee -a "$results_file" \
        > "$output_directory/reports/$(printf '%03d' "$run_index")-${category}.json"

    for artifact in candidate-preview reference-preview current; do
        case "$artifact" in
            candidate-preview) suffix="theme" ;;
            reference-preview) suffix="blur" ;;
            current) suffix="source" ;;
        esac
        ffmpeg -hide_banner -loglevel error -y \
            -i "$run_directory/$artifact.png" \
            -vf "scale=240:320:force_original_aspect_ratio=decrease,pad=240:320:(ow-iw)/2:(oh-ih)/2" \
            -q:v 5 "$output_directory/thumbs/$(printf '%03d' "$run_index")-$suffix.jpg"
    done

    if [[ "$case_index" == "1" ]]; then
        cp "$run_directory/current.png" \
            "$output_directory/samples/${category}-source.png"
        cp "$run_directory/candidate-preview.png" \
            "$output_directory/samples/${category}-theme.png"
        cp "$run_directory/reference-preview.png" \
            "$output_directory/samples/${category}-blur.png"
    fi
    echo "[$run_index/$((cases_per_category * 5))] $category case $case_index"
}

for case_index in $(seq 1 "$cases_per_category"); do
    run_case "light-en-zh" "$case_index" light en article portrait \
        ENGLISH ENGLISH_TO_CHINESE no
done
for case_index in $(seq 1 "$cases_per_category"); do
    run_case "dark-en-zh" "$case_index" dark en article portrait \
        ENGLISH ENGLISH_TO_CHINESE yes
done
for case_index in $(seq 1 "$cases_per_category"); do
    run_case "landscape-en-zh" "$case_index" light en article landscape \
        ENGLISH ENGLISH_TO_CHINESE no
done
for case_index in $(seq 1 "$cases_per_category"); do
    run_case "zh-en" "$case_index" light zh article portrait \
        CHINESE CHINESE_TO_ENGLISH no
done
for case_index in $(seq 1 "$cases_per_category"); do
    run_case "mixed-en-zh" "$case_index" light en mixed portrait \
        ENGLISH ENGLISH_TO_CHINESE no
done

for suffix in source theme blur; do
    ffmpeg -hide_banner -loglevel error -y -framerate 1 \
        -i "$output_directory/thumbs/%03d-$suffix.jpg" \
        -vf "tile=5x10" -frames:v 1 "$output_directory/$suffix-contact-sheet.jpg"
done

jq -s '
    def percentile(p): sort | .[((length * p | ceil) - 1)];
    {
        event: "live_background_a7_matrix",
        runs: length,
        categories: (group_by(.a7_category) | map({
            category: .[0].a7_category,
            runs: length,
            visual_pass_rate: ((map(select(.visual_render_pass == true)) | length) / length),
            coverage_pass_rate: ((map(select(.coverage_pass == true)) | length) / length),
            chrome_preserved_rate: ((map(select(
                .candidate_visual.changed_outside_patch_samples == 0 and
                .reference_visual.changed_outside_patch_samples == 0
            )) | length) / length),
            viewport_fallbacks: (map(select(.content_viewport.used_fallback == true)) | length),
            theme_compose_p50_ms: (map(.candidate_full_page.compose_ms) | percentile(0.5)),
            blur_compose_p50_ms: (map(.reference_full_page.compose_ms) | percentile(0.5))
        })),
        visual_pass_rate: ((map(select(.visual_render_pass == true)) | length) / length),
        coverage_pass_rate: ((map(select(.coverage_pass == true)) | length) / length),
        patch_coverage_pass_rate: ((map(select(.patch_coverage_pass == true)) | length) / length),
        chrome_preserved_rate: ((map(select(
            .candidate_visual.changed_outside_patch_samples == 0 and
            .reference_visual.changed_outside_patch_samples == 0
        )) | length) / length),
        viewport_fallbacks: (map(select(.content_viewport.used_fallback == true)) | length),
        content_area_ratio_p10: (map(.content_viewport.area_ratio) | percentile(0.1)),
        content_area_ratio_p50: (map(.content_viewport.area_ratio) | percentile(0.5)),
        theme_compose_p50_ms: (map(.candidate_full_page.compose_ms) | percentile(0.5)),
        theme_compose_p90_ms: (map(.candidate_full_page.compose_ms) | percentile(0.9)),
        blur_compose_p50_ms: (map(.reference_full_page.compose_ms) | percentile(0.5)),
        blur_compose_p90_ms: (map(.reference_full_page.compose_ms) | percentile(0.9)),
        blur_detail_p50: (map(.reference_full_page.detail_retention_ratio) | percentile(0.5)),
        semantic_quality_evaluated: all(.semantic_quality_evaluated == true)
    }
' "$results_file" > "$output_directory/summary.json"

cat "$output_directory/summary.json"
