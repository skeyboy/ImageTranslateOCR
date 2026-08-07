#!/usr/bin/env bash
set -euo pipefail

CACHE_ROOT="${XDG_CACHE_HOME:-${HOME}/Library/Caches}"
VENV_DIR="${PADDLE_OCR_VENV_DIR:-${CACHE_ROOT}/ImageTranslateOCR/paddleocr-venv}"
SERVICE_HOST="${PADDLE_OCR_HOST:-127.0.0.1}"
SERVICE_PORT="${PADDLE_OCR_PORT:-8081}"
DEVICE="${PADDLE_OCR_DEVICE:-cpu}"
PIPELINE="${PADDLE_OCR_PIPELINE:-OCR}"

if [[ ! -x "${VENV_DIR}/bin/paddlex" ]]; then
  echo "PaddleOCR environment is missing: ${VENV_DIR}" >&2
  echo "Run scripts/setup-local-paddle-ocr-service.sh first." >&2
  exit 1
fi

if curl --fail --silent --show-error --max-time 3 \
  "http://${SERVICE_HOST}:${SERVICE_PORT}/health" >/dev/null 2>&1; then
  echo "PaddleOCR service is already healthy: http://${SERVICE_HOST}:${SERVICE_PORT}"
  exit 0
fi

export PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK="${PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK:-True}"

echo "Starting PaddleOCR ${PIPELINE} on http://${SERVICE_HOST}:${SERVICE_PORT}"
exec "${VENV_DIR}/bin/paddlex" \
  --serve \
  --pipeline "${PIPELINE}" \
  --device "${DEVICE}" \
  --host "${SERVICE_HOST}" \
  --port "${SERVICE_PORT}"
