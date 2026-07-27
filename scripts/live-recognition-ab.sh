#!/usr/bin/env bash
set -euo pipefail

package_name="com.example.imagetranslate"
component="$package_name/.debug.LiveRecognitionBenchmarkActivity"
candidate="${AB_CANDIDATE:-ADAPTIVE}"
reference="${AB_REFERENCE:-FULL_FRAME}"
candidate_context="${AB_CANDIDATE_CONTEXT:-BALANCED}"
reference_context="${AB_REFERENCE_CONTEXT:-BALANCED}"
candidate_rendering="${AB_CANDIDATE_RENDERING:-SEQUENTIAL}"
reference_rendering="${AB_REFERENCE_RENDERING:-SEQUENTIAL}"
recognition_mode="${AB_RECOGNITION_MODE:-ENGLISH}"
translation_mode="${AB_TRANSLATION_MODE:-ENGLISH_TO_CHINESE}"
candidate_first="${AB_CANDIDATE_FIRST:-true}"
serial="${ANDROID_SERIAL:-}"

adb_command=(adb)
if [[ -n "$serial" ]]; then
    adb_command+=( -s "$serial" )
fi

temporary_directory=$(mktemp -d /tmp/imagetranslate-ab.XXXXXX)
cleanup() {
    rm -rf "$temporary_directory"
    "${adb_command[@]}" shell am force-stop "$package_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

baseline_local="$temporary_directory/baseline.png"
current_local="$temporary_directory/current.png"
temporary_remote_directory="/data/local/tmp/imagetranslate-ab"
baseline_temporary_remote="$temporary_remote_directory/baseline.png"
current_temporary_remote="$temporary_remote_directory/current.png"
internal_directory="/data/user/0/$package_name/files/benchmark"
baseline_internal="$internal_directory/baseline.png"
current_internal="$internal_directory/current.png"

"${adb_command[@]}" shell am force-stop "$package_name"
"${adb_command[@]}" exec-out screencap -p > "$baseline_local"

read -r swipe_x1 swipe_y1 swipe_x2 swipe_y2 swipe_duration <<< \
    "${AB_SWIPE:-720 2200 720 900 650}"
"${adb_command[@]}" shell input swipe \
    "$swipe_x1" "$swipe_y1" "$swipe_x2" "$swipe_y2" "$swipe_duration"
sleep "${AB_SETTLE_SECONDS:-1}"
"${adb_command[@]}" exec-out screencap -p > "$current_local"

"${adb_command[@]}" shell mkdir -p "$temporary_remote_directory"
"${adb_command[@]}" push "$baseline_local" "$baseline_temporary_remote" >/dev/null
"${adb_command[@]}" push "$current_local" "$current_temporary_remote" >/dev/null
"${adb_command[@]}" shell run-as "$package_name" mkdir -p files/benchmark
"${adb_command[@]}" shell run-as "$package_name" cp \
    "$baseline_temporary_remote" files/benchmark/baseline.png
"${adb_command[@]}" shell run-as "$package_name" cp \
    "$current_temporary_remote" files/benchmark/current.png
"${adb_command[@]}" shell rm -f "$baseline_temporary_remote" "$current_temporary_remote"
"${adb_command[@]}" logcat -c
"${adb_command[@]}" shell am start -W -n "$component" \
    --es baseline_image_path "$baseline_internal" \
    --es current_image_path "$current_internal" \
    --es candidate_segmentation "$candidate" \
    --es reference_segmentation "$reference" \
    --es candidate_context_profile "$candidate_context" \
    --es reference_context_profile "$reference_context" \
    --es candidate_rendering_mode "$candidate_rendering" \
    --es reference_rendering_mode "$reference_rendering" \
    --es recognition_mode "$recognition_mode" \
    --es translation_mode "$translation_mode" \
    --ez candidate_first "$candidate_first" >/dev/null

for _ in $(seq 1 120); do
    report=$("${adb_command[@]}" logcat -d -v raw -s LIVE_OCR_AB:I 2>/dev/null |
        rg '"event":"live_recognition_ab(_failed)?"' | tail -n 1 || true)
    if [[ "$report" == *'"event":"live_recognition_ab"'* || \
          "$report" == *'"event":"live_recognition_ab_failed"'* ]]; then
        printf '%s\n' "$report"
        exit 0
    fi
    sleep 1
done

echo "Timed out waiting for LIVE_OCR_AB output" >&2
exit 1
