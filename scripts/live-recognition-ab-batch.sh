#!/usr/bin/env bash
set -euo pipefail

run_count="${AB_RUNS:-4}"
page_url="${AB_PAGE_URL:-https://doc.rust-lang.org/book/ch00-00-introduction.html}"
page_package="${AB_PAGE_PACKAGE:-com.android.browser}"
serial="${ANDROID_SERIAL:-}"

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

jq -s '
    def percentile(p): sort | .[((length * p | ceil) - 1)];
    {
        event: "live_recognition_ab_batch",
        runs: length,
        candidate_strategy: (.[0].candidate_strategy // "UNKNOWN"),
        coverage_pass_rate: ((map(select(.coverage_pass == true)) | length) / length),
        decisions: (group_by(.decision) | map({key: .[0].decision, value: length}) | from_entries),
        candidate_p50_ms: (map(.candidate_total_ms) | percentile(0.5)),
        candidate_p90_ms: (map(.candidate_total_ms) | percentile(0.9)),
        reference_p50_ms: (map(.reference_total_ms) | percentile(0.5)),
        reference_p90_ms: (map(.reference_total_ms) | percentile(0.9)),
        speedup_p50: (map(.speedup_ratio) | percentile(0.5)),
        region_recall_p50: (map(.region_recall) | percentile(0.5)),
        area_recall_p50: (map(.area_recall) | percentile(0.5))
    }
' "$json_lines"
