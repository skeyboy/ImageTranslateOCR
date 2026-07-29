#!/usr/bin/env bash
set -euo pipefail

serial="${ANDROID_SERIAL:-}"
output_directory="${DETERMINISTIC_RENDER_OUTPUT_DIR:-docs/validation/deterministic-overlay-rendering-2026-07-29/run}"
package_name="com.example.imagetranslate"
runner="$package_name.test/androidx.test.runner.AndroidJUnitRunner"
test_class="com.example.imagetranslate.screenshot.DeterministicOverlayRenderingMatrixTest"
remote_directory="/storage/emulated/0/Android/data/$package_name/files/deterministic-render"
app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

if [[ -z "$serial" ]]; then
    echo "ANDROID_SERIAL is required" >&2
    exit 1
fi
if [[ -e "$output_directory" ]] && [[ -n "$(find "$output_directory" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "Output directory is not empty: $output_directory" >&2
    echo "Choose a new DETERMINISTIC_RENDER_OUTPUT_DIR for another run." >&2
    exit 1
fi

adb_command=(adb -s "$serial")
temporary_directory=$(mktemp -d /tmp/imagetranslate-deterministic.XXXXXX)
raw_directory="$output_directory/raw"
thumb_directory="$output_directory/thumbs"

cleanup() {
    "${adb_command[@]}" shell rm -rf "$remote_directory" >/dev/null 2>&1 || true
    rm -rf "$temporary_directory"
}
trap cleanup EXIT

mkdir -p "$raw_directory" "$thumb_directory" "$output_directory/samples"

"${adb_command[@]}" install -r "$app_apk" >/dev/null
"${adb_command[@]}" install -r "$test_apk" >/dev/null
"${adb_command[@]}" shell am instrument -w \
    -e class "$test_class" "$runner" |
    tee "$output_directory/instrumentation.txt"

"${adb_command[@]}" pull "$remote_directory/." "$raw_directory" >/dev/null

case_count=$(wc -l < "$raw_directory/results.jsonl" | tr -d ' ')
if [[ "$case_count" != "50" ]]; then
    echo "Expected 50 result rows, found $case_count" >&2
    exit 2
fi

for variant in source ideal current; do
    index=0
    while IFS= read -r image; do
        index=$((index + 1))
        ffmpeg -hide_banner -loglevel error -y \
            -i "$image" \
            -vf "scale=240:320:force_original_aspect_ratio=decrease,pad=240:320:(ow-iw)/2:(oh-ih)/2" \
            -q:v 5 "$thumb_directory/$(printf '%03d' "$index")-$variant.jpg"
    done < <(find "$raw_directory" -maxdepth 1 -name "*-$variant.png" | sort)
    if [[ "$index" != "50" ]]; then
        echo "Expected 50 $variant images, found $index" >&2
        exit 2
    fi
    ffmpeg -hide_banner -loglevel error -y -framerate 1 \
        -i "$thumb_directory/%03d-$variant.jpg" \
        -vf "tile=5x10" -frames:v 1 "$output_directory/$variant-contact-sheet.jpg"
done

for sample in \
    001-light-body \
    011-dark-body \
    021-non-text-mixed \
    031-landscape-edge \
    041-length-stress; do
    for variant in source ideal current; do
        cp "$raw_directory/$sample-$variant.png" \
            "$output_directory/samples/$sample-$variant.png"
    done
done

observed_failure_ratio=$(jq -s \
    '([.[].completion.failed] | add) / ([.[].completion.recognized] | add)' \
    docs/validation/live-scroll-atomic-2026-07-29/run/scroll-results.jsonl)
observed_retained_latin_p90=$(jq '.retained_latin_ratio_p90' \
    docs/validation/live-scroll-atomic-2026-07-29/run/summary.json)

jq -s \
    --argjson observed_failure_ratio "$observed_failure_ratio" \
    --argjson observed_retained_latin_p90 "$observed_retained_latin_p90" '
    def percentile(p): sort | .[((length * p | ceil) - 1)];
    def profile_summary(name):
        map(. [name]) as $profiles |
        {
            passes: ($profiles | map(select(.pass == true)) | length),
            coverage_min: ($profiles | map(.coverage_ratio) | min),
            coverage_p10: ($profiles | map(.coverage_ratio) | percentile(0.1)),
            clipped_regions: ($profiles | map(.clipped_regions) | add),
            failed_regions: ($profiles | map(.failed_regions) | add),
            dropped_input_regions: ($profiles | map(.dropped_input_regions) | add),
            retained_source_regions: ($profiles | map(.retained_source_regions) | add),
            minimum_text_scale: ($profiles | map(.minimum_text_scale) | min),
            minimum_contrast_ratio: ($profiles | map(.minimum_contrast_ratio) | min),
            protected_unchanged_min: ($profiles | map(.protected_unchanged_ratio) | min),
            chrome_unchanged_min: ($profiles | map(.chrome_unchanged_ratio) | min),
            protected_patch_intersections: ($profiles | map(.protected_patch_intersections) | add),
            changed_outside_patch_samples: ($profiles | map(.changed_outside_patch_samples) | add),
            rendering_p50_ms: ($profiles | map(.rendering_ms) | percentile(0.5)),
            rendering_p90_ms: ($profiles | map(.rendering_ms) | percentile(0.9)),
            rendering_max_ms: ($profiles | map(.rendering_ms) | max)
        };
    . as $cases |
    (profile_summary("ideal")) as $ideal |
    (profile_summary("current_calibrated")) as $current |
    {
        event: "deterministic_overlay_rendering_matrix",
        cases: length,
        categories: (
            group_by(.category) |
            map({
                category: .[0].category,
                cases: length,
                ideal_passes: (map(select(.ideal.pass == true)) | length),
                current_calibrated_passes: (map(select(.current_calibrated.pass == true)) | length),
                ideal_rendering_p90_ms: (map(.ideal.rendering_ms) | percentile(0.9)),
                ideal_minimum_text_scale: (map(.ideal.minimum_text_scale) | min),
                ideal_minimum_contrast_ratio: (map(.ideal.minimum_contrast_ratio) | min)
            })
        ),
        input_profiles: {
            high_accuracy_ceiling: $ideal,
            current_calibrated: $current
        },
        current_pipeline_reference: {
            source: "2026-07-29 real MediaProjection continuous scroll run",
            recognized_regions: 466,
            failed_regions: 17,
            failure_ratio: $observed_failure_ratio,
            retained_latin_ratio_p90: $observed_retained_latin_p90,
            interpretation: "Calibration input only; not a deterministic renderer failure."
        },
        gates: {
            fifty_cases_completed: (length == 50),
            ideal_all_regions_rendered: ($ideal.failed_regions == 0 and $ideal.coverage_min == 1),
            ideal_no_clipping: ($ideal.clipped_regions == 0),
            ideal_legible_scale: ($ideal.minimum_text_scale >= 0.5),
            ideal_contrast: ($ideal.minimum_contrast_ratio >= 4.5),
            ideal_non_text_protected: (
                $ideal.protected_unchanged_min >= 0.99 and
                $ideal.chrome_unchanged_min >= 0.999 and
                $ideal.protected_patch_intersections == 0
            ),
            ideal_changes_scoped_to_patches: ($ideal.changed_outside_patch_samples == 0),
            ideal_rendering_performance: ($ideal.rendering_p90_ms <= 160)
        }
    } |
    .automation_pass = ([.gates[]] | all)
' "$raw_directory/results.jsonl" > "$output_directory/summary.json"

cat "$output_directory/summary.json"

if [[ "$(jq -r '.automation_pass' "$output_directory/summary.json")" != "true" ]]; then
    echo "Deterministic rendering matrix did not pass every ideal-input gate." >&2
    exit 2
fi
