#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""The phone's keyboard for apps in LX windows, on a real phone with lxdb running. Safe for the owner's own phone.

It opens the test APK's two probe apps from LX's Applications menu, as a person would, and taps them through the LX
window: the text field, the button and the empty area, several rounds. The probe logs what the app sees (keyboard up or
down, the text that arrived, button presses), so every check reads the app's side. Screenshots are taken only to find
the probe's three colours on the screen; they are not kept. LX's files are copied off first and put back at the end.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_keyboard.py --out build/ui-test-keyboard [--rounds 5]
Needs LX's debug APK and the test APK installed.
"""
import argparse
import io
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_test import PKG, Ui, sh  # noqa: E402
from ui_test_file_manager import adb_cmd, restore_data, save_data  # noqa: E402
from ui_test_framed_apps import APPS_BUTTON, display_of, displays, open_from_menu, wait_for  # noqa: E402

PROBE = "com.lxlauncher.test"
FIELD, BUTTON, EMPTY = (255, 0, 255), (0, 200, 83), (41, 98, 255)
# The navigation bar's Back and the maximised window's close button on the test phone (720x1600, three buttons).
NAV_BACK = (540, 1560)
CLOSE = (630, 243)


def probe_lines():
    return [line.split(": ", 1)[-1].strip() for line in sh("logcat -d -s LxKeyProbe:I", check=False).splitlines() if ": " in line]


def keyboard_up(lines):
    states = [line for line in lines if line.startswith("keyboard ")]
    return bool(states) and states[-1] == "keyboard true"


def regions(keep=None):
    """Centres of the probe's three colours on the screen, from one screenshot (saved to [keep] when given)."""
    from PIL import Image
    png = subprocess.run(adb_cmd() + ["exec-out", "screencap -p"], capture_output=True, timeout=30).stdout
    if keep:
        with open(keep, "wb") as f:
            f.write(png)
    image = Image.open(io.BytesIO(png)).convert("RGB")
    w, h = image.size
    pixels = image.load()
    found = {}
    for name, colour in (("field", FIELD), ("button", BUTTON), ("empty", EMPTY)):
        xs, ys = [], []
        for y in range(0, h, 6):
            for x in range(0, w, 6):
                p = pixels[x, y]
                if all(abs(p[i] - colour[i]) < 24 for i in range(3)):
                    xs.append(x)
                    ys.append(y)
        if len(xs) > 20:
            xs.sort()
            ys.sort()
            found[name] = (xs[len(xs) // 2], ys[len(ys) // 2])
    return found


def tap(point):
    sh(f"input tap {point[0]} {point[1]}")


def lx_uid():
    m = re.search(r"uid:(\d+)", sh(f"pm list packages -U {PKG}", check=False))
    return m.group(1) if m else "?"


def lx_has_keyboard(uid):
    """The keyboard is up and its client is LX's own window, not an app's."""
    ime = sh("dumpsys input_method", check=False)
    return "mInputShown=true" in ime and re.search(rf"mCurClient=\S+ mUid={uid} ", ime) is not None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-keyboard")
    ap.add_argument("--rounds", type=int, default=5)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    results = []

    def step(name, ok, evidence=""):
        results.append({"step": name, "result": "PASS" if ok else "FAIL", "evidence": evidence})
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  {evidence}", flush=True)
        return ok

    if "uid=" not in sh(f"run-as {PKG} id", check=False):
        print("run-as is not available for LX; this test refuses to run without a way to restore LX's data")
        return 2
    if PROBE not in sh(f"pm list packages {PROBE}", check=False):
        print("the test APK is not installed")
        return 2
    if not sh("pgrep -f '[l]iblxdb.so daemon'", check=False).strip():
        print("lxdb is not running; connect LX Bridge first")
        return 2
    backup = os.path.join(args.out, "lx-data-before.tar")
    save_data(backup)
    try:
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(4)

        # ---- a field the person taps ----
        sh("logcat -c", check=False)
        open_from_menu("lxprobetype")
        shown = wait_for(lambda: display_of(PROBE))
        step("the probe opens in an LX window", bool(shown), f"display={shown}")
        time.sleep(2.5)
        for r in range(1, args.rounds + 1):
            sh("logcat -c", check=False)
            where = regions()
            if "field" not in where:
                # Kept to see why: the screen shows only LX and the probe.
                regions(os.path.join(args.out, f"round-{r}-missing.png"))
                step(f"round {r}: the probe is on the screen", False, str(where))
                break
            tap(where["field"])
            up = wait_for(lambda: keyboard_up(probe_lines()), timeout=4)
            step(f"round {r}: a tap on the field brings the keyboard", bool(up), " | ".join(probe_lines()[-4:]))
            word = f"lx{r}x"
            sh(f"input text {word}")
            typed = wait_for(lambda: any(line.startswith("text ") and line.endswith(word) for line in probe_lines()), timeout=4)
            step(f"round {r}: typed text reaches the field", bool(typed))
            time.sleep(1)
            where = regions()
            if "button" in where:
                tap(where["button"])
                pressed = wait_for(lambda: "pressed" in probe_lines(), timeout=3)
                time.sleep(1)
                step(f"round {r}: the app's button works and the keyboard stays", bool(pressed) and keyboard_up(probe_lines()),
                     " | ".join(probe_lines()[-3:]))
            where = regions()
            if "empty" in where:
                tap(where["empty"])
                time.sleep(1.2)
                step(f"round {r}: a tap on empty space keeps the keyboard", keyboard_up(probe_lines()), " | ".join(probe_lines()[-3:]))
            tap(NAV_BACK)
            down = wait_for(lambda: not keyboard_up(probe_lines()), timeout=4)
            step(f"round {r}: Back puts the keyboard away and keeps the app", bool(down) and display_of(PROBE) == shown)
            if display_of(PROBE) is None:
                open_from_menu("lxprobetype")
                shown = wait_for(lambda: display_of(PROBE))
                time.sleep(2.5)
            time.sleep(1)

        # ---- LX's own field, right after typing in the app ----
        where = regions()
        if "field" in where:
            tap(where["field"])
            wait_for(lambda: keyboard_up(probe_lines()), timeout=4)
        sh(f"input tap {APPS_BUTTON[0]} {APPS_BUTTON[1]}")
        time.sleep(1.5)
        ui = Ui(args.out)
        fields = [n for n in ui.wait(regex=r".", timeout=4) if n["pkg"] == PKG and n["cls"].endswith("EditText")]
        if fields:
            ui.tap(fields[0])
        uid = lx_uid()
        own = wait_for(lambda: lx_has_keyboard(uid), timeout=4)
        step("LX's own search field gets the keyboard after typing in an app", bool(fields) and bool(own), f"fields={len(fields)}")
        sh("input keyevent KEYCODE_BACK", check=False)
        time.sleep(0.5)
        sh("input keyevent KEYCODE_BACK", check=False)
        time.sleep(1)
        tap(CLOSE)
        step("closing the window ends the probe", wait_for(lambda: display_of(PROBE) is None, timeout=8) is True)
        time.sleep(1.5)

        # ---- a screen that opens with its keyboard up ----
        sh("logcat -c", check=False)
        open_from_menu("lxprobeauto")
        auto = wait_for(lambda: display_of(PROBE))
        up = wait_for(lambda: keyboard_up(probe_lines()), timeout=6)
        step("a screen that asks for the keyboard as it opens gets it", bool(auto) and bool(up), " | ".join(probe_lines()[-4:]))
        tap(CLOSE)
        wait_for(lambda: display_of(PROBE) is None, timeout=8)
        time.sleep(1.5)

        crash = sh("logcat -d -b crash", check=False)
        step("no crash from LX, lxdb or the probe", not any(p in crash for p in (PKG, "lxdb")), crash[-300:])
        step("no probe display is left", display_of(PROBE) is None, str(displays()))
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
