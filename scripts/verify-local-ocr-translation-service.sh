#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICE_URL="${TRANSLATION_SERVICE_URL:-http://127.0.0.1:8090}"
IMAGE_PATH="${1:-${REPO_DIR}/docs/assets/2026-07-21-control-source.png}"
ARTIFACT_DIR="${REPO_DIR}/translation-service/.artifacts"
REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
SESSION_ID="local-ocr-translation-verification"
BASE64_FILE="$(mktemp)"

cleanup() {
  rm -f "${BASE64_FILE}"
}
trap cleanup EXIT

if [[ ! -f "${IMAGE_PATH}" ]]; then
  echo "OCR verification image does not exist: ${IMAGE_PATH}" >&2
  exit 1
fi

if command -v sips >/dev/null 2>&1; then
  IMAGE_WIDTH="$(sips -g pixelWidth "${IMAGE_PATH}" | awk '/pixelWidth/ {print $2}')"
  IMAGE_HEIGHT="$(sips -g pixelHeight "${IMAGE_PATH}" | awk '/pixelHeight/ {print $2}')"
elif command -v identify >/dev/null 2>&1; then
  read -r IMAGE_WIDTH IMAGE_HEIGHT < <(identify -format '%w %h' "${IMAGE_PATH}")
else
  echo "Image dimensions require sips or ImageMagick identify." >&2
  exit 1
fi

case "${IMAGE_PATH##*.}" in
  png|PNG) MEDIA_TYPE="image/png" ;;
  jpg|JPG|jpeg|JPEG) MEDIA_TYPE="image/jpeg" ;;
  webp|WEBP) MEDIA_TYPE="image/webp" ;;
  *) echo "Verification supports PNG, JPEG, or WebP images." >&2; exit 1 ;;
esac

mkdir -p "${ARTIFACT_DIR}"
base64 <"${IMAGE_PATH}" | tr -d '\r\n' >"${BASE64_FILE}"
jq --null-input \
  --rawfile image_data "${BASE64_FILE}" \
  --arg request_id "${REQUEST_ID}" \
  --arg session_id "${SESSION_ID}" \
  --arg media_type "${MEDIA_TYPE}" \
  --argjson width "${IMAGE_WIDTH}" \
  --argjson height "${IMAGE_HEIGHT}" \
  '{
    schemaVersion: 1,
    requestId: $request_id,
    sessionId: $session_id,
    generation: 1,
    image: {
      data: $image_data,
      mediaType: $media_type,
      width: $width,
      height: $height
    },
    translation: {
      mode: "AUTO_BIDIRECTIONAL",
      preserveIdentifiers: true
    },
    ocr: {
      textDetLimitSideLen: 1280,
      textRecScoreThresh: 0.35
    }
  }' >"${ARTIFACT_DIR}/ocr-translation.request.json"

CURL_ARGS=(
  --fail
  --silent
  --show-error
  --max-time 90
  --request POST
  --header "Content-Type: application/json; charset=utf-8"
  --header "Accept: application/json"
  --header "X-Request-Id: ${REQUEST_ID}"
  --header "Idempotency-Key: ${REQUEST_ID}"
)
if [[ -n "${API_BEARER_TOKEN:-}" ]]; then
  CURL_ARGS+=(--header "Authorization: Bearer ${API_BEARER_TOKEN}")
fi

curl "${CURL_ARGS[@]}" \
  --data-binary "@${ARTIFACT_DIR}/ocr-translation.request.json" \
  "${SERVICE_URL}/api/v1/ocr-translations" \
  | tee "${ARTIFACT_DIR}/ocr-translation.response.json" \
  | jq -e \
      --arg request_id "${REQUEST_ID}" \
      --arg session_id "${SESSION_ID}" \
      --argjson width "${IMAGE_WIDTH}" \
      --argjson height "${IMAGE_HEIGHT}" '
        .schemaVersion == 1
        and .requestId == $request_id
        and .sessionId == $session_id
        and .generation == 1
        and .sourceWidth == $width
        and .sourceHeight == $height
        and .recognizedCount > 0
        and (.regions | length) > 0
        and ([.regions[].status] | all(. == "TRANSLATED" or . == "PRESERVED" or . == "FAILED"))
        and .timing.ocrMs >= 0
        and .timing.translationMs >= 0
        and .timing.totalMs >= .timing.ocrMs
      ' >/dev/null

echo "PaddleOCR + Hy-MT2 integrated service verification passed."
jq '{status, recognizedCount, returnedRegionCount: (.regions | length), timing}' \
  "${ARTIFACT_DIR}/ocr-translation.response.json"
