#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="$HERE/protocol-selftest.jar"
KOTLINC="${KOTLINC:-kotlinc}"
cd "$ROOT"
"$KOTLINC" \
  "$ROOT/app/src/main/java/top/maler/motogps/ble/BleProtocol.kt" \
  "$HERE/ProtocolSelfTest.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"
