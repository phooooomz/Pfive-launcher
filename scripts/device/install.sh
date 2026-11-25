#!/bin/sh
# Installs an LX build on the test phone. When LX was Home before, an update makes the XOS Home chooser appear; it is
# answered as the owner had answered it (LX-LAUNCHER, Always), which, unlike `cmd package set-home-activity`, survives
# LX being force-stopped by the tests.
S=${ANDROID_SERIAL:-$(cat "$(dirname "$0")/.serial" 2>/dev/null)}
[ -n "$S" ] || { echo "set ANDROID_SERIAL or put the phone's serial in scripts/device/.serial" >&2; exit 1; }
was=$(adb -s $S shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME | grep -m1 'name=' | tr -d ' ')
adb -s $S install -r "$1" | tail -1
if [ "$was" = "name=com.lxlauncher.LauncherActivity" ] || [ -n "$KEEP_LX_HOME" ]; then
  adb -s $S shell input keyevent KEYCODE_HOME; sleep 2
  adb -s $S shell uiautomator dump /data/local/tmp/u.xml >/dev/null
  xml=$(adb -s $S shell cat /data/local/tmp/u.xml)
  lx=$(echo "$xml" | grep -o 'text="LX-LAUNCHER"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
  al=$(echo "$xml" | grep -o 'text="\(Selalu\|Always\)"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
  if [ -n "$lx" ] && [ -n "$al" ]; then
    set -- $(echo "$lx" | tr '[],' '   '); adb -s $S shell input tap $(( ($1 + $3) / 2 )) $(( $2 - 36 )); sleep 1
    set -- $(echo "$al" | tr '[],' '   '); adb -s $S shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 2
  fi
fi
adb -s $S shell "dumpsys activity activities | grep -m1 topResumedActivity"
