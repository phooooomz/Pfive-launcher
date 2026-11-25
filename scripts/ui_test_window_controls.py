#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Full screen, Back and the close button for an app in an LX window, on a real phone with lxdb running.

Uses the test APK's probe app, never the owner's apps. What LX shows is read from uiautomator, which sees LX's own
views on the phone's screen and nothing of what the apps draw; the app's side comes from dumpsys and the probe's log.
LX's files are copied off first and put back at the end.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_window_controls.py --out build/ui-test-window-controls
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_test import PKG, Ui, sh  # noqa: E402
from ui_test_file_manager import restore_data, save_data  # noqa: E402
from ui_test_framed_apps import display_of, displays, open_from_menu, wait_for  # noqa: E402
from ui_test_keyboard import NAV_BACK, PROBE, probe_lines  # noqa: E402

TITLE = "LxProbeType"
# A maximised window's title bar on the test phone, left of its buttons.
TITLE_BAR = (200, 243)


def in_recents():
    return PROBE in sh("dumpsys activity recents", check=False)


def system_bars():
    """Bottom of the status bar and top of the navigation bar, from the phone's own insets."""
    out = sh("dumpsys window", check=False)
    status = re.search(r"type=statusBars frame=\[0,0\]\[\d+,(\d+)\]", out)
    nav = re.search(r"type=navigationBars frame=\[0,(\d+)\]", out)
    return int(status.group(1)) if status else 0, int(nav.group(1)) if nav else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-window-controls")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ui = Ui(args.out)
    results = []

    def step(name, ok, evidence=""):
        results.append({"step": name, "result": "PASS" if ok else "FAIL", "evidence": evidence})
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  {evidence}", flush=True)
        return ok

    if "uid=" not in sh(f"run-as {PKG} id", check=False):
        print("run-as is not available for LX; this test refuses to run without a way to restore LX's data")
        return 2
    if not sh("pgrep -f '[l]iblxdb.so daemon'", check=False).strip():
        print("lxdb is not running; connect LX Bridge first")
        return 2
    backup = os.path.join(args.out, "lx-data-before.tar")
    save_data(backup)
    width = int(re.search(r"(\d+)x(\d+)", sh("wm size")).group(1))
    try:
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(4)
        sh("logcat -c", check=False)
        open_from_menu("lxprobetype")
        shown = wait_for(lambda: display_of(PROBE))
        step("the probe opens in an LX window", bool(shown), f"display={shown}")
        time.sleep(2.5)
        windowed = displays().get(shown)

        # uiautomator reads the window with the phone's focus, which the app holds now; a press on the title bar
        # gives it back to LX and opens the window menu.
        sh(f"input swipe {TITLE_BAR[0]} {TITLE_BAR[1]} {TITLE_BAR[0]} {TITLE_BAR[1]} 900")
        time.sleep(1.0)
        item = [n for n in ui.wait(regex=r"^(Layar penuh|Full screen)$", timeout=4) if n["pkg"] == PKG]
        step("the window menu offers full screen", bool(item))
        if item:
            ui.tap(item[0])
        top, bottom = system_bars()
        full = wait_for(lambda: (lambda s: s if s and s[0] >= width - 2 and s[1] >= bottom - top - 2 else None)(displays().get(shown)), timeout=6)
        step("full screen gives the app everything between the system bars", bool(full),
             f"display {displays().get(shown)}, screen {width}x{bottom - top} between the bars, before {windowed}")
        time.sleep(1)
        seen = [n for n in ui.find(regex=r".") if n["pkg"] == PKG]
        # The frame, the app's view and the taskbar keep the name for TalkBack; the title bar shows it as text.
        bars = [n for n in seen if n["text"] == TITLE and n["cls"].endswith("TextView")]
        step("no LX title bar is left in full screen", bool(seen) and not bars, f"LX views read: {len(seen)}")

        sh("logcat -c", check=False)
        sh(f"input tap {NAV_BACK[0]} {NAV_BACK[1]}")
        back = wait_for(lambda: displays().get(shown) == windowed, timeout=6)
        step("Back gives the window its size from before", bool(back), f"display {displays().get(shown)}")
        step("the app itself got no Back", not any(line.startswith("back") for line in probe_lines()), " | ".join(probe_lines()))
        title = [n for n in ui.wait(label=TITLE, timeout=4) if n["pkg"] == PKG]
        step("the title bar and its buttons are back", bool(title))

        step("the app is in Recents while its window is open", in_recents())
        close = [n for n in ui.find(regex=r"^(Tutup|Close)$") if n["pkg"] == PKG and n["clickable"]]
        step("the close button is there", bool(close))
        if close:
            ui.tap(close[0])
        gone = wait_for(lambda: display_of(PROBE) is None, timeout=8)
        step("the close button closes the window", gone is True)
        left = wait_for(lambda: not in_recents(), timeout=6)
        step("the close button ends the app: it leaves Recents", left is True)
        ended = wait_for(lambda: not sh(f"pidof {PROBE}", check=False).strip(), timeout=6)
        step("the close button ends the app: its process ends", ended is True)

        crash = sh("logcat -d -b crash", check=False)
        step("no crash from LX or lxdb", not any(p in crash for p in (PKG, "lxdb")), crash[-300:])
    finally:
        restore_data(backup)
        os.remove(backup)
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(1)

    with open(os.path.join(args.out, "results.json"), "w") as f:
        json.dump(results, f, indent=1)
    failed = [r for r in results if r["result"] == "FAIL"]
    print(f"{len(results) - len(failed)} passed, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
