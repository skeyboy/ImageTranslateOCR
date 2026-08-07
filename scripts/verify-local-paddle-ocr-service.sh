#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICE_URL="${PADDLE_OCR_SERVICE_URL:-http://127.0.0.1:8081}"
IMAGE_PATH="${1:-${REPO_DIR}/docs/assets/2026-07-21-control-source.png}"
OUTPUT_PATH="${2:-}"
REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

for command_name in base64 curl jq mktemp; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Required command is missing: ${command_name}" >&2
    exit 1
  fi
done

if [[ ! -f "${IMAGE_PATH}" ]]; then
  echo "Image does not exist: ${IMAGE_PATH}" >&2
  exit 1
fi

curl --fail --silent --show-error --max-time 5 \
  "${SERVICE_URL}/health" \
  | jq -e '.errorCode == 0 and .errorMsg == "Healthy"' >/dev/null

METRICS_FILE="$(mktemp -t paddleocr-metrics.XXXXXX)"
if [[ -n "${OUTPUT_PATH}" ]]; then
  RESPONSE_FILE="${OUTPUT_PATH}"
  mkdir -p "$(dirname "${RESPONSE_FILE}")"
  REMOVE_RESPONSE=false
else
  RESPONSE_FILE="$(mktemp -t paddleocr-response.XXXXXX)"
  REMOVE_RESPONSE=true
fi

cleanup() {
  rm -f "${METRICS_FILE}"
  if [[ "${REMOVE_RESPONSE}" == true ]]; then
    rm -f "${RESPONSE_FILE}"
  fi
}
trap cleanup EXIT

base64 < "${IMAGE_PATH}" \
  | tr -d '\n' \
  | jq -Rs --arg log_id "${REQUEST_ID}" '{
      file: .,
      fileType: 1,
      useDocOrientationClassify: false,
      useDocUnwarping: false,
      useTextlineOrientation: false,
      textDetLimitSideLen: 1280,
      textDetLimitType: "max",
      returnWordBox: false,
      visualize: false,
      logId: $log_id
    }' \
  | curl --fail-with-body --silent --show-error --max-time 180 \
      --request POST \
      --header "Content-Type: application/json" \
      --data-binary @- \
      --output "${RESPONSE_FILE}" \
      --write-out 'http=%{http_code}\ntime_total=%{time_total}\nsize_upload=%{size_upload}\nsize_download=%{size_download}\n' \
      "${SERVICE_URL}/ocr" > "${METRICS_FILE}"

jq -e --arg request_id "${REQUEST_ID}" '
    .errorCode == 0
    and .errorMsg == "Success"
    and .logId == $request_id
    and (.result.ocrResults | length) > 0
  ' "${RESPONSE_FILE}" >/dev/null

cat "${METRICS_FILE}"
jq '{
      image: .result.dataInfo,
      textCount: (.result.ocrResults[0].prunedResult.rec_texts | length),
      texts: .result.ocrResults[0].prunedResult.rec_texts,
      scores: .result.ocrResults[0].prunedResult.rec_scores
    }' "${RESPONSE_FILE}"

if [[ -n "${OUTPUT_PATH}" ]]; then
  echo "Response: ${RESPONSE_FILE}"
fi
