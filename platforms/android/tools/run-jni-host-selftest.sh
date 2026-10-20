#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
BUILD_DIR="${MOTO_ANDROID_JNI_TEST_BUILD_DIR:-${TMPDIR:-/tmp}/moto-android-jni-host-build}"
JAR="${TMPDIR:-/tmp}/moto-android-jni-protocol-selftest.jar"

command -v cmake >/dev/null || { echo "cmake is required" >&2; exit 2; }
command -v ninja >/dev/null || { echo "ninja is required" >&2; exit 2; }
command -v kotlinc >/dev/null || { echo "kotlinc is required" >&2; exit 2; }
command -v java >/dev/null || { echo "java is required" >&2; exit 2; }

cmake -S "$ROOT/app/src/main/cpp" -B "$BUILD_DIR" -G Ninja
cmake --build "$BUILD_DIR" --parallel 4
kotlinc \
  "$ROOT/app/src/main/java/top/maler/motogps/ble/BleProtocol.kt" \
  "$ROOT/app/src/main/java/top/maler/motogps/ble/NativeBleCodec.kt" \
  "$HERE/ProtocolSelfTest.kt" \
  -include-runtime -d "$JAR"
(cd "$ROOT" && java -Djava.library.path="$BUILD_DIR" -jar "$JAR")
