#!/usr/bin/env bash
set -euo pipefail

PYTHON_BIN="${PADDLE_OCR_PYTHON_BIN:-/usr/bin/python3}"
CACHE_ROOT="${XDG_CACHE_HOME:-${HOME}/Library/Caches}"
VENV_DIR="${PADDLE_OCR_VENV_DIR:-${CACHE_ROOT}/ImageTranslateOCR/paddleocr-venv}"
PADDLEPADDLE_VERSION="${PADDLEPADDLE_VERSION:-3.2.1}"
PADDLEOCR_VERSION="${PADDLEOCR_VERSION:-3.7.0}"
PADDLEX_VERSION="${PADDLEX_VERSION:-3.7.2}"

"${PYTHON_BIN}" -c '
import sys
if sys.version_info < (3, 8):
    raise SystemExit("PaddleOCR requires Python 3.8 or newer")
'

mkdir -p "$(dirname "${VENV_DIR}")"
if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  "${PYTHON_BIN}" -m venv "${VENV_DIR}"
fi

"${VENV_DIR}/bin/python" -m pip install --upgrade pip setuptools wheel
"${VENV_DIR}/bin/python" -m pip install \
  "paddlepaddle==${PADDLEPADDLE_VERSION}" \
  "paddleocr==${PADDLEOCR_VERSION}" \
  "paddlex[ocr-core]==${PADDLEX_VERSION}"
"${VENV_DIR}/bin/paddlex" --install serving -y

"${VENV_DIR}/bin/python" -c '
import paddle
import paddleocr
import paddlex
print(f"paddle={paddle.__version__} device={paddle.device.get_device()}")
print(f"paddleocr={paddleocr.__version__} paddlex={paddlex.__version__}")
'

echo "PaddleOCR local serving environment is ready: ${VENV_DIR}"
echo "Start it with: scripts/run-local-paddle-ocr-service.sh"
