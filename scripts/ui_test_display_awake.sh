#!/bin/bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
#
# An app on an LX display must keep running, and its display must hold a wake lock of its own for exactly as long as
# it exists. This puts <component> on a display through lxdb and reads Android's state, first with a surface, then with
# none, as a minimised window has. It also times how soon lxdb hears that an app turned sideways. It works with the
# screen off and on a locked phone, and it changes no setting. It needs an app on the display: with none, this phone
# freezes LX behind other apps and the test never ends.
#
# Usage: ANDROID_SERIAL=<serial> scripts/ui_test_display_awake.sh [component] [seconds]
#        (LX's debug and test APKs installed, lxdb running)
set -u
RUNNER=com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation
PROBE=com.lxlauncher.test/com.lxlauncher.test.OrientationProbeActivity
COMPONENT=${1:-com.transsion.calculator/.Calculator}
SECONDS_HELD=${2:-14}
PACKAGE=${COMPONENT%%/*}
OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT
failed=0
check() { if [ "$2" = "$3" ]; then echo "[PASS] $1: $2"; else echo "[FAIL] $1: $2, wanted $3"; failed=1; fi; }
locks() { adb shell dumpsys power | grep -c "SCREEN_BRIGHT_WAKE_LOCK.*lx:display-"; }
frozen() {
    local pid group
    pid=$(adb shell pidof "$PACKAGE" | tr -d '\r')
    group=$(adb shell cat "/proc/$pid/cgroup" | tr -d '\r' | sed -n 's/^0:://p')
    adb shell cat "/sys/fs/cgroup$group/cgroup.events" | tr -d '\r' | sed -n 's/^frozen //p'
}
resumed() { adb shell "dumpsys activity -a -p $PACKAGE activities" | grep -c "state=RESUMED"; }
display_state() { adb shell dumpsys display | tr -d '\r' | grep -m1 'DisplayInfo{"lx-held"' | sed -n 's/.*, state \([A-Z_]*\),.*/\1/p'; }
cpu_ticks() {
    local pid
    pid=$(adb shell pidof "$PACKAGE" | tr -d '\r')
    adb shell cat "/proc/$pid/stat" | awk '{print $14 + $15}'
}

hold() { adb shell "am instrument -w -e holdDisplay $SECONDS_HELD -e holdApp $COMPONENT $1 $RUNNER" >"$OUT" 2>&1 & held=$!; }

hold ""
sleep 9
grep -q "display -1" "$OUT" && { echo "[FAIL] lxdb did not answer"; exit 1; }
check "the display holds a wake lock of its own" "$(locks)" 1
check "$PACKAGE is not frozen" "$(frozen)" 0
wait $held
sleep 1
check "the lock ends with the display" "$(locks)" 0

hold "-e holdHidden true"
sleep 9
check "a hidden display stays on" "$(display_state)" ON
check "the app on it stays resumed" "$(resumed)" 1
check "the app on it is not frozen" "$(frozen)" 0
check "a hidden display keeps its wake lock" "$(locks)" 1
before=$(cpu_ticks)
sleep 4
after=$(cpu_ticks)
echo "       $PACKAGE used $((after - before)) CPU ticks in 4 s with nothing drawn"
wait $held
sleep 1
check "the lock ends with the hidden display" "$(locks)" 0

adb logcat -c
adb shell "am instrument -w -e holdDisplay 7 -e holdApp $PROBE $RUNNER" >"$OUT" 2>&1
asked=$(adb logcat -d -s LxProbe:I | grep -m1 'asking for landscape' | awk '{print $2}')
heard=$(adb logcat -d -s lxdb:I | grep -m1 'asks for orientation 0' | awk '{print $2}')
check "an app turning sideways reaches LX" "$(grep -c 'orientation 0' "$OUT")" 1
if [ -n "$asked" ] && [ -n "$heard" ]; then
    delay=$(python3 -c "
def ms(t):
    h, m, s = t.split(':')
    return (int(h) * 3600 + int(m) * 60 + float(s)) * 1000
print(round(ms('$heard') - ms('$asked')))")
    echo "       lxdb heard it $delay ms after the app asked"
    check "it hears at once, not at its next slow look" "$([ "$delay" -lt 1500 ] && echo yes || echo "no, $delay ms")" yes
fi
exit $failed
