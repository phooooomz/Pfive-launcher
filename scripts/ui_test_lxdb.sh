#!/bin/bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
#
# lxdb on a real phone, with the daemon already running: its commands, who it answers, and that it holds no network
# socket. It writes one file under /data/local/tmp and removes it, and it touches no app or setting of the owner's.
#
# Usage: ANDROID_SERIAL=<serial> scripts/ui_test_lxdb.sh
#        (LX's debug and test APKs installed, lxdb running)
set -u
RUNNER=com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation
PACKAGE=com.lxlauncher
TEST_PACKAGE=com.lxlauncher.test
FIXTURE=/data/local/tmp/lxdb-test
LOCAL=$(mktemp -d)
trap 'rm -rf "$LOCAL"; adb shell rm -f "$FIXTURE" "$FIXTURE.back"' EXIT
failed=0
passed=0
check() {
    if [ "$2" = "$3" ]; then
        echo "[PASS] $1: $2"
        passed=$((passed + 1))
    else
        echo "[FAIL] $1: got '$2', wanted '$3'"
        failed=$((failed + 1))
    fi
}
trimmed() { tr -d '\r' | sed -e 's/[[:space:]]*$//'; }

APK=$(adb shell pm path "$PACKAGE" | sed -n 's/^package://p' | tr -d '\r')
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
# LX's libraries are stored compressed and unpacked by Android (Tedit's Linux runtime needs that); the linker runs the
# unpacked copy. An APK that stores them as they are runs from inside the APK.
case "$ABI" in arm64-v8a) ARCH=arm64 ;; *) ARCH=$ABI ;; esac
UNPACKED="$(dirname "$APK")/lib/$ARCH/liblxdb.so"
if [ "$(adb shell "[ -f '$UNPACKED' ] && echo yes" | tr -d '\r')" = yes ]; then
    LXDB="/system/bin/linker64 '$UNPACKED'"
else
    LXDB="/system/bin/linker64 '$APK!/lib/$ABI/liblxdb.so'"
fi
DAEMON=$(adb shell "pgrep -f '[l]iblxdb.so daemon'" | tr -d '\r')
[ -n "$DAEMON" ] || { echo "lxdb is not running"; exit 2; }

# ---- the commands ----
adb shell "$LXDB doctor" >/dev/null
check "doctor finds every call" "$?" 0
check "version names the daemon" "$(adb shell "$LXDB version" | grep -c "daemon pid $DAEMON uid 2000")" 1
check "devices names this phone" "$(adb shell "$LXDB devices" | grep -c "Android")" 1
check "services lists Android's services" "$(adb shell "$LXDB services" | grep -cx 'activity_task')" 1
check "shell runs as the shell user" "$(adb shell "$LXDB shell id -u" | trimmed)" 2000
adb shell "$LXDB shell 'exit 7'"
check "shell passes on the exit status" "$?" 7
check "shell takes input" "$(adb shell "echo through | $LXDB shell cat" | trimmed)" through
check "a word with spaces stays one word" "$(adb shell "$LXDB shell \"printf '%s' 'a  b'\"" | trimmed)" "a  b"
check "dumpsys answers" "$(adb shell "$LXDB dumpsys activity activities" | grep -c 'ACTIVITY MANAGER')" 1

# A file big enough to cross several pipe buffers, with a byte of every value.
head -c 5000000 /dev/urandom >"$LOCAL/fixture"
adb push "$LOCAL/fixture" "$FIXTURE" >/dev/null
adb shell "$LXDB push $FIXTURE $FIXTURE.back"
check "push keeps every byte" "$(adb shell sha256sum "$FIXTURE.back" | cut -d' ' -f1)" "$(sha256sum "$LOCAL/fixture" | cut -d' ' -f1)"
adb shell "rm -f $FIXTURE.back; $LXDB pull $FIXTURE $FIXTURE.back"
check "pull keeps every byte" "$(adb shell sha256sum "$FIXTURE.back" | cut -d' ' -f1)" "$(sha256sum "$LOCAL/fixture" | cut -d' ' -f1)"

# A real terminal: sh answers on a line of its own, and exit ends it.
answer=$(printf 'id -u\nexit 3\n' | adb shell -tt "$LXDB shell" 2>/dev/null | tr -d '\r' | grep -cx 2000)
check "shell without a command is a terminal" "$answer" 1

# ---- who it answers ----
# Apps stay off the control socket: SELinux stops them, and the daemon's own uid check stands behind that.
for app in "$PACKAGE" "$TEST_PACKAGE"; do
    adb shell "run-as $app $LXDB shell id -u" >"$LOCAL/other" 2>&1
    check "$app gets no shell through the socket" "$(grep -c '^2000' "$LOCAL/other")" 0
done
check "LX's process reaches lxdb through Binder" "$(adb shell "am instrument -w -e shellCommand 'id -u' $RUNNER" | tr -d '\r' | grep -cx 2000)" 1

# ---- no network ----
check "the daemon runs as the shell user" "$(adb shell "cat /proc/$DAEMON/status" | awk '/^Uid:/ {print $2}')" 2000
adb shell "ls -l /proc/$DAEMON/fd" | tr -d '\r' >"$LOCAL/fds"
check "the daemon talks to the Binder driver" "$(grep -c '/dev/binder' "$LOCAL/fds")" 1
inodes=$(sed -n 's/.*socket:\[\([0-9]*\)\].*/\1/p' "$LOCAL/fds")
network=0
for inode in $inodes; do
    if adb shell "cat /proc/$DAEMON/net/tcp /proc/$DAEMON/net/tcp6 /proc/$DAEMON/net/udp /proc/$DAEMON/net/udp6" | grep -qw "$inode"; then
        network=$((network + 1))
    fi
done
check "none of its sockets is a network socket" "$network" 0
check "its only listening socket is lxdb's own" "$(adb shell "cat /proc/$DAEMON/net/unix" 2>/dev/null | grep -c '@lxdb-u0')" 1

echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
