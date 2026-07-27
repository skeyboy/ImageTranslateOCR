#!/usr/bin/env bash
set -euo pipefail

run_count="${AB_RUNS:-4}"
page_url="${AB_PAGE_URL:-https://doc.rust-lang.org/book/ch00-00-introduction.html}"
page_package="${AB_PAGE_PACKAGE:-com.android.browser}"
serial="${ANDROID_SERIAL:-}"
results_file="${AB_RESULTS_FILE:-}"

adb_command=(adb)
if [[ -n "$serial" ]]; then
    adb_command+=( -s "$serial" )
fi

result_directory=$(mktemp -d /tmp/imagetranslate-ab-batch.XXXXXX)
trap 'rm -rf "$result_directory"' EXIT
json_lines="$result_directory/results.jsonl"

for run in $(seq 1 "$run_count"); do
    separator="?"
    if [[ "$page_url" == *"?"* ]]; then
        separator="&"
    fi
    "${adb_command[@]}" shell am force-stop com.example.imagetranslate
    "${adb_command[@]}" shell am start -a android.intent.action.VIEW \
        -d "${page_url}${separator}ab_run=$run" -p "$page_package" >/dev/null
    sleep "${AB_PAGE_LOAD_SECONDS:-4}"

    candidate_first=true
    if (( run % 2 == 0 )); then
        candidate_first=false
    fi
    report=$(AB_CANDIDATE_FIRST="$candidate_first" \
        scripts/live-recognition-ab.sh | rg '^\{"schema"')
    printf '%s\n' "$report" | tee -a "$json_lines"
    if [[ "$report" == *'"event":"live_recognition_ab_failed"'* ]]; then
        echo "A/B run $run failed" >&2
        exit 1
    fi
done

if [[ -n "$results_file" ]]; then
    mkdir -p "$(dirname "$results_file")"
    cp "$json_lines" "$results_file"
fi

jq -s '
    def percentile(p): sort | .[((length * p | ceil) - 1)];
    {
        event: "live_recognition_ab_batch",
        runs: length,
        candidate_strategy: (.[0].candidate_strategy // "UNKNOWN"),
        candidate_context_profile: (.[0].candidate.context_profile // "UNKNOWN"),
        reference_context_profile: (.[0].reference.context_profile // "UNKNOWN"),
        candidate_rendering_mode: (.[0].candidate.rendering_mode // "UNKNOWN"),
        reference_rendering_mode: (.[0].reference.rendering_mode // "UNKNOWN"),
        candidate_background_mode: (.[0].candidate.background_mode // "UNKNOWN"),
        reference_background_mode: (.[0].reference.background_mode // "UNKNOWN"),
        differential_hit_rate: ((map(select(.candidate.applied_strategy == "DIFFERENTIAL")) |
            length) / length),
        visual_render_pass_rate: ((map(select(.visual_render_pass == true)) | length) / length),
        background_detail_gate_pass_rate:
            ((map(select(.background_detail_gate_pass == true)) | length) / length),
        background_performance_gate_pass_rate:
            ((map(select(.background_performance_gate_pass == true)) | length) / length),
        semantic_quality_evaluated: all(.semantic_quality_evaluated == true),
        coverage_pass_rate: ((map(select(.coverage_pass == true)) | length) / length),
        patch_coverage_pass_rate: ((map(select(.patch_coverage_pass == true)) | length) / length),
        decisions: (group_by(.decision) | map({key: .[0].decision, value: length}) | from_entries),
        candidate_p50_ms: (map(.candidate_total_ms) | percentile(0.5)),
        candidate_p90_ms: (map(.candidate_total_ms) | percentile(0.9)),
        reference_p50_ms: (map(.reference_total_ms) | percentile(0.5)),
        reference_p90_ms: (map(.reference_total_ms) | percentile(0.9)),
        candidate_render_p50_ms: (map(.candidate.render_ms) | percentile(0.5)),
        candidate_render_p90_ms: (map(.candidate.render_ms) | percentile(0.9)),
        reference_render_p50_ms: (map(.reference.render_ms) | percentile(0.5)),
        reference_render_p90_ms: (map(.reference.render_ms) | percentile(0.9)),
        background_render_overhead_p50:
            (map(.background_render_overhead_ratio) | percentile(0.5)),
        candidate_background_detail_retention_p50:
            (map(.candidate.background_detail_retention_ratio // 0) | percentile(0.5)),
        speedup_p50: (map(.speedup_ratio) | percentile(0.5)),
        region_recall_p10: (map(.region_recall) | percentile(0.1)),
        region_recall_p50: (map(.region_recall) | percentile(0.5)),
        area_recall_p10: (map(.area_recall) | percentile(0.1)),
        area_recall_p50: (map(.area_recall) | percentile(0.5)),
        patch_region_recall_p10: (map(.patch_region_recall) | percentile(0.1)),
        patch_region_recall_p50: (map(.patch_region_recall) | percentile(0.5)),
        patch_area_recall_p10: (map(.patch_area_recall) | percentile(0.1)),
        patch_area_recall_p50: (map(.patch_area_recall) | percentile(0.5)),
        recognition_area_ratio_p50: (map(.candidate.recognition_area_ratio // 1) |
            percentile(0.5)),
        dirty_cells_p50: (map(.candidate.dirty_cells // 0) | percentile(0.5)),
        restored_boundary_tracks_total: (map(.candidate.restored_boundary_tracks // 0) | add),
        fallback_reasons: (map(.candidate.differential_fallback_reason // "NONE") |
            group_by(.) | map({key: .[0], value: length}) | from_entries)
    }
' "$json_lines"
