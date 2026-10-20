# Android community port patch bundle

This ZIP is an overlay patch for the uploaded `moto-gps-waveshare-main` source tree, not a replacement full repository and not a Git commit.

## Apply
1. Make a backup of your full Git clone.
2. Extract this ZIP into the repository root, preserving directory paths and allowing the listed changed files to overwrite their corresponding originals.
3. Review `git diff` before committing. The patch does not include `.git`, credentials, `local.properties`, APKs, or signing material.
4. Initialize the project's recursive submodules in the full clone before the full root build.

## Contents
- `platforms/android/`: Kotlin/Compose app, BLE v1 transport, JNI adapter to upstream `shared/ble_protocol`, tests and host self-test scripts.
- `ANDROID_SOURCE_AUDIT.md`, `ANDROID_PROGRESS.md`: source-backed audit and honest validation status.
- `.github/workflows/checks.yml`: Android build/test/lint CI job (currently bootstraps a Gradle wrapper per CI run because wrapper scripts/JAR could not be generated in the offline authoring environment).
- `README.md`: documents the experimental community port and license status.

This is experimental alpha source. The Android Gradle build and physical Samsung S25 + 1.75C board have not yet been verified. Do not publish as a stable APK until those checks pass.
