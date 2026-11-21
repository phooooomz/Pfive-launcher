#!/usr/bin/env bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
# Installs the debug build on the device named by ANDROID_SERIAL and runs the
# instrumentation tests and the three UI test batches. The UI tests make LX the
# Home app for the run and give Home back to HOME_RESTORE at the end.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
: "${HOME_RESTORE:?set HOME_RESTORE to the launcher component to restore, e.g. com.android.launcher3/.uioverrides.QuickstepLauncher}"
# The design reference is a blueprint, never app content. The listing goes to a file first:
# with pipefail, grep -q closing the pipe early would make a match look like a failure.
command -v unzip >/dev/null || { echo "FAIL unzip is needed to check the APK" >&2; exit 1; }
mkdir -p build
unzip -Z1 app/build/outputs/apk/debug/app-debug.apk > build/apk-contents.txt
if grep -qi referensi build/apk-contents.txt; then
    echo "FAIL the APK contains the design reference image" >&2
    exit 1
fi
# Waydroid's package manager can leave an install hanging; fail instead of waiting forever.
timeout 180 adb install -r app/build/outputs/apk/debug/app-debug.apk
timeout 180 adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation | tee build/instrumentation.txt
grep -q " 0 failed" build/instrumentation.txt
python3 scripts/ui_test.py --out build/ui-test --restore-home "$HOME_RESTORE"
python3 scripts/ui_test_more.py --out build/ui-test-more --restore-home "$HOME_RESTORE"
# Batch three works on real shared storage: a folder for File Manager and an image
# for the wallpaper picker. Leftovers from an earlier run are removed first.
adb shell rm -rf /sdcard/Documents/LXTest
adb shell mkdir -p /sdcard/Documents/LXTest /sdcard/Pictures
adb shell "echo hello from LX > /sdcard/Documents/LXTest/hello.txt"
# The wallpaper fixture is a generated test card, never the design reference:
# whatever the test picks stays on the device as an LX wallpaper.
python3 scripts/make-test-wallpaper.py build/test-fixtures/lx-wallpaper-test.png >/dev/null
adb push build/test-fixtures/lx-wallpaper-test.png /sdcard/Pictures/lx-wallpaper-test.png >/dev/null
# The photo picker lists the newest image first; make the fixture the newest.
adb shell touch /sdcard/Pictures/lx-wallpaper-test.png
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d file:///sdcard/Pictures/lx-wallpaper-test.png >/dev/null
python3 scripts/ui_test_full.py --out build/ui-test-full --restore-home "$HOME_RESTORE"
