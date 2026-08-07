#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICE_URL="${PNUTS_TRANSLATION_SERVICE_URL:-https://api-dev.pnutsai.com}"
ARTIFACT_DIR="${REPO_DIR}/docs/validation/pnuts-semantic-grouping-2026-08-05"
LINE_REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
GROUP_REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

mkdir -p "${ARTIFACT_DIR}"

jq -n --arg request_id "${LINE_REQUEST_ID}" '{
  schemaVersion: 1,
  requestId: $request_id,
  generation: 0,
  scene: "SEMANTIC_GROUPING_AB_LINE",
  translation: {
    mode: "ENGLISH_TO_CHINESE",
    sourceLanguage: "en",
    targetLanguage: "zh",
    preserveIdentifiers: true,
    useContext: true
  },
  regions: [
    {regionId: "line-1", text: "The Web3 industry tried, and we did", sourceLanguage: "en", targetLanguage: "zh"},
    {regionId: "line-2", text: "quite well. Until the country decided", sourceLanguage: "en", targetLanguage: "zh"},
    {regionId: "line-3", text: "to invest in projects that fit neither", sourceLanguage: "en", targetLanguage: "zh"},
    {regionId: "line-4", text: "the country nor the Web3 community.", sourceLanguage: "en", targetLanguage: "zh"},
    {regionId: "line-5", text: "Then all hell broke loose. The rest", sourceLanguage: "en", targetLanguage: "zh"},
    {regionId: "line-6", text: "is history.", sourceLanguage: "en", targetLanguage: "zh"}
  ]
}' > "${ARTIFACT_DIR}/line.request.json"

jq -n --arg request_id "${GROUP_REQUEST_ID}" '{
  schemaVersion: 1,
  requestId: $request_id,
  generation: 0,
  scene: "SEMANTIC_GROUPING_AB_GROUP",
  translation: {
    mode: "ENGLISH_TO_CHINESE",
    sourceLanguage: "en",
    targetLanguage: "zh",
    preserveIdentifiers: true,
    useContext: false
  },
  regions: [
    {
      regionId: "semantic-group-1",
      text: "The Web3 industry tried, and we did quite well. Until the country decided to invest in projects that fit neither the country nor the Web3 community. Then all hell broke loose. The rest is history.",
      sourceLanguage: "en",
      targetLanguage: "zh"
    }
  ]
}' > "${ARTIFACT_DIR}/group.request.json"

LINE_TIME_FILE="${ARTIFACT_DIR}/line.time"
GROUP_TIME_FILE="${ARTIFACT_DIR}/group.time"

curl --fail --silent --show-error --max-time 60 \
  --request POST \
  --header "Content-Type: application/json; charset=utf-8" \
  --header "Accept: application/json" \
  --header "X-Request-Id: ${LINE_REQUEST_ID}" \
  --header "Idempotency-Key: ${LINE_REQUEST_ID}" \
  --data-binary "@${ARTIFACT_DIR}/line.request.json" \
  --output "${ARTIFACT_DIR}/line.response.json" \
  --write-out '%{time_total}' \
  "${SERVICE_URL}/api/v1/translate/regions" > "${LINE_TIME_FILE}"

curl --fail --silent --show-error --max-time 60 \
  --request POST \
  --header "Content-Type: application/json; charset=utf-8" \
  --header "Accept: application/json" \
  --header "X-Request-Id: ${GROUP_REQUEST_ID}" \
  --header "Idempotency-Key: ${GROUP_REQUEST_ID}" \
  --data-binary "@${ARTIFACT_DIR}/group.request.json" \
  --output "${ARTIFACT_DIR}/group.response.json" \
  --write-out '%{time_total}' \
  "${SERVICE_URL}/api/v1/translate/regions" > "${GROUP_TIME_FILE}"

jq -e --arg request_id "${LINE_REQUEST_ID}" '
  .requestId == $request_id
  and (.results | length) == 6
  and ([.results[] | select(.status == "TRANSLATED" or .status == "PRESERVED" or .status == "FAILED")] | length) == 6
' "${ARTIFACT_DIR}/line.response.json" >/dev/null

jq -e --arg request_id "${GROUP_REQUEST_ID}" '
  .requestId == $request_id
  and (.results | length) == 1
  and .results[0].status == "TRANSLATED"
  and (.results[0].translatedText | length) > 0
' "${ARTIFACT_DIR}/group.response.json" >/dev/null

jq -n \
  --arg endpoint "${SERVICE_URL}/api/v1/translate/regions" \
  --argjson line_request "$(jq -c . "${ARTIFACT_DIR}/line.request.json")" \
  --argjson line_response "$(jq -c . "${ARTIFACT_DIR}/line.response.json")" \
  --argjson group_request "$(jq -c . "${ARTIFACT_DIR}/group.request.json")" \
  --argjson group_response "$(jq -c . "${ARTIFACT_DIR}/group.response.json")" \
  --arg line_seconds "$(<"${LINE_TIME_FILE}")" \
  --arg group_seconds "$(<"${GROUP_TIME_FILE}")" '
  {
    schemaVersion: 1,
    endpoint: $endpoint,
    sourceLines: [$line_request.regions[].text],
    lineMode: {
      regionCount: ($line_request.regions | length),
      elapsedMs: (($line_seconds | tonumber) * 1000 | round),
      successCount: ([$line_response.results[] | select(.status == "TRANSLATED")] | length),
      failedCount: ([$line_response.results[] | select(.status == "FAILED")] | length),
      results: [
        $line_request.regions[] as $source
        | $line_response.results[]
        | select(.regionId == $source.regionId)
        | {
            regionId,
            sourceText: $source.text,
            status,
            translatedText: (.translatedText // null)
          }
      ],
      stitchedTranslation: (
        [$line_response.results[] | select(.status == "TRANSLATED") | .translatedText]
        | if length == 0 then null else join(" ") end
      )
    },
    semanticGroupMode: {
      regionCount: ($group_request.regions | length),
      elapsedMs: (($group_seconds | tonumber) * 1000 | round),
      translation: $group_response.results[0].translatedText
    },
    automaticChecks: {
      allLineResultsReturned: (($line_response.results | length) == 6),
      groupTranslated: ($group_response.results[0].status == "TRANSLATED"),
      groupPreservesWeb3: ($group_response.results[0].translatedText | contains("Web3")),
      groupMentionsCountry: ($group_response.results[0].translatedText | contains("国家")),
      groupMentionsHistory: ($group_response.results[0].translatedText | contains("历史"))
    }
  }
' > "${ARTIFACT_DIR}/summary.json"

rm -f "${LINE_TIME_FILE}" "${GROUP_TIME_FILE}"

echo "Pnuts semantic grouping A/B completed."
echo "Artifacts: ${ARTIFACT_DIR}"
jq '{lineMode, semanticGroupMode, automaticChecks}' "${ARTIFACT_DIR}/summary.json"
