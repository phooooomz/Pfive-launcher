#!/usr/bin/env bash
# Alternating A/B of the window checks: each APK installed in turn, `-e only windows` run between a checked backup
# and restore of LX's data. Usage: ab-resize.sh <tag> <apkA> <apkB> [rounds]
set -u
cd "$(dirname "$0")/../.."
mkdir -p build/device
export ANDROID_SERIAL="${ANDROID_SERIAL:-$(cat scripts/device/.serial 2>/dev/null)}" NO_HOME=1
[ -n "$ANDROID_SERIAL" ] || { echo "set ANDROID_SERIAL or put the phone's serial in scripts/device/.serial" >&2; exit 1; }
tag=$1; a=$2; b=$3; rounds=${4:-2}
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
for r in $(seq 1 "$rounds"); do
  for side in A B; do
    apk=$a; [ "$side" = B ] && apk=$b
    adb install -r -t "$apk" >/dev/null && echo "installed $side ($apk)"
    python3 scripts/device/run-instr.py windows "build/device/ab-$tag-$side-$r.txt" > "build/device/ab-$tag-$side-$r.log" 2>&1
    grep -E 'PERF (resize|open) [a-z]+: [0-9]' "build/device/ab-$tag-$side-$r.txt" | sed "s/^/$side$r /" | cut -c1-260
  done
done
adb shell input keyevent KEYCODE_HOME
