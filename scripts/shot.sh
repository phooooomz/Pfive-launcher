#!/usr/bin/env bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
# Screenshot from a device or Waydroid. `adb exec-out screencap` on Waydroid
# prints a driver warning into the PNG stream, so capture to a file and pull.
set -euo pipefail
serial="${ANDROID_SERIAL:-}"
out="${1:?usage: shot.sh <out.png>}"
adb ${serial:+-s "$serial"} shell screencap -p /data/local/tmp/lx-shot.png 2>/dev/null
adb ${serial:+-s "$serial"} pull /data/local/tmp/lx-shot.png "$out" >/dev/null
adb ${serial:+-s "$serial"} shell rm /data/local/tmp/lx-shot.png
echo "$out"
