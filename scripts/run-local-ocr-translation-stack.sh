#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PADDLE_HOST="${PADDLE_OCR_HOST:-127.0.0.1}"
PADDLE_PORT="${PADDLE_OCR_PORT:-8081}"
PADDLE_HEALTH_URL="http://${PADDLE_HOST}:${PADDLE_PORT}/health"
LOG_DIR="${REPO_DIR}/translation-service/.artifacts"
PADDLE_LOG_FILE="${LOG_DIR}/paddle-ocr-service.log"
START_TIMEOUT_SECONDS="${PADDLE_OCR_START_TIMEOUT_SECONDS:-180}"
PADDLE_PID=""
STACK_PID=""

cleanup() {
  trap - EXIT INT TERM
  if [[ -n "${STACK_PID}" ]] && kill -0 "${STACK_PID}" 2>/dev/null; then
    kill "${STACK_PID}" 2>/dev/null || true
    wait "${STACK_PID}" 2>/dev/null || true
  fi
  if [[ -n "${PADDLE_PID}" ]] && kill -0 "${PADDLE_PID}" 2>/dev/null; then
    kill "${PADDLE_PID}" 2>/dev/null || true
    wait "${PADDLE_PID}" 2>/dev/null || true
  fi
}

trap cleanup EXIT
trap 'exit 130' INT TERM

mkdir -p "${LOG_DIR}"
if ! curl --fail --silent --show-error --max-time 3 "${PADDLE_HEALTH_URL}" >/dev/null 2>&1; then
  "${SCRIPT_DIR}/run-local-paddle-ocr-service.sh" >"${PADDLE_LOG_FILE}" 2>&1 &
  PADDLE_PID=$!
  for ((attempt = 1; attempt <= START_TIMEOUT_SECONDS; attempt++)); do
    if curl --fail --silent --show-error --max-time 3 "${PADDLE_HEALTH_URL}" >/dev/null 2>&1; then
      break
    fi
    if ! kill -0 "${PADDLE_PID}" 2>/dev/null; then
      echo "PaddleOCR service exited during startup:" >&2
      tail -80 "${PADDLE_LOG_FILE}" >&2 || true
      exit 1
    fi
    sleep 1
  done
fi

if ! curl --fail --silent --show-error --max-time 3 "${PADDLE_HEALTH_URL}" >/dev/null; then
  echo "PaddleOCR service did not become ready in time." >&2
  tail -80 "${PADDLE_LOG_FILE}" >&2 || true
  exit 1
fi

export PADDLE_OCR_BASE_URL="${PADDLE_OCR_BASE_URL:-http://${PADDLE_HOST}:${PADDLE_PORT}}"
echo "PaddleOCR is ready: ${PADDLE_HEALTH_URL}"
"${SCRIPT_DIR}/run-local-translation-stack.sh" &
STACK_PID=$!
wait "${STACK_PID}"
