#!/usr/bin/env bash
set -euo pipefail

url="${LAYOUT_URL:?LAYOUT_URL is required}"
output_directory="${LAYOUT_OUTPUT_DIR:?LAYOUT_OUTPUT_DIR is required}"
serial="${ANDROID_SERIAL:-}"
page_package="${LAYOUT_PAGE_PACKAGE:-com.android.chrome}"
page_load_seconds="${LAYOUT_PAGE_LOAD_SECONDS:-5}"
translation_timeout_seconds="${LAYOUT_TRANSLATION_TIMEOUT_SECONDS:-45}"
scroll_swipe="${LAYOUT_SCROLL_SWIPE:-}"
expectation="${LAYOUT_EXPECTATION:-}"
python_command="${LAYOUT_PYTHON:-python3}"
auto_enable_accessibility="${LAYOUT_AUTO_ENABLE_ACCESSIBILITY:-false}"
require_browser_dom="${LAYOUT_REQUIRE_BROWSER_DOM:-false}"
accessibility_component="com.example.imagetranslate/com.example.imagetranslate.screenshot.ScreenTranslationAccessibilityService"

adb_command=(adb)
if [[ -n "$serial" ]]; then
    adb_command+=( -s "$serial" )
fi

temporary_directory=$(mktemp -d)
trap 'find "$temporary_directory" -type f -delete 2>/dev/null || true; rmdir "$temporary_directory" 2>/dev/null || true' EXIT

dump_ui() {
    local remote_name="$1"
    local local_name="$2"
    "${adb_command[@]}" shell uiautomator dump "/sdcard/$remote_name" >/dev/null
    "${adb_command[@]}" pull "/sdcard/$remote_name" "$local_name" >/dev/null
}

ui_node_center() {
    local xml_file="$1"
    local mode="$2"
    local value="$3"
    "$python_command" - "$xml_file" "$mode" "$value" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml_file, mode, value = sys.argv[1:]
for node in ET.parse(xml_file).iter("node"):
    attributes = node.attrib
    matches = (
        mode == "resource-suffix" and attributes.get("resource-id", "").endswith(value)
        or mode == "clickable-text" and attributes.get("clickable") == "true"
        and re.search(value, attributes.get("text", "") + attributes.get("content-desc", ""))
        or mode == "content-description"
        and re.search(value, attributes.get("content-desc", ""))
    )
    bounds = re.fullmatch(r"\[(\d+),(\d+)]\[(\d+),(\d+)]", attributes.get("bounds", ""))
    if matches and bounds:
        left, top, right, bottom = map(int, bounds.groups())
        print((left + right) // 2, (top + bottom) // 2)
        break
PY
}

tap_coordinates() {
    local coordinates="$1"
    [[ -n "$coordinates" ]] || return 1
    local x y
    read -r x y <<< "$coordinates"
    "${adb_command[@]}" shell input tap "$x" "$y"
}

overlay_controls_geometry() {
    "${adb_command[@]}" shell dumpsys window windows | "$python_command" -c '
import re
import sys
matches = []
for line in sys.stdin:
    if "pkg = com.example.imagetranslate" not in line or "ty=APPLICATION_OVERLAY" not in line:
        continue
    match = re.search(r"\{\((-?\d+),(-?\d+)\)\((\d+)x(\d+)\)", line)
    if match:
        values = tuple(map(int, match.groups()))
        if values[2] < 1440 or values[3] < 3200:
            matches.append(values)
if matches:
    print(*max(matches, key=lambda item: item[2] * item[3]))
'
}

write_inconclusive() {
    local failure_code="$1"
    local message="$2"
    jq -n --arg code "$failure_code" --arg message "$message" '{
        schemaVersion: 2,
        event: "live_layout_validation",
        status: "INCONCLUSIVE",
        failureCode: $code,
        errorCount: 0,
        blockedCount: 1,
        warningCount: 0,
        validatedBlockCount: 0,
        findings: [{code: $code, severity: "BLOCKED", message: $message}]
    }' > "$output_directory/layout-validation.json"
}

mkdir -p "$output_directory"
"${adb_command[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null
"${adb_command[@]}" shell am force-stop com.example.imagetranslate
"${adb_command[@]}" shell am force-stop "$page_package"
"${adb_command[@]}" shell am start -a android.intent.action.VIEW -d "$url" \
    -p "$page_package" >/dev/null
sleep "$page_load_seconds"
if [[ -n "$scroll_swipe" ]]; then
    read -r x1 y1 x2 y2 duration <<< "$scroll_swipe"
    "${adb_command[@]}" shell input swipe "$x1" "$y1" "$x2" "$y2" "$duration"
    sleep 2
fi

"${adb_command[@]}" exec-out screencap -p > "$output_directory/positioned.png"

"${adb_command[@]}" shell am start \
    -n com.example.imagetranslate/.ui.ImageTranslateActivity >/dev/null
sleep 2
if [[ "$auto_enable_accessibility" == true ]]; then
    "${adb_command[@]}" shell settings put secure enabled_accessibility_services \
        "$accessibility_component"
    "${adb_command[@]}" shell settings put secure accessibility_enabled 1
    sleep 1
fi
app_ui="$temporary_directory/app-ui.xml"
dump_ui layout-app-controls.xml "$app_ui"
collapse_settings=$(ui_node_center "$app_ui" content-description '收起翻译设置|Collapse translation settings')
if [[ -n "$collapse_settings" ]]; then
    tap_coordinates "$collapse_settings"
    sleep 1
    dump_ui layout-app-controls-collapsed.xml "$app_ui"
fi
capture_entry=$(ui_node_center "$app_ui" resource-suffix 'btnCaptureScreenshot')
if ! tap_coordinates "$capture_entry"; then
    write_inconclusive "CAPTURE_ENTRY_NOT_FOUND" \
        "The app capture entry could not be resolved from the UI hierarchy"
    exit 2
fi
sleep 2
"${adb_command[@]}" shell am start \
    -n com.android.chrome/com.google.android.apps.chrome.Main >/dev/null
chrome_foreground=false
for _ in $(seq 1 10); do
    if "${adb_command[@]}" shell dumpsys activity activities | \
        rg -q '(?:topResumedActivity|ResumedActivity|mResumedActivity).*com\.android\.chrome'; then
        chrome_foreground=true
        break
    fi
    sleep 1
done
if [[ "$chrome_foreground" != true ]]; then
    write_inconclusive "CHROME_NOT_FOREGROUND" \
        "System Chrome did not become the resumed activity"
    exit 2
fi
sleep 1
"${adb_command[@]}" exec-out screencap -p > "$output_directory/source.png"
dump_ui layout-source.xml "$output_directory/source.xml"
if ! rg -q 'package="com\.android\.chrome"' "$output_directory/source.xml"; then
    write_inconclusive "SOURCE_UI_NOT_CHROME" \
        "The source UI hierarchy was not captured from system Chrome"
    exit 2
fi
if [[ "$require_browser_dom" == true ]] && \
    ! rg -q 'class="android\.webkit\.WebView"' "$output_directory/source.xml"; then
    write_inconclusive "BROWSER_DOM_UNAVAILABLE" \
        "Chrome did not expose WebView text; strict DOM-to-OCR validation cannot run"
    exit 2
fi

"${adb_command[@]}" shell run-as com.example.imagetranslate \
    ls cache/translation-request-archives | sort > "$output_directory/archive-before.txt"
"${adb_command[@]}" logcat -c
controls_geometry=$(overlay_controls_geometry)
if [[ -z "$controls_geometry" ]]; then
    write_inconclusive "CAPTURE_OVERLAY_NOT_FOUND" \
        "The active capture overlay window was not found"
    exit 2
fi
read -r overlay_x overlay_y overlay_width overlay_height <<< "$controls_geometry"
if (( overlay_width < 600 )); then
    "${adb_command[@]}" shell input tap \
        "$((overlay_x + overlay_width / 2))" "$((overlay_y + overlay_height / 2))"
    sleep 1
    controls_geometry=$(overlay_controls_geometry)
    read -r overlay_x overlay_y overlay_width overlay_height <<< "$controls_geometry"
fi
density=$("${adb_command[@]}" shell wm density | awk '/Physical density:/ {print $3; exit}')
density=${density:-560}
capture_x=$((overlay_x + overlay_width * 647 / 1000))
capture_y=$((overlay_y + density * 28 / 160))
"${adb_command[@]}" shell input tap "$capture_x" "$capture_y"
sleep 2
permission_ui="$temporary_directory/permission-ui.xml"
if dump_ui layout-permission.xml "$permission_ui" 2>/dev/null; then
    permission_action=$(ui_node_center \
        "$permission_ui" clickable-text '共享屏幕|Share screen|Start now|立即开始')
    if [[ -n "$permission_action" ]]; then
        tap_coordinates "$permission_action"
    fi
fi

presented=false
for _ in $(seq 1 "$translation_timeout_seconds"); do
    if "${adb_command[@]}" logcat -d -v brief LiveOcrMetrics:I '*:S' | \
        rg -q 'overlay_translation_presented'; then
        presented=true
        break
    fi
    sleep 1
done
if [[ "$presented" != true ]]; then
    "${adb_command[@]}" exec-out screencap -p > "$output_directory/timeout-screen.png"
    "${adb_command[@]}" logcat -d -v threadtime > "$output_directory/logcat.txt"
    if rg -q 'OCR recognition succeeded' "$output_directory/logcat.txt"; then
        timeout_code="REMOTE_TRANSLATION_TIMEOUT"
        timeout_message="OCR completed but remote translation did not finish before the deadline"
    elif rg -q 'Overlay translation started' "$output_directory/logcat.txt"; then
        timeout_code="OCR_TIMEOUT"
        timeout_message="Capture started but OCR did not finish before the deadline"
    elif rg -q 'ImageTranslateScreenCaptureSession' "$output_directory/logcat.txt"; then
        timeout_code="CAPTURE_TIMEOUT"
        timeout_message="Screen projection started but no OCR pipeline execution was observed"
    else
        timeout_code="CAPTURE_TRIGGER_NOT_STARTED"
        timeout_message="The capture action did not start the screen projection pipeline"
    fi
    write_inconclusive "$timeout_code" "$timeout_message"
    echo "Timed out waiting for overlay_translation_presented" >&2
    exit 2
fi

sleep 1
"${adb_command[@]}" exec-out screencap -p > "$output_directory/translated.png"
"${adb_command[@]}" logcat -d -v threadtime > "$output_directory/logcat.txt"
"${adb_command[@]}" shell run-as com.example.imagetranslate \
    ls cache/translation-request-archives | sort > "$output_directory/archive-after.txt"
request_id=$(comm -13 "$output_directory/archive-before.txt" \
    "$output_directory/archive-after.txt" | tail -n 1)
if [[ -z "$request_id" ]]; then
    sleep 2
    "${adb_command[@]}" shell run-as com.example.imagetranslate \
        ls cache/translation-request-archives | sort > "$output_directory/archive-after.txt"
    request_id=$(comm -13 "$output_directory/archive-before.txt" \
        "$output_directory/archive-after.txt" | tail -n 1)
fi
if [[ -z "$request_id" ]]; then
    echo "No new translation request archive was found" >&2
    exit 1
fi
printf '%s\n' "$request_id" > "$output_directory/request-id.txt"
"${adb_command[@]}" exec-out run-as com.example.imagetranslate tar -cf - \
    "cache/translation-request-archives/$request_id" | tar -xf - -C "$output_directory"
archive="$output_directory/cache/translation-request-archives/$request_id"

validator=(
    "$python_command" scripts/live_layout_validation.py
    --archive "$archive"
    --ui-xml "$output_directory/source.xml"
    --output "$output_directory/layout-validation.json"
)
if [[ -n "$expectation" ]]; then
    validator+=( --expectation "$expectation" )
fi
"${validator[@]}"
