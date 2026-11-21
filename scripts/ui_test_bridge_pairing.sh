#!/bin/bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
#
# Puts LX Bridge's key exchange against BoringSSL's: the real `adb pair` on this computer pairs with a test
# server in the instrumentation APK, which runs LX's own code on the loopback address of the phone. Wireless
# debugging stays off and the phone's adbd is not involved. The computer's list of paired devices is put back.
#
# Usage: ANDROID_SERIAL=<serial> scripts/ui_test_bridge_pairing.sh   (LX's debug and test APKs installed)
set -u
RUNNER=com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation
PORT=37123
HOSTS=$HOME/.android/adb_known_hosts.pb
SAVED=$(mktemp)
OUT=$(mktemp)
[ -f "$HOSTS" ] && cp -p "$HOSTS" "$SAVED"

finish() {
    adb forward --remove tcp:$PORT 2>/dev/null
    if [ -s "$SAVED" ]; then cp -p "$SAVED" "$HOSTS"; else rm -f "$HOSTS"; fi
    rm -f "$SAVED" "$OUT"
}
trap finish EXIT

adb forward tcp:$PORT tcp:$PORT >/dev/null
failed=0

try() {  # try <code the peer expects> <code typed> <text adb must print>
    adb shell am instrument -w -e pairingPeer "$1" $RUNNER >"$OUT" 2>&1 &
    local peer=$!
    sleep 4
    local said
    said=$(timeout 20 adb pair 127.0.0.1:$PORT "$2" 2>&1)
    wait $peer
    if grep -q "$3" <<<"$said"; then echo "[PASS] code $2: $said"; else echo "[FAIL] code $2: $said"; failed=1; fi
}

try 482915 482915 "Successfully paired"
try 482915 111111 "Wrong password"
exit $failed
