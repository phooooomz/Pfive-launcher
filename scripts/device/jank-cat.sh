#!/usr/bin/env bash
# Opening and switching categories in the Applications menu, gfxinfo from a reset. Taps land on the category
# icons (x=70), which stay in place when the first column turns into a rail, so no tap can reach an app.
# No LX window may be open. Arg: a name for the run.
set -u
cd "$(dirname "$0")/../.."
mkdir -p build/device
export ANDROID_SERIAL="${ANDROID_SERIAL:-$(cat scripts/device/.serial 2>/dev/null)}"
[ -n "$ANDROID_SERIAL" ] || { echo "set ANDROID_SERIAL or put the phone's serial in scripts/device/.serial" >&2; exit 1; }
out=build/device/jank-cat-$1.txt
gfx() { adb shell dumpsys gfxinfo com.lxlauncher | grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number High input latency|Number Slow UI thread|Number Slow bitmap uploads|Number Slow issue draw commands|Number Frame deadline missed|Number Frame deadline missed \(legacy\)" | head -13; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 0.4; }
{
echo "== $1 $(date +%H:%M:%S)"
back; back; back
adb shell input tap 62 1456; sleep 1.5
adb shell dumpsys gfxinfo com.lxlauncher reset >/dev/null; sleep 0.5
for round in 1 2 3 4 5 6; do
  adb shell input tap 70 836; sleep 0.8   # Media opens
  adb shell input tap 70 740; sleep 0.8   # Game: the submenu moves over
  adb shell input tap 70 1028; sleep 0.8  # Sosial
  adb shell input tap 70 1028; sleep 0.8  # Sosial again closes it
done
echo "-- categories (6 x open, 2 switches, close)"; gfx
back; back; back
} > "$out" 2>&1
grep -E "^--|Janky frames:|90th|95th|99th|Total" "$out" | paste - - - - - -
