#!/usr/bin/env bash
# ==============================================================================
# Theia Model & Engine Setup (Universal Entrypoint)
# Automatically delegates to macOS, Linux, or Windows setup scripts.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$(uname -s)" in
    Darwin*)
        exec "${SCRIPT_DIR}/setup_macos.sh" "$@"
        ;;
    Linux*)
        exec "${SCRIPT_DIR}/setup_linux.sh" "$@"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        if [ -f "${SCRIPT_DIR}/setup_windows.bat" ]; then
            exec cmd.exe /c "${SCRIPT_DIR}\\setup_windows.bat"
        else
            echo "Please run scripts/setup_windows.ps1 or scripts/setup_windows.bat on Windows." >&2
            exit 1
        fi
        ;;
    *)
        exec "${SCRIPT_DIR}/setup_linux.sh" "$@"
        ;;
esac
