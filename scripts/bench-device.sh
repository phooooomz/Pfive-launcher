#!/usr/bin/env bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
# Measures one LX build on the device named by ANDROID_SERIAL: five cold starts
# (am start -W after a force stop), total PSS after the launcher settles, and
# frame statistics from dumpsys gfxinfo over the same interaction every time
# (open the drawer, scroll it, switch workspaces five times, open and close LX
# Settings). The tap coordinates are for Waydroid at 1920x1028 and 180 dpi.
# Compare builds signed with the same key and run them alternately.
#
# Usage: ANDROID_SERIAL=<serial> scripts/bench-device.sh <apk> <label>
# Clears LX data; set Home back to the previous launcher afterwards.
set -u
apk=$1; label=$2
A="adb -s ${ANDROID_SERIAL:?}"
$A install -r "$apk" >/dev/null
$A shell pm clear com.lxlauncher >/dev/null
$A shell cmd package set-home-activity com.lxlauncher/.LauncherActivity >/dev/null
$A shell input keyevent KEYCODE_HOME; sleep 6
$A shell input tap 1213 163; sleep 1            # close the Welcome window
starts=""
for i in 1 2 3 4 5; do
    $A shell am force-stop com.lxlauncher; sleep 2
    t=$($A shell am start -W -n com.lxlauncher/.LauncherActivity -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | awk '/TotalTime/{print $2}')
    starts="$starts $t"; sleep 3
done
sleep 8
pss=$($A shell dumpsys meminfo com.lxlauncher | tr -d '\r' | awk '/TOTAL PSS:/{print $3; exit} /^ *TOTAL /{print $2; exit}')
$A shell dumpsys gfxinfo com.lxlauncher reset >/dev/null
$A shell input tap 1880 928; sleep 1.5          # drawer
for i in 1 2 3 4; do $A shell input swipe 900 800 900 300 250; sleep 0.6; done
for i in 1 2 3 4; do $A shell input swipe 900 300 900 800 250; sleep 0.6; done
$A shell input keyevent KEYCODE_ESCAPE; sleep 1
for x in 88 137 39 88 39; do $A shell input tap $x 928; sleep 1; done   # workspaces 2, 3, 1, 2, 1
$A shell input tap 55 472; sleep 2              # LX Settings window
$A shell input tap 1213 163; sleep 1            # close it
g=$($A shell dumpsys gfxinfo com.lxlauncher | tr -d '\r')
frames=$(echo "$g" | awk '/Total frames rendered/{print $4; exit}')
janky=$(echo "$g" | awk '/Janky frames:/ && !/legacy/{print $3, $4; exit}')
p50=$(echo "$g" | awk '/50th percentile/{print $3; exit}')
p90=$(echo "$g" | awk '/90th percentile/{print $3; exit}')
p99=$(echo "$g" | awk '/99th percentile/{print $3; exit}')
echo "$label cold_ms:$starts | pss_kb: $pss | frames: $frames janky: $janky p50: $p50 p90: $p90 p99: $p99"
