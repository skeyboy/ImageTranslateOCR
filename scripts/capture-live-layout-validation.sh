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

adb_command=(adb)
if [[ -n "$serial" ]]; then
    adb_command+=( -s "$serial" )
fi

mkdir -p "$output_directory"
"${adb_command[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null
"${adb_command[@]}" shell am force-stop com.example.imagetranslate
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
"${adb_command[@]}" shell input tap 720 496
sleep 2
"${adb_command[@]}" shell am start \
    -n com.android.chrome/com.google.android.apps.chrome.Main >/dev/null
sleep 2
"${adb_command[@]}" exec-out screencap -p > "$output_directory/source.png"
"${adb_command[@]}" shell uiautomator dump /sdcard/layout-source.xml >/dev/null
"${adb_command[@]}" pull /sdcard/layout-source.xml "$output_directory/source.xml" >/dev/null

"${adb_command[@]}" shell run-as com.example.imagetranslate \
    ls cache/translation-request-archives | sort > "$output_directory/archive-before.txt"
"${adb_command[@]}" logcat -c
"${adb_command[@]}" shell input tap 915 2860
sleep 2
"${adb_command[@]}" shell uiautomator dump /sdcard/layout-permission.xml >/dev/null || true
if "${adb_command[@]}" shell cat /sdcard/layout-permission.xml 2>/dev/null | \
    rg -q '共享屏幕|Share screen|Start now'; then
    "${adb_command[@]}" shell input tap 1015 2910
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
    jq -n '{
        schemaVersion: 1,
        event: "live_layout_validation",
        status: "INCONCLUSIVE",
        failureCode: "TRANSLATION_TIMEOUT",
        errorCount: 0,
        blockedCount: 1,
        warningCount: 0,
        validatedBlockCount: 0,
        findings: [{
            code: "TRANSLATION_TIMEOUT",
            severity: "BLOCKED",
            message: "No presented translation was available for layout validation"
        }]
    }' > "$output_directory/layout-validation.json"
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
