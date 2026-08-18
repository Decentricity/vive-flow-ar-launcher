#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SERIAL="${FLOW_SERIAL:-FA22B2S00442}"
PORT=8765

if ! adb -s "$SERIAL" get-state | grep -qx device; then
  echo "Vive Flow not on ADB ($SERIAL)" >&2
  exit 1
fi

adb -s "$SERIAL" reverse --remove-all >/dev/null 2>&1 || true
adb -s "$SERIAL" reverse tcp:$PORT tcp:$PORT
echo "adb reverse tcp:$PORT -> host"

exec python3 "$ROOT/top_server.py"
