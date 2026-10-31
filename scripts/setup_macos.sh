#!/usr/bin/env bash
# ==============================================================================
# Theia Model & Engine Setup for macOS
# Fetches the Needle engine (android-arm64) and Whistle + Needle 3 models.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

echo "==> Setting up Theia on macOS..."

# Check Python 3
if ! command -v python3 >/dev/null 2>&1; then
    echo "Error: Python 3 is required. Install it via Homebrew ('brew install python3') or Xcode Command Line Tools ('xcode-select --install')." >&2
    exit 1
fi

# Set up or use virtual environment in project root
VENV_DIR="${ROOT_DIR}/.venv"
if command -v needle >/dev/null 2>&1; then
    NEEDLE="needle"
    echo "Found system needle CLI"
elif [ -x "${VENV_DIR}/bin/needle" ]; then
    NEEDLE="${VENV_DIR}/bin/needle"
    echo "Found needle CLI in ${VENV_DIR}"
else
    echo "Creating virtual environment in ${VENV_DIR}..."
    python3 -m venv "${VENV_DIR}"
    "${VENV_DIR}/bin/pip" install --upgrade cactus-needle
    NEEDLE="${VENV_DIR}/bin/needle"
fi

# Download models and engine
echo "==> Fetching engine and models via needle..."
FETCH_DIR="${ROOT_DIR}/.fetch"
rm -rf "${FETCH_DIR}" && mkdir -p "${FETCH_DIR}"
cd "${FETCH_DIR}"

"${NEEDLE}" download android-arm64
"${NEEDLE}" download whistle
"${NEEDLE}" download needle3

cd "${ROOT_DIR}"

# Locate artifacts
LIB=$(find "${FETCH_DIR}" -path '*android-arm64*' -name libneedle.a | head -1)
HDR=$(find "${FETCH_DIR}" -path '*android-arm64*' -name needle.h | head -1)
W=$(find "${FETCH_DIR}" "$HOME/.cache" -name whistle.cact 2>/dev/null | head -1)
N=$(find "${FETCH_DIR}" "$HOME/.cache" -name needle3.cact 2>/dev/null | head -1)

for v in LIB HDR W N; do
    if [ -z "${!v:-}" ]; then
        echo "Error: Missing required artifact: $v" >&2
        exit 1
    fi
done

# Copy artifacts to app directories
mkdir -p "${ROOT_DIR}/app/src/main/cpp/engine" "${ROOT_DIR}/app/src/main/assets"
cp "${LIB}" "${HDR}" "${ROOT_DIR}/app/src/main/cpp/engine/"
cp "${W}" "${N}" "${ROOT_DIR}/app/src/main/assets/"

echo "==> macOS setup complete!"
ls -lh "${ROOT_DIR}/app/src/main/cpp/engine" "${ROOT_DIR}/app/src/main/assets"

