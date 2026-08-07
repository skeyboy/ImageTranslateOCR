#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODEL_RUNTIME_DIR="${REPO_DIR}/translation-service/hy-mt2-runtime"
MODEL_PORT="${HY_MT_PORT:-8088}"
MODEL_HEALTH_URL="${HY_MT_MODEL_HEALTH_URL:-http://127.0.0.1:${MODEL_PORT}/health}"
MODEL_LOG_DIR="${HY_MT_MODEL_LOG_DIR:-${MODEL_RUNTIME_DIR}/logs}"
MODEL_LOG_FILE="${MODEL_LOG_DIR}/llama-server.log"
MODEL_START_TIMEOUT_SECONDS="${HY_MT_MODEL_START_TIMEOUT_SECONDS:-120}"
MODEL_PID=""

export HY_MT2_BASE_URL="${HY_MT2_BASE_URL:-http://127.0.0.1:${MODEL_PORT}/v1}"

cleanup() {
  if [[ -n "${MODEL_PID}" ]] && kill -0 "${MODEL_PID}" 2>/dev/null; then
    kill "${MODEL_PID}" 2>/dev/null || true
    wait "${MODEL_PID}" 2>/dev/null || true
  fi
}

trap cleanup EXIT
trap 'exit 130' INT TERM

if ! curl --fail --silent --show-error --max-time 3 "${MODEL_HEALTH_URL}" >/dev/null 2>&1; then
  mkdir -p "${MODEL_LOG_DIR}"
  "${MODEL_RUNTIME_DIR}/start.sh" >"${MODEL_LOG_FILE}" 2>&1 &
  MODEL_PID=$!

  for ((attempt = 1; attempt <= MODEL_START_TIMEOUT_SECONDS; attempt++)); do
    if curl --fail --silent --show-error --max-time 3 "${MODEL_HEALTH_URL}" >/dev/null 2>&1; then
      break
    fi
    if ! kill -0 "${MODEL_PID}" 2>/dev/null; then
      echo "Hy-MT2 model service exited during startup:" >&2
      tail -80 "${MODEL_LOG_FILE}" >&2 || true
      exit 1
    fi
    sleep 1
  done
fi

if ! curl --fail --silent --show-error --max-time 3 "${MODEL_HEALTH_URL}" >/dev/null; then
  echo "Hy-MT2 model service did not become ready in time." >&2
  tail -80 "${MODEL_LOG_FILE}" >&2 || true
  exit 1
fi

echo "Hy-MT2 model is ready: ${MODEL_HEALTH_URL}"
"${SCRIPT_DIR}/run-local-translation-service.sh"
