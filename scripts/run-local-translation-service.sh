#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICE_DIR="${REPO_DIR}/translation-service"
SERVICE_DATABASE_NAME="${TRANSLATION_DATABASE_NAME:-image_translate_ocr}"

if ! pg_isready >/dev/null 2>&1; then
  echo "PostgreSQL is not accepting connections on the configured local socket." >&2
  exit 1
fi

if ! psql -Atqc "SELECT 1 FROM pg_database WHERE datname = '${SERVICE_DATABASE_NAME}'" postgres \
  | grep -qx 1; then
  createdb "${SERVICE_DATABASE_NAME}"
fi

export DATABASE_URL="${DATABASE_URL:-postgresql://localhost/${SERVICE_DATABASE_NAME}}"
export BIND_ADDR="${BIND_ADDR:-0.0.0.0:8090}"
export HY_MT2_BASE_URL="${HY_MT2_BASE_URL:-http://127.0.0.1:8088/v1}"

curl --fail --silent --show-error --max-time 5 \
  "${HY_MT2_BASE_URL%/v1}/health" >/dev/null || {
  echo "Hy-MT2 is unavailable. Start the complete local stack with:" >&2
  echo "  scripts/run-local-translation-stack.sh" >&2
  exit 1
}

cd "${SERVICE_DIR}"
exec cargo run --release
