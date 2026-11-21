#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Android apps inside LX windows, on a real phone with lxdb running. Safe for the owner's own phone.

It never clears LX's data: LX's files are copied off the device first (run-as) and put back at the end.
It takes no screenshots, so nothing of what the apps show leaves the phone. Every check reads dumpsys:
the app's task on its own display, the display's size, and that Android letterboxes nothing.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_framed_apps.py --out build/ui-test-framed [--cycles 3] [--apps "..."]
The apps are "search text=package" pairs; LX's Applications menu opens the first hit of the search text.
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_test import PKG, Ui, adb, sh  # noqa: E402
from ui_test_file_manager import restore_data, save_data  # noqa: E402

DEFAULT_APPS = ("kalkulator=com.transsion.calculator,chrome=com.android.chrome,youtube=com.google.android.youtube,"
                "whatsapp=com.whatsapp,facebook=com.facebook.katana")
APPS_BUTTON = (62, 1456)


def displays():
    """{id: (width, height)} of LX's app displays."""
    out = sh("dumpsys display", check=False)
    found = {}
    for m in re.finditer(r'mOverrideDisplayInfo=DisplayInfo\{"lx-app\d+", displayId (\d+).*?real (\d+) x (\d+)', out):
        found[int(m.group(1))] = (int(m.group(2)), int(m.group(3)))
    return found


def activities(package):
    return sh(f"dumpsys activity -a -p {package} activities", check=False)


def display_of(package):
    m = re.search(r"Display #(\d+) \(activities from top to bottom\):\n\s+\* Task\{[^\n]*" + re.escape(package), activities(package))
    return int(m.group(1)) if m else None


def letterboxed(package):
    """The reason Android letterboxes the app's top activity, or None."""
    m = re.search(r"letterboxReason=(\S+)", activities(package))
    reason = m.group(1) if m else None
    return None if reason in (None, "NOT_LETTERBOXED") else reason


def open_from_menu(text):
    sh(f"input tap {APPS_BUTTON[0]} {APPS_BUTTON[1]}")
    time.sleep(1.2)
    sh(f"input text {text}")
    time.sleep(1.2)
    sh("input keyevent KEYCODE_ENTER")


def wait_for(check, timeout=14, step=0.5):
    end = time.time() + timeout
    while time.time() < end:
        value = check()
        if value:
            return value
        time.sleep(step)
    return None


def set_lx_frame(ui):
    """Through LX's data, read back from LX: the chosen frame is what apps must follow."""
    text = sh(f"run-as {PKG} cat files/layout.lxl", check=False)
    return '"apps.windowFrame":"lx"' in text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-framed")
    ap.add_argument("--cycles", type=int, default=3)
    ap.add_argument("--apps", default=DEFAULT_APPS)
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
    apps = [tuple(pair.split("=")) for pair in args.apps.split(",")]
    screen = re.search(r"(\d+)x(\d+)", sh("wm size"))
    width = int(screen.group(1))
    pid_before = None
    try:
        sh("logcat -c", check=False)
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(4)
        pid_before = sh(f"pidof {PKG}", check=False).strip()
        # The owner's own app windows stay open through the run; only displays this test made must be gone.
        owners = set(displays())
        step("the LX frame is the chosen setting", set_lx_frame(ui))

        for cycle in range(1, args.cycles + 1):
            for text, package in apps:
                before = set(displays())
                open_from_menu(text)
                shown = wait_for(lambda: display_of(package))
                new = set(displays()) - before
                size = displays().get(shown) if shown else None
                step(f"cycle {cycle}: {text} opens on a display of its own", bool(shown and (new or shown in before)), f"display={shown} size={size}")
                if not shown:
                    continue
                # LX's window is 688 px wide on this phone once the keyboard of the search is gone.
                full = wait_for(lambda: (lambda s: s if s and s[0] >= width - 40 and s[1] > 900 else None)(displays().get(shown)), timeout=6)
                step(f"cycle {cycle}: {text} fills the maximised window", bool(full), f"size={full}")
                step(f"cycle {cycle}: Android letterboxes nothing of {text}", wait_for(lambda: letterboxed(package) is None, timeout=4) is True, f"reason={letterboxed(package)}")

                if cycle == 1:
                    # Floating: the window gets smaller than a phone, the app keeps a phone layout in full.
                    sh("input tap 521 243")
                    time.sleep(2.5)
                    win = ui.wait(regex=r"^Jendela ", timeout=4)
                    top = [n for n in win if n["pkg"] == PKG][-1] if win else None
                    if top:
                        x1, y1, x2, y2 = top["bounds"]
                        sh(f"input swipe {x2 - 3} {y2 - 3} {x1 + 420} {y1 + 500} 700")
                        time.sleep(2.5)
                    floating = displays().get(shown)
                    step(f"{text}: a small floating window keeps a phone-sized display", bool(floating) and min(floating) >= 640, f"size={floating}")
                    step(f"{text}: nothing is letterboxed in the small window", wait_for(lambda: letterboxed(package) is None, timeout=5) is True, f"reason={letterboxed(package)}")
                    win = ui.wait(regex=r"^Jendela ", timeout=4)
                    top = [n for n in win if n["pkg"] == PKG][-1] if win else None
                    if top:
                        # Back to maximised through the title bar, as a person would: double tap.
                        x1, y1, x2, y2 = top["bounds"]
                        sh(f"input tap {x1 + 90} {y1 + 50}")
                        time.sleep(0.12)
                        sh(f"input tap {x1 + 90} {y1 + 50}")
                        time.sleep(2.5)

                # Close through the frame's red button.
                sh("input tap 630 243")
                gone = wait_for(lambda: shown not in displays(), timeout=8)
                step(f"cycle {cycle}: closing {text} removes its display", gone is True)
                time.sleep(1)

        crash = sh("logcat -d -b crash", check=False)
        step("no crash from LX or lxdb", PKG not in crash and "lxdb" not in crash, crash[-300:] if PKG in crash or "lxdb" in crash else "")
        anr = [line for line in sh("logcat -d -s ActivityManager:E", check=False).splitlines() if f"ANR in {PKG}" in line]
        step("no ANR from LX", not anr, " ".join(anr[-2:]))
        step("LX kept its process through the run", sh(f"pidof {PKG}", check=False).strip() == pid_before, pid_before or "")
        step("no app display of this test is left", not set(displays()) - owners, str(displays()))
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
