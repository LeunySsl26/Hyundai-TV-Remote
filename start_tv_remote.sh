#!/usr/bin/env bash
# Script de inicio rápido del Servidor Web de Control Remoto Hyundai TV (webOS)
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v python3 >/dev/null 2>&1; then
    echo "[!] Error: python3 no está instalado."
    exit 1
fi

if ! python3 -c "import websockets" >/dev/null 2>&1; then
    echo "[*] Instalando dependencia 'websockets'..."
    pip install --user websockets || pip install websockets
fi

python3 tv_remote_web.py "$@"
