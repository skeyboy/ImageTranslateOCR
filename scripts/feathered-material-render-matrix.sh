#!/usr/bin/env bash
set -euo pipefail

serial="${ANDROID_SERIAL:-}"
output_directory="${FEATHERED_MATERIAL_OUTPUT_DIR:-docs/validation/feathered-material-rendering-2026-07-29/run}"
package_name="com.example.imagetranslate"
runner="$package_name.test/androidx.test.runner.AndroidJUnitRunner"
test_class="com.example.imagetranslate.screenshot.FeatheredMaterialRenderingMatrixTest"
remote_directory="/storage/emulated/0/Android/data/$package_name/files/feathered-material-render"
app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

if [[ -z "$serial" ]]; then
    echo "ANDROID_SERIAL is required" >&2
    exit 1
fi
if [[ -e "$output_directory" ]] && [[ -n "$(find "$output_directory" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "Output directory is not empty: $output_directory" >&2
    echo "Choose a new FEATHERED_MATERIAL_OUTPUT_DIR for another run." >&2
    exit 1
fi

adb_command=(adb -s "$serial")
temporary_directory=$(mktemp -d /tmp/imagetranslate-feathered-material.XXXXXX)
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

for variant in \
    source \
    standard \
    feathered_theme \
    feathered_gaussian \
    enhanced_theme \
    enhanced_gaussian; do
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
    for variant in \
        source \
        standard \
        feathered_theme \
        feathered_gaussian \
        enhanced_theme \
        enhanced_gaussian; do
        cp "$raw_directory/$sample-$variant.png" \
            "$output_directory/samples/$sample-$variant.png"
    done
done

jq -s '
    def percentile(p): sort | .[((length * p | ceil) - 1)];
    def profile_summary(name):
        map(. [name]) as $profiles |
        {
            passes: ($profiles | map(select(.pass == true)) | length),
            structural_passes: ($profiles | map(select(.structural_pass == true)) | length),
            material_passes: ($profiles | map(select(.material_pass == true)) | length),
            coverage_min: ($profiles | map(.coverage_ratio) | min),
            clipped_regions: ($profiles | map(.clipped_regions) | add),
            minimum_text_scale: ($profiles | map(.minimum_text_scale) | min),
            minimum_contrast_ratio: ($profiles | map(.minimum_contrast_ratio) | min),
            protected_unchanged_min: ($profiles | map(.protected_unchanged_ratio) | min),
            chrome_unchanged_min: ($profiles | map(.chrome_unchanged_ratio) | min),
            protected_patch_intersections: ($profiles | map(.protected_patch_intersections) | add),
            changed_outside_patch_samples: ($profiles | map(.changed_outside_patch_samples) | add),
            detail_retention_p50: ($profiles | map(.source_detail_retention_ratio) | percentile(0.5)),
            detail_retention_p90: ($profiles | map(.source_detail_retention_ratio) | percentile(0.9)),
            detail_retention_max: ($profiles | map(.source_detail_retention_ratio) | max),
            boundary_change_p50: ($profiles | map(.material_boundary_change_ratio) | percentile(0.5)),
            boundary_change_p90: ($profiles | map(.material_boundary_change_ratio) | percentile(0.9)),
            boundary_change_max: ($profiles | map(.material_boundary_change_ratio) | max),
            changed_area_p50: ($profiles | map(.material_changed_area_ratio) | percentile(0.5)),
            changed_area_p90: ($profiles | map(.material_changed_area_ratio) | percentile(0.9)),
            rendering_p50_ms: ($profiles | map(.rendering_ms) | percentile(0.5)),
            rendering_p90_ms: ($profiles | map(.rendering_ms) | percentile(0.9)),
            rendering_max_ms: ($profiles | map(.rendering_ms) | max)
        };
    . as $cases |
    (profile_summary("standard")) as $standard |
    (profile_summary("feathered_theme")) as $theme |
    (profile_summary("feathered_gaussian")) as $gaussian |
    (profile_summary("enhanced_theme")) as $enhanced_theme |
    (profile_summary("enhanced_gaussian")) as $enhanced_gaussian |
    {
        event: "feathered_material_rendering_matrix",
        cases: length,
        configuration: {
            standard_overlay_alpha: 0.72,
            enhanced_overlay_alpha: 1,
            text_background: "none; text is drawn over a separate local material layer",
            full_page_background: false
        },
        profiles: {
            standard: $standard,
            feathered_theme: $theme,
            feathered_gaussian: $gaussian,
            enhanced_theme: $enhanced_theme,
            enhanced_gaussian: $enhanced_gaussian
        },
        categories: (
            group_by(.category) |
            map({
                category: .[0].category,
                cases: length,
                standard_passes: (map(select(.standard.pass == true)) | length),
                feathered_theme_passes: (map(select(.feathered_theme.pass == true)) | length),
                feathered_gaussian_passes: (map(select(.feathered_gaussian.pass == true)) | length),
                enhanced_theme_passes: (map(select(.enhanced_theme.pass == true)) | length),
                enhanced_gaussian_passes: (map(select(.enhanced_gaussian.pass == true)) | length),
                feathered_gaussian_detail_p90: (
                    map(.feathered_gaussian.source_detail_retention_ratio) | percentile(0.9)
                ),
                feathered_gaussian_boundary_p90: (
                    map(.feathered_gaussian.material_boundary_change_ratio) | percentile(0.9)
                )
            })
        ),
        gates: {
            fifty_cases_completed: (length == 50),
            standard_structural_baseline: ($standard.structural_passes == 50),
            theme_all_cases_pass: ($theme.passes == 50),
            gaussian_all_cases_pass: ($gaussian.passes == 50),
            enhanced_theme_all_cases_pass: ($enhanced_theme.passes == 50),
            enhanced_gaussian_all_cases_pass: ($enhanced_gaussian.passes == 50),
            candidate_text_legible: (
                $theme.minimum_text_scale >= 0.5 and
                $gaussian.minimum_text_scale >= 0.5 and
                $enhanced_theme.minimum_text_scale >= 0.5 and
                $enhanced_gaussian.minimum_text_scale >= 0.5 and
                $theme.minimum_contrast_ratio >= 4.5 and
                $gaussian.minimum_contrast_ratio >= 4.5 and
                $enhanced_theme.minimum_contrast_ratio >= 4.5 and
                $enhanced_gaussian.minimum_contrast_ratio >= 4.5
            ),
            candidate_non_text_protected: (
                $theme.protected_unchanged_min >= 0.99 and
                $gaussian.protected_unchanged_min >= 0.99 and
                $enhanced_theme.protected_unchanged_min >= 0.99 and
                $enhanced_gaussian.protected_unchanged_min >= 0.99 and
                $theme.chrome_unchanged_min >= 0.999 and
                $gaussian.chrome_unchanged_min >= 0.999 and
                $enhanced_theme.chrome_unchanged_min >= 0.999 and
                $enhanced_gaussian.chrome_unchanged_min >= 0.999
            ),
            candidate_edges_feathered: (
                $theme.boundary_change_max <= 0.15 and
                $gaussian.boundary_change_max <= 0.15 and
                $enhanced_theme.boundary_change_max <= 0.15 and
                $enhanced_gaussian.boundary_change_max <= 0.15
            ),
            candidate_source_detail_suppressed: (
                $theme.detail_retention_max <= 0.2 and
                $gaussian.detail_retention_max <= 0.35 and
                $enhanced_theme.detail_retention_max <= 0.2 and
                $enhanced_gaussian.detail_retention_max <= 0.35
            ),
            gaussian_footprint_not_larger_than_standard: (
                $gaussian.changed_area_p90 <= $standard.changed_area_p90
            ),
            enhanced_footprint_reduced: (
                $enhanced_theme.changed_area_p90 < $theme.changed_area_p90 and
                $enhanced_gaussian.changed_area_p90 < $gaussian.changed_area_p90
            ),
            gaussian_rendering_performance: (
                $gaussian.rendering_p90_ms <= 200 and
                $enhanced_gaussian.rendering_p90_ms <= 200
            )
        }
    } |
    .automation_pass = ([.gates[]] | all)
' "$raw_directory/results.jsonl" > "$output_directory/summary.json"

cat "$output_directory/summary.json"

if [[ "$(jq -r '.automation_pass' "$output_directory/summary.json")" != "true" ]]; then
    echo "Feathered material matrix did not pass every candidate gate." >&2
    exit 2
fi
