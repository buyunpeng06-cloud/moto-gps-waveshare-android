# Android port source audit

Review date: 2026-10-20. Reviewed against the uploaded `moto-gps-waveshare-main` source archive (archive root commit label `c8bdacca0e01dda85260975aa2027c1ae8a549e5`). The archive does not include `.git` history and the `third_party/lvgl` submodule directory is empty. No root `AGENTS.md` is present in the archive.

## Authoritative project and license files reviewed

- `README.md`, `LICENSE.md`, `NOTICE`, `THIRD_PARTY_NOTICES.md`
- `docs/ANDROID_AI_GUIDE.md`, `docs/GATEWAY_SETUP.md`, `backend/README.md`
- `.github/workflows/checks.yml`, `.gitmodules`, root `CMakeLists.txt`
- `shared/protocol/ble-navigation-v1.md` and `shared/protocol/fixtures/ble-navigation-v1.golden.txt`
- `shared/protocol/route-request.v1.schema.json`, `route-options.v1.schema.json`, `route-bundle.v1.schema.json`, `map-scene.v1.schema.json`
- `shared/ble_protocol`, `shared/nav_core`, `shared/nav_app`, `shared/coordinates`
- ESP32 `phone_nav_bridge.cpp` and `ble_nav_transport_nimble.cpp`
- iOS BLE, Navigation, Location, OfflineMap adapters, `AppModel.swift`, and AppTests / core tests
- `tests/native`, `backend/test`, and the existing CI workflow

## Reusable interfaces confirmed from source

### BLE v1

- Service UUID: `7e57a000-b50c-4b6a-9c57-40a54e8e1000`
- Phone -> ESP32 RX UUID: `7e57a001-b50c-4b6a-9c57-40a54e8e1000` (encrypted write)
- ESP32 -> phone TX UUID: `7e57a002-b50c-4b6a-9c57-40a54e8e1000` (encrypted Notify)
- CCCD UUID: `00002902-0000-1000-8000-00805f9b34fb`
- Frame: 10-byte header, little-endian fields, fragment payload, 2-byte CRC-16/CCITT-FALSE; magic `0xB7`, protocol v1.
- Characteristic frame limit: `min(ATT_MTU - 3, peer max_frame_size, 512)`; MTU 23 therefore permits a 20-byte value, not a 512-byte value.
- Session sequence: encrypted GATT + service discovery + TX Notify; Phone/Starting; Device/Ready; Phone/Ready; final Device/Ready. Business payloads are gated until final Ready.
- Fragments of one logical message must remain contiguous; complete message reassembly and CRC precede application handling. Link-layer write success is not application ACK. Requested ACKs use the same sequence on retry, up to two retries after 750 ms per the spec.
- Existing shared codec is `shared/ble_protocol`; shared route logic is `shared/nav_core`; coordinates are in `shared/coordinates`; the iOS implementation has policy and golden checks that can inform Android tests but platform APIs must not be copied directly.

### Gateway and schemas

- `GET /v1/places?keywords=...&longitude_deg=...&latitude_deg=...&region=...`
- `POST /v1/route-options` (up to three route candidates)
- `POST /v1/routes` (selected route, reroute, periodic route/traffic refresh)
- `GET /v1/map/cities?keywords=...`
- `GET /v1/map/tiles/15/{x}/{y}`
- Route request origin/destination use WGS84. Provider route geometry is GCJ-02. POI results are normalized to WGS84. Map tile indices are based on WGS84 Web Mercator; map scene geometry is GCJ-02. Avoid double conversion and do not overlay coordinates from different systems without conversion.
- The AMap Web Service key is server-side only. Existing backend does not promise motorcycle-specific route rules, road speed limits, or traffic-light countdowns.
- The route request/options/bundle and map-scene JSON schemas are strict (`additionalProperties: false`) and must be treated as the wire contract.

## Documentation / implementation discrepancy to raise upstream

`shared/protocol/ble-navigation-v1.md` says the ESP32 takes the intersection of the phone and device capability masks before sending Device/Ready. In `platforms/esp32/main/ble_nav_transport_nimble.cpp`, `send_connection_status()` currently sends the full device capability mask (navigation, geometry, traffic, media, touch/music commands, command ACK, map scene). The phone should compute and advertise only its own supported capabilities and derive the usable intersection locally. This Android alpha currently advertises zero business capabilities until producers/consumers are implemented; the ESP32's advertised mask is not treated as proof that the phone supports those features. Do not change the wire format to hide this discrepancy.

The firmware also has a compatibility path that answers Phone/Ready with Device/Ready and opens the display-data gate. The normal documented sequence remains the two Device/Ready responses with identical session/negotiated parameters.

## Current Android slice

- Native Android UI / ViewModel / BLE repository separation, scan by service UUID, explicit device selection, GATT service checks, MTU negotiation, TX Notify subscription, bonding/security handling, BLE v1 Kotlin codec and golden-byte tests are present in `platforms/android/`.
- The Android BLE codec delegates to the existing `shared/ble_protocol` through `platforms/android/app/src/main/cpp/native_ble_codec.cpp` when the native library loads; the Kotlin reference path remains for JVM-only tests. The JNI bridge and shared C++ codec compiled on the host and passed the golden-byte self-test. NDK APK compilation is not yet run. JNI integration with `shared/nav_core` and `shared/coordinates` remains required before real navigation.
- The current BLE slice handles the handshake and heartbeat but does not yet implement the full application-ACK/retry policy for business messages. It sends no navigation/map/media payloads, so no unsupported business capability is advertised.
- Location, foreground navigation service, route search/preview, route calculation/matching, map download and music controls are not yet implemented.

## Validation performed in this environment

- `platforms/android/tools/run-protocol-selftest.sh`: PASS (6 checks on Kotlin reference path, exact golden bytes / CRC / fragmentation / reassembly / timeout / corrupt CRC).
- `platforms/android/tools/run-jni-host-selftest.sh`: PASS (host CMake build of the JNI bridge + upstream C++ BLE codec, then the same 6 golden/self-tests through JNI).
- `cmake -S tests/native -B /tmp/moto-native-tests -G Ninja && cmake --build /tmp/moto-native-tests --parallel 4 && ctest --test-dir /tmp/moto-native-tests --output-on-failure`: PASS (7/7 native tests, using the project's no-LVGL UI stub path).
- `node scripts/generate_jinan_demo_fixture.mjs --check`: PASS (no differences reported).
- `node scripts/offline_map/validate_jinan_sqlite.mjs`: PASS (SQLite integrity `ok`, schema v1, GCJ-02).
- Backend `npm test`: NOT PASS in this attempt. 36 tests passed and 4 test files failed to load because `npm ci` timed out and left `pmtiles` incomplete in `node_modules`; this is a dependency-install/environment failure, not a clean backend regression result. Rerun after a successful `npm ci`.
- Root CMake configure: BLOCKED because the uploaded archive omits the initialized `third_party/lvgl` submodule. The standalone native test build above does not require that submodule.
- Android Gradle build / lint / APK: NOT RUN. Android SDK and Gradle are absent in this execution environment; no Gradle Wrapper JAR is available. No S25 / physical round-display test was run.
- GitHub branch/push/release: NOT performed. The uploaded archive has no `.git` metadata and no authenticated GitHub write connection is available here.
