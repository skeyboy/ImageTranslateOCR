#!/usr/bin/env bash
set -euo pipefail

serial="${ANDROID_SERIAL:-}"
scroll_runs="${SCROLL_RUNS:-30}"
theme="${SCROLL_THEME:-light}"
background_mode="${SCROLL_BACKGROUND_MODE:-OFF}"
port="${SCROLL_SERVER_PORT:-8766}"
page_package="${SCROLL_PAGE_PACKAGE:-com.android.browser}"
package_name="com.example.imagetranslate"
component="$package_name/.debug.LiveScrollBenchmarkLauncherActivity"
fixture_root="docs/validation/live-scroll-atomic-2026-07-29"
output_directory="${SCROLL_OUTPUT_DIR:-$fixture_root/run}"
page_url="http://127.0.0.1:${port}/fixtures/continuous-scroll.html?theme=${theme}"

if [[ -z "$serial" ]]; then
    echo "ANDROID_SERIAL is required" >&2
    exit 1
fi
if [[ ! "$scroll_runs" =~ ^[1-9][0-9]*$ ]]; then
    echo "SCROLL_RUNS must be a positive integer" >&2
    exit 1
fi
case "$background_mode" in
    OFF|THEME_COLOR|GAUSSIAN_BLUR) ;;
    *)
        echo "Unsupported SCROLL_BACKGROUND_MODE: $background_mode" >&2
        exit 1
        ;;
esac

adb_command=(adb -s "$serial")
temporary_directory=$(mktemp -d /tmp/imagetranslate-scroll.XXXXXX)
server_pid=""
screenrecord_pid=""
remote_video=""
original_rotation=$("${adb_command[@]}" shell settings get system user_rotation | tr -d '\r')
original_accelerometer=$("${adb_command[@]}" shell settings get system accelerometer_rotation | tr -d '\r')
original_night=$("${adb_command[@]}" shell cmd uimode night | tr -d '\r')

cleanup() {
    "${adb_command[@]}" shell am force-stop "$package_name" >/dev/null 2>&1 || true
    "${adb_command[@]}" shell pkill -INT screenrecord >/dev/null 2>&1 || true
    if [[ -n "$screenrecord_pid" ]]; then
        wait "$screenrecord_pid" >/dev/null 2>&1 || true
    fi
    "${adb_command[@]}" reverse --remove "tcp:$port" >/dev/null 2>&1 || true
    if [[ -n "$server_pid" ]]; then
        kill "$server_pid" >/dev/null 2>&1 || true
        wait "$server_pid" >/dev/null 2>&1 || true
    fi
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
trap cleanup EXIT

mkdir -p "$output_directory/checkpoints"
results_file="$output_directory/scroll-results.jsonl"
: > "$results_file"

dump_ui() {
    local name="$1"
    "${adb_command[@]}" shell uiautomator dump "/sdcard/$name.xml" >/dev/null
    "${adb_command[@]}" exec-out cat "/sdcard/$name.xml" \
        > "$temporary_directory/$name.xml"
    printf '%s\n' "$temporary_directory/$name.xml"
}

center_from_bounds() {
    local bounds="$1"
    local coordinates
    coordinates=$(sed 's/]\[/,/' <<< "$bounds" | tr -d '[]' | tr ',' ' ')
    read -r left top right bottom <<< "$coordinates"
    printf '%s %s\n' "$(((left + right) / 2))" "$(((top + bottom) / 2))"
}

tap_xpath() {
    local xml="$1"
    local xpath="$2"
    local bounds
    bounds=$(xmllint --xpath "string(($xpath)[1]/@bounds)" "$xml")
    if [[ -z "$bounds" ]]; then
        return 1
    fi
    local center
    center=$(center_from_bounds "$bounds")
    read -r x y <<< "$center"
    "${adb_command[@]}" shell input tap "$x" "$y"
}

probe_state() {
    local xml="$1"
    local label
    label=$(xmllint --xpath \
        'string((//node[starts-with(@text,"Touch probe count")])[1]/@text)' \
        "$xml")
    if [[ ! "$label" =~ ^Touch\ probe\ count\ ([0-9]+)\ scroll\ position\ ([0-9]+)$ ]]; then
        echo "Unable to read touch probe state: $label" >&2
        return 1
    fi
    printf '%s %s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
}

wait_for_probe() {
    for attempt in $(seq 1 20); do
        local xml
        xml=$(dump_ui "probe-$attempt")
        if probe_state "$xml" >/dev/null 2>&1; then
            printf '%s\n' "$xml"
            return 0
        fi
        sleep 0.25
    done
    echo "Timed out waiting for the fixture accessibility probe" >&2
    return 1
}

close_browser_translation_prompt() {
    local xml
    xml=$(dump_ui "browser-prompt")
    tap_xpath "$xml" '//node[@content-desc="翻译弹窗关闭"]' >/dev/null 2>&1 || true
    sleep 0.25
}

accept_projection_permission() {
    local xml
    for attempt in $(seq 1 20); do
        xml=$(dump_ui "projection-$attempt")
        if xmllint --xpath \
            'boolean(//node[@resource-id="com.android.systemui:id/screen_share_mode_spinner"])' \
            "$xml" 2>/dev/null | rg -q true; then
            break
        fi
        sleep 0.25
    done

    local selected_mode
    selected_mode=$(xmllint --xpath \
        'string((//node[@resource-id="com.android.systemui:id/screen_share_mode_spinner"])[1]/@text)' \
        "$xml")
    if [[ "$selected_mode" != *"整个屏幕"* && "$selected_mode" != *"entire screen"* ]]; then
        tap_xpath "$xml" \
            '//node[@resource-id="com.android.systemui:id/screen_share_mode_spinner"]'
        sleep 0.25
        xml=$(dump_ui "projection-mode")
        tap_xpath "$xml" \
            '//node[@text="共享整个屏幕" or @text="Share entire screen"]'
        sleep 0.25
    fi
    xml=$(dump_ui "projection-confirm")
    tap_xpath "$xml" '//node[@resource-id="android:id/button1"]'
}

metrics_lines() {
    "${adb_command[@]}" logcat -d -v raw -s LiveOcrMetrics:I 2>/dev/null |
        rg '^\{"schema"' || true
}

wait_for_initial_translation() {
    for attempt in $(seq 1 80); do
        local lines
        lines=$(metrics_lines)
        if [[ "$lines" == *'"event":"overlay_translation_completed"'* &&
              "$lines" == *'"event":"overlay_translation_presented"'* ]]; then
            return 0
        fi
        sleep 0.25
    done
    echo "Timed out waiting for the initial live translation" >&2
    metrics_lines >&2
    return 1
}

wait_for_scroll_translation() {
    for attempt in $(seq 1 80); do
        local lines
        lines=$(metrics_lines)
        if [[ "$lines" == *'"event":"overlay_translation_hidden_for_movement"'* &&
              "$lines" == *'"event":"overlay_translation_completed"'* &&
              "$lines" == *'"event":"overlay_translation_presented"'* ]]; then
            return 0
        fi
        sleep 0.25
    done
    echo "Timed out waiting for a settled scroll translation" >&2
    metrics_lines >&2
    return 1
}

control_frame() {
    local window_dump="$temporary_directory/windows.txt"
    "${adb_command[@]}" shell dumpsys window windows > "$window_dump"
    awk '
        /Window #[0-9]+ Window.*com.example.imagetranslate/ {
            active = 1
            flags = ""
            next
        }
        active && /fl=/ { flags = $0 }
        active && /Frames:/ {
            if (flags !~ /NOT_TOUCHABLE/) {
                if (match($0, /frame=\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]/)) {
                    print substr($0, RSTART + 6, RLENGTH - 6)
                    exit
                }
            }
            active = 0
        }
    ' "$window_dump"
}

expand_control_and_toggle_point() {
    local frame
    frame=$(control_frame)
    local center
    center=$(center_from_bounds "$frame")
    read -r x y <<< "$center"
    "${adb_command[@]}" shell input tap "$x" "$y"
    sleep 0.35

    frame=$(control_frame)
    local coordinates
    coordinates=$(sed 's/]\[/,/' <<< "$frame" | tr -d '[]' | tr ',' ' ')
    read -r left top right bottom <<< "$coordinates"
    local width=$((right - left))
    printf '%s %s\n' "$((left + width * 124 / 380))" "$(((top + bottom) / 2))"
}

ssim_for_crop() {
    local translated="$1"
    local source="$2"
    local crop="$3"
    ffmpeg -hide_banner -loglevel info \
        -i "$translated" -i "$source" \
        -lavfi "[0:v]crop=$crop[a];[1:v]crop=$crop[b];[a][b]ssim" \
        -f null - 2>&1 |
        sed -n 's/.* All:\([0-9.]*\).*/\1/p' |
        tail -n 1
}

start_video_segment() {
    local segment="$1"
    remote_video="/sdcard/imagetranslate-live-scroll-$segment.mp4"
    "${adb_command[@]}" shell rm -f "$remote_video" >/dev/null 2>&1 || true
    "${adb_command[@]}" shell screenrecord --time-limit 180 "$remote_video" \
        >/dev/null 2>&1 &
    screenrecord_pid=$!
    sleep 0.4
}

finish_video_segment() {
    local segment="$1"
    "${adb_command[@]}" shell pkill -INT screenrecord >/dev/null 2>&1 || true
    wait "$screenrecord_pid" >/dev/null 2>&1 || true
    screenrecord_pid=""
    "${adb_command[@]}" pull "$remote_video" \
        "$output_directory/session-$segment.mp4" >/dev/null
    "${adb_command[@]}" shell rm -f "$remote_video" >/dev/null 2>&1 || true
}

python3 -m http.server "$port" --bind 127.0.0.1 --directory "$fixture_root" \
    > "$temporary_directory/http.log" 2>&1 &
server_pid=$!
for attempt in $(seq 1 20); do
    if curl -fsS "http://127.0.0.1:$port/fixtures/continuous-scroll.html" \
        2>/dev/null \
        >/dev/null; then
        break
    fi
    sleep 0.2
done
"${adb_command[@]}" reverse "tcp:$port" "tcp:$port" >/dev/null

"${adb_command[@]}" shell cmd uimode night \
    "$([[ "$theme" == dark ]] && echo yes || echo no)" >/dev/null
"${adb_command[@]}" shell settings put system accelerometer_rotation 0 >/dev/null
"${adb_command[@]}" shell settings put system user_rotation 0 >/dev/null
"${adb_command[@]}" shell am force-stop "$package_name" >/dev/null
"${adb_command[@]}" shell am force-stop "$page_package" >/dev/null
escaped_url="${page_url//&/\\&}"
"${adb_command[@]}" shell am start -a android.intent.action.VIEW \
    -d "$escaped_url" -p "$page_package" >/dev/null
wait_for_probe >/dev/null
close_browser_translation_prompt

"${adb_command[@]}" logcat -c
"${adb_command[@]}" shell am start -n "$component" \
    --es background_mode "$background_mode" >/dev/null
accept_projection_permission
wait_for_initial_translation
close_browser_translation_prompt
sleep 0.8

initial_probe_xml=$(wait_for_probe)
read -r current_touch current_scroll <<< "$(probe_state "$initial_probe_xml")"
probe_bounds=$(xmllint --xpath \
    'string((//node[starts-with(@text,"Touch probe count")])[1]/@bounds)' \
    "$initial_probe_xml")
read -r probe_x probe_y <<< "$(center_from_bounds "$probe_bounds")"
video_split_run=$(((scroll_runs + 1) / 2))
start_video_segment 1

for run in $(seq 1 "$scroll_runs"); do
    touch_before=$current_touch
    scroll_before=$current_scroll
    "${adb_command[@]}" logcat -c
    "${adb_command[@]}" shell input swipe 720 2400 720 1050 550
    wait_for_scroll_translation
    sleep 0.65

    lines=$(metrics_lines)
    hidden=$(jq -c 'select(.event == "overlay_translation_hidden_for_movement")' \
        <<< "$lines" | tail -n 1)
    completion=$(jq -c 'select(.event == "overlay_translation_completed")' \
        <<< "$lines" | tail -n 1)
    presented=$(jq -c 'select(.event == "overlay_translation_presented")' \
        <<< "$lines" | tail -n 1)
    completion_count=$(jq -s \
        'map(select(.event == "overlay_translation_completed")) | length' <<< "$lines")
    hidden_count=$(jq -s \
        'map(select(.event == "overlay_translation_hidden_for_movement")) | length' \
        <<< "$lines")
    presented_count=$(jq -s \
        'map(select(.event == "overlay_translation_presented")) | length' <<< "$lines")

    "${adb_command[@]}" shell input tap "$probe_x" "$probe_y"
    sleep 0.2
    touched_xml=$(wait_for_probe)
    read -r touch_after scroll_after_touch <<< "$(probe_state "$touched_xml")"
    scroll_after=$scroll_after_touch
    current_touch=$touch_after
    current_scroll=$scroll_after

    scroll_delta=$((scroll_after - scroll_before))
    touch_delta=$((touch_after - touch_before))
    jq -cn \
        --argjson run "$run" \
        --argjson scroll_before "$scroll_before" \
        --argjson scroll_after "$scroll_after" \
        --argjson scroll_delta "$scroll_delta" \
        --argjson touch_before "$touch_before" \
        --argjson touch_after "$touch_after" \
        --argjson touch_delta "$touch_delta" \
        --argjson completion_count "$completion_count" \
        --argjson hidden_count "$hidden_count" \
        --argjson presented_count "$presented_count" \
        --argjson hidden "$hidden" \
        --argjson completion "$completion" \
        --argjson presented "$presented" \
        '{run:$run, scroll_before:$scroll_before, scroll_after:$scroll_after,
          scroll_delta:$scroll_delta, scroll_pass:($scroll_delta >= 200),
          touch_before:$touch_before, touch_after:$touch_after,
          touch_delta:$touch_delta, touch_pass:($touch_delta == 1),
          completion_count:$completion_count, hidden_count:$hidden_count,
          presented_count:$presented_count, hidden:$hidden,
          completion:$completion, presented:$presented}' \
        | tee -a "$results_file" >/dev/null

    if [[ "$run" == 1 || "$run" == $(((scroll_runs + 1) / 2)) ||
          "$run" == "$scroll_runs" ]]; then
        "${adb_command[@]}" exec-out screencap -p \
            > "$output_directory/checkpoints/scroll-$(printf '%02d' "$run").png"
    fi
    echo "[$run/$scroll_runs] scroll=$scroll_delta touch=$touch_delta " \
        "hidden=$(jq -r '.motion_to_hidden_ms' <<< "$hidden")ms " \
        "commit=$(jq -r '.last_motion_to_commit_ms' <<< "$completion")ms"
    if [[ "$run" == "$video_split_run" ]]; then
        finish_video_segment 1
        start_video_segment 2
    fi
done

"${adb_command[@]}" logcat -c
sleep 15
idle_lines=$(metrics_lines)
idle_activity_count=$(jq -s \
    'map(select(.event == "overlay_translation_completed" or
                .event == "overlay_translation_hidden_for_movement" or
                .event == "settled_viewport_restored")) | length' <<< "$idle_lines")

before_toggle_xml=$(wait_for_probe)
read -r _ anchor_before <<< "$(probe_state "$before_toggle_xml")"
marker_bounds=$(xmllint --xpath \
    'string((//node[@text="Protected visual marker"])[1]/@bounds)' \
    "$before_toggle_xml")
toggle_point=$(expand_control_and_toggle_point)
read -r toggle_x toggle_y <<< "$toggle_point"
sleep 0.35
"${adb_command[@]}" exec-out screencap -p \
    > "$output_directory/translated-visible.png"
"${adb_command[@]}" shell input tap "$toggle_x" "$toggle_y"
sleep 0.6
source_xml=$(wait_for_probe)
read -r _ anchor_source <<< "$(probe_state "$source_xml")"
"${adb_command[@]}" exec-out screencap -p > "$output_directory/source-visible.png"
"${adb_command[@]}" shell input tap "$toggle_x" "$toggle_y"
sleep 0.6
translation_xml=$(wait_for_probe)
read -r _ anchor_translation <<< "$(probe_state "$translation_xml")"

marker_coordinates=$(sed 's/]\[/,/' <<< "$marker_bounds" | tr -d '[]' | tr ',' ' ')
read -r marker_left marker_top marker_right marker_bottom <<< "$marker_coordinates"
marker_crop="$((marker_right - marker_left)):$((marker_bottom - marker_top)):$marker_left:$marker_top"
marker_ssim=$(ssim_for_crop \
    "$output_directory/translated-visible.png" \
    "$output_directory/source-visible.png" \
    "$marker_crop")
browser_chrome_ssim=$(ssim_for_crop \
    "$output_directory/translated-visible.png" \
    "$output_directory/source-visible.png" \
    "1000:130:300:160")
anchor_drift_source=$((anchor_source - anchor_before))
anchor_drift_translation=$((anchor_translation - anchor_before))
if (( anchor_drift_source < 0 )); then anchor_drift_source=$((-anchor_drift_source)); fi
if (( anchor_drift_translation < 0 )); then
    anchor_drift_translation=$((-anchor_drift_translation))
fi
maximum_anchor_drift=$((
    anchor_drift_source > anchor_drift_translation ?
        anchor_drift_source : anchor_drift_translation
))

finish_video_segment 2

jq -s \
    --arg theme "$theme" \
    --arg background_mode "$background_mode" \
    --argjson expected_runs "$scroll_runs" \
    --argjson idle_activity_count "$idle_activity_count" \
    --argjson maximum_anchor_drift "$maximum_anchor_drift" \
    --argjson marker_ssim "$marker_ssim" \
    --argjson browser_chrome_ssim "$browser_chrome_ssim" '
    def percentile(p): sort | .[((length * p | ceil) - 1)];
    {
      event: "live_continuous_scroll_validation",
      theme: $theme,
      background_mode: $background_mode,
      runs: length,
      expected_runs: $expected_runs,
      scroll_passes: (map(select(.scroll_pass)) | length),
      touch_passes: (map(select(.touch_pass)) | length),
      single_completion_passes: (map(select(.completion_count == 1)) | length),
      single_hidden_passes: (map(select(.hidden_count == 1)) | length),
      atomic_presentation_passes: (map(select(
        .presented_count == 1 and .presented.atomic_group == true
      )) | length),
      motion_to_hidden_p50_ms: (map(.hidden.motion_to_hidden_ms) | percentile(0.5)),
      motion_to_hidden_p90_ms: (map(.hidden.motion_to_hidden_ms) | percentile(0.9)),
      last_motion_to_commit_p50_ms:
        (map(.completion.last_motion_to_commit_ms) | percentile(0.5)),
      last_motion_to_commit_p90_ms:
        (map(.completion.last_motion_to_commit_ms) | percentile(0.9)),
      capture_to_commit_p90_ms:
        (map(.completion.capture_to_commit_ms) | percentile(0.9)),
      total_p90_ms: (map(.completion.total_ms) | percentile(0.9)),
      failed_regions_total: (map(.completion.failed) | add),
      retained_latin_ratio_p90:
        (map(.completion.retained_latin_ratio) | percentile(0.9)),
      idle_activity_count: $idle_activity_count,
      maximum_toggle_anchor_drift_px: $maximum_anchor_drift,
      protected_marker_ssim: $marker_ssim,
      browser_chrome_ssim: $browser_chrome_ssim,
      gates: {
        a3_scroll_stability: (
          length == $expected_runs and
          (map(select(.scroll_pass)) | length) == $expected_runs and
          (map(select(.completion_count == 1)) | length) == $expected_runs and
          $idle_activity_count == 0
        ),
        a4_result_freshness: (
          (map(.hidden.motion_to_hidden_ms) | percentile(0.9)) <= 150 and
          (map(.completion.last_motion_to_commit_ms) | percentile(0.9)) <= 2000
        ),
        a7_atomic_and_non_text: (
          (map(select(.presented_count == 1 and .presented.atomic_group == true)) |
            length) == $expected_runs and
          $marker_ssim >= 0.97 and
          $browser_chrome_ssim >= 0.99
        ),
        a8_touch_through: (
          (map(select(.touch_pass)) | length) == $expected_runs
        ),
        a9_source_toggle: ($maximum_anchor_drift <= 16)
      },
      semantic_quality_evaluated: false
    }
    | .automation_pass = (.gates | all(. == true))
' "$results_file" | tee "$output_directory/summary.json"

jq -e '.automation_pass == true' "$output_directory/summary.json" >/dev/null
