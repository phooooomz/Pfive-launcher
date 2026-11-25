#!/usr/bin/env bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
# Debug APK, unsigned release APK, the instrumentation APK and lint, with
# SHA-256 checksums of the APKs. Release signing is left to the owner.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
version=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
rm -rf build/dist && mkdir -p build/dist
cp app/build/outputs/apk/debug/app-debug.apk "build/dist/lx-launcher-$version-debug.apk"
cp app/build/outputs/apk/release/app-release-unsigned.apk "build/dist/lx-launcher-$version-release-unsigned.apk"
(cd build/dist && sha256sum ./*.apk | tee SHA256SUMS)
