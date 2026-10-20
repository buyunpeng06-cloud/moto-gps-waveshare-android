# Android port progress

Updated: 2026-10-20. Target: Samsung Galaxy S25 (Android version not yet supplied), Waveshare ESP32-S3-Touch-AMOLED-1.75C using existing upstream-compatible firmware.

## Phase 1 — source audit + BLE handshake slice

### Implemented in this checkout
- Added `platforms/android/`: Kotlin + Jetpack Compose app scaffold, ViewModel, BLE repository, protocol codec, gateway/location/map/navigation boundaries, and unit-test fixtures.
- BLE discovery filters on the upstream Navigation Service UUID; the user explicitly chooses the device before connecting.
- Checks the RX/TX characteristic UUIDs and required properties; negotiates MTU and derives the GATT value limit instead of assuming 512 bytes.
- Enables TX Notify and follows Android bonding/security when encrypted GATT is required. The firmware encryption requirement is not weakened.
- Implements v1 framing, CRC-16/CCITT-FALSE, message fragmentation/reassembly, two-Device-Ready handshake and a session-relative heartbeat.
- Business capability mask remains zero until the Android side actually implements those business payloads. No fake location/route is produced.
- Adds Android CI job to install the selected SDK platform/build tools, bootstrap Gradle 8.13 for the job, then run assembleDebug, testDebugUnitTest and lintDebug.
- Updates root README to describe this as an experimental community Android port and links to the Android README.
- Adds `ANDROID_SOURCE_AUDIT.md` with the protocol/gateway/schema contracts, an ESP32 capability-negotiation documentation discrepancy, and explicit test limitations.

### Toolchain target
- JDK: 17 for CI (local sandbox has OpenJDK 21.0.12.1).
- Gradle: 8.13 (CI bootstraps the wrapper for that CI run; the generated wrapper scripts/JAR are **not yet committed**).
- Android Gradle Plugin: 8.13.2.
- Kotlin: 2.2.21.
- compileSdk: 36; targetSdk: 35; minSdk: 26.
- NDK: `27.2.12479018`; CMake: `3.22.1`; BLE codec JNI is implemented. Shared navigation core/coordinates JNI is pending.

### Automated checks actually run
- `cd platforms/android && ./tools/run-protocol-selftest.sh`: PASS (6 checks: CRC standard vector, exact upstream connection frame, exact 20-byte fragmentation golden bytes, reassembly/duplicate START, timeout, bad CRC).
- `cmake -S tests/native -B /tmp/moto-native-tests -G Ninja && cmake --build /tmp/moto-native-tests --parallel 4 && ctest --test-dir /tmp/moto-native-tests --output-on-failure`: PASS, 7/7 native tests.
- `node scripts/generate_jinan_demo_fixture.mjs --check`: PASS (no diff output).
- `node scripts/offline_map/validate_jinan_sqlite.mjs`: PASS (SQLite integrity `ok`, schema v1, GCJ-02).

### Failed / blocked / not run
- Root CMake configure is blocked because the uploaded source archive does not include the initialized `third_party/lvgl` submodule. The standalone native tests above use the repository's no-LVGL test stub and do not replace the full root build.
- Backend `npm test` attempt: 36 passed, 4 test files failed to load because `npm ci` timed out and left `pmtiles` incomplete. This is not a clean backend regression result; rerun after a successful dependency installation.
- Android Gradle build, Android unit-test task, lint, APK generation and installation: NOT RUN because this sandbox has no Android SDK or Gradle distribution / Wrapper JAR.
- JNI integration with `shared/ble_protocol`: implemented and host-tested against golden bytes. Android NDK APK compilation is not yet run. JNI integration with `shared/nav_core` / `shared/coordinates`: NOT implemented yet.
- Samsung S25 pairing, encryption, lock-screen operation, reconnection, power use and physical 1.75C board: NOT TESTED.
- GitHub branch/commit/push/release: NOT performed. The uploaded ZIP has no `.git` metadata and no authenticated GitHub write connection is available in this session.

## Next steps
1. Initialize the recursive `third_party/lvgl` submodule in a full Git clone and rerun the full root CI suite and backend tests after successful `npm ci`.
2. Generate and commit the official Gradle Wrapper files from a network-enabled Gradle environment, then make CI use the committed `./gradlew` directly.
3. Commit the official Gradle Wrapper; run Android Gradle build/test/lint in CI; extend JNI to `shared/nav_core` / `shared/coordinates` and test lifecycle/coordinate boundaries.
4. Build/install the debug APK and test secure pairing + final Ready handshake + disconnect/reconnect with the actual 1.75C board and Samsung S25.
5. Implement gateway settings, real location, destination search, route candidates and route session; then implement the foreground navigation service and map downloads.
6. Only after successful build and real-device checks, prepare a test release and upstream contribution. Keep PolyForm Noncommercial 1.0.0 and third-party/map attributions intact.
