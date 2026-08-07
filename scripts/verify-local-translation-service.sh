#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICE_URL="${TRANSLATION_SERVICE_URL:-http://127.0.0.1:8090}"
ARTIFACT_DIR="${REPO_DIR}/translation-service/.artifacts"
FIXTURE_REQUEST_ID="bcb31972-6c09-4f7b-9a0c-220000000011"
REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

mkdir -p "${ARTIFACT_DIR}"

curl --fail --silent --show-error --max-time 10 \
  "${SERVICE_URL}/healthz" | tee "${ARTIFACT_DIR}/health.json" | jq -e \
  '.status == "ok" and .database == true and .model == true' >/dev/null

jq --arg old_id "${FIXTURE_REQUEST_ID}" --arg request_id "${REQUEST_ID}" '
    walk(if type == "string" then gsub($old_id; $request_id) else . end)
  ' "${REPO_DIR}/docs/mock/continuous-scroll-observation-22.v1.request.json" \
  > "${ARTIFACT_DIR}/v1.request.json"

curl --fail --silent --show-error --max-time 60 \
  --request POST \
  --header "Content-Type: application/json; charset=utf-8" \
  --header "Accept: application/json" \
  --header "X-Request-Id: ${REQUEST_ID}" \
  --header "Idempotency-Key: ${REQUEST_ID}" \
  --data-binary "@${ARTIFACT_DIR}/v1.request.json" \
  "${SERVICE_URL}/api/v1/translation-batches" \
  | tee "${ARTIFACT_DIR}/v1.response.json" \
  | jq -e --arg request_id "${REQUEST_ID}" '
      .requestId == $request_id
      and (.results | length) == 4
      and ([.results[].regionId] | unique | length) == 4
      and ([.results[0:3][] | select(.status == "TRANSLATED" and (.translatedText | length) > 0)] | length) == 3
      and .results[3].status == "PRESERVED"
    ' >/dev/null

jq '{
      atlasBrandPreserved: (.results[0].translatedText | contains("Atlas")),
      completionMeaningPresent: (.results[1].translatedText | contains("完成")),
      orderingMeaningPreserved: (
        (.results[2].translatedText | contains("正确"))
        and ((.results[2].translatedText | contains("之后")) or (.results[2].translatedText | contains("建立在")))
        and ((.results[2].translatedText | contains("更重要")) | not)
      ),
      codePreserved: (.results[3].status == "PRESERVED")
    }
    | . + {semanticPass: (.atlasBrandPreserved and .completionMeaningPresent and .orderingMeaningPreserved and .codePreserved)}
  ' "${ARTIFACT_DIR}/v1.response.json" > "${ARTIFACT_DIR}/semantic-quality.json"

curl --fail --silent --show-error --max-time 10 \
  "${SERVICE_URL}/api/v2/translation/capabilities" \
  | tee "${ARTIFACT_DIR}/capabilities.json" \
  | jq -e '
      .schemaVersion == 2
      and .supportsPerRegionLanguage == true
      and (.supportedLanguages | index("zh-Hans")) != null
      and (.supportedLanguages | index("en")) != null
    ' >/dev/null

V2_REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
jq --arg request_id "${V2_REQUEST_ID}" '.requestId = $request_id' \
  "${REPO_DIR}/translation-service/tests/fixtures/v2.request.json" \
  > "${ARTIFACT_DIR}/v2.request.json"

curl --fail --silent --show-error --max-time 60 \
  --request POST \
  --header "Content-Type: application/json; charset=utf-8" \
  --header "X-Request-Id: ${V2_REQUEST_ID}" \
  --header "Idempotency-Key: ${V2_REQUEST_ID}" \
  --data-binary "@${ARTIFACT_DIR}/v2.request.json" \
  "${SERVICE_URL}/api/v2/translation-batches" \
  | tee "${ARTIFACT_DIR}/v2.response.json" \
  | jq -e --arg request_id "${V2_REQUEST_ID}" '
      .schemaVersion == 2
      and .requestId == $request_id
      and .sessionId == "session-local-hy-mt2"
      and .generation == 42
      and .translationRevision == 7
      and .status == "COMPLETED"
      and (.results | length) == 1
      and .results[0].regionId == "track-1042"
      and .results[0].status == "TRANSLATED"
      and (.results[0].translatedText | length) > 0
    ' >/dev/null

jq --slurpfile v2 "${ARTIFACT_DIR}/v2.response.json" '
    . + {
      shortLabelCorrect: ($v2[0].results[0].translatedText == "开始识别")
    }
    | .semanticPass = (.semanticPass and .shortLabelCorrect)
  ' "${ARTIFACT_DIR}/semantic-quality.json" \
  > "${ARTIFACT_DIR}/semantic-quality.updated.json"
mv "${ARTIFACT_DIR}/semantic-quality.updated.json" \
  "${ARTIFACT_DIR}/semantic-quality.json"

echo "Hy-MT2 translation service verification passed."
echo "Artifacts: ${ARTIFACT_DIR}"
if ! jq -e '.semanticPass == true' "${ARTIFACT_DIR}/semantic-quality.json" >/dev/null; then
  echo "Warning: transport passed, but the connected quantized model failed the semantic quality gate." >&2
fi
