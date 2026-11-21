#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""The keys along the edges of an app's keyboard in a maximised LX window, on a real phone with lxdb running.

The owner reported the keyboard closing when they touched it. The cause was LX's resize border lying over the app's
outer pixels and the bands beside a maximised window: a tap there went to LX, which took the keyboard. This opens the
test APK's probe from LX's Applications menu, brings up the phone's keyboard in it and taps 1, 2 and 3 dp inside the
window's left, right and bottom edges (q, p, a, l, Delete and the space bar), then the band beside the window, its
border and its title. Each tap must reach the app or leave it alone, and the keyboard must stay up.

Screenshots stay in memory: they are only scanned for the probe's colours and the keyboard's rows. Nothing is tapped
below the window, where LX's bar opens apps. LX's files are copied off first and put back at the end.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_keyboard_edges.py --out build/ui-test-keyboard-edges
Needs LX's debug APK, the test APK and Gboard; written for a 720x1600 phone with three-button navigation.
"""
import argparse
import io
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_test import PKG, sh  # noqa: E402
from ui_test_file_manager import adb_cmd, restore_data, save_data  # noqa: E402
from ui_test_framed_apps import display_of, open_from_menu, wait_for  # noqa: E402
from ui_test_keyboard import BUTTON, CLOSE, EMPTY, FIELD, NAV_BACK, PROBE, keyboard_up, probe_lines  # noqa: E402


def near(p, c, tol=24):
    return all(abs(p[i] - c[i]) < tol for i in range(3))


def grab():
    from PIL import Image
    png = subprocess.run(adb_cmd() + ["exec-out", "screencap -p"], capture_output=True, timeout=30).stdout
    return Image.open(io.BytesIO(png)).convert("RGB")


def last_text():
    """The probe's text as last logged. Read raw: probe_lines() strips each line, and with it a typed trailing space."""
    out = sh("logcat -d -s LxKeyProbe:I", check=False).splitlines()
    texts = [line.split(": text ", 1)[1].rstrip("\r\n") for line in out if ": text " in line]
    return texts[-1] if texts else ""


def lx_gave_keyboard_back():
    return sum("back to LX" in line for line in sh("logcat -d -s LxInput:D", check=False).splitlines())


def window(img):
    """The app's content on the screen: its left, right and top edges, and where the keyboard starts below the probe."""
    w, h = img.size
    px = img.load()
    col = int(w * 0.9)
    ys = [y for y in range(h) if near(px[col, y], FIELD)]
    if not ys:
        return None
    mid = (ys[0] + ys[-1]) // 2
    xs = [x for x in range(w) if near(px[x, mid], FIELD)]
    y = ys[-1]
    while y + 1 < h and any(near(px[col, y + 1], c) for c in (FIELD, BUTTON, EMPTY)):
        y += 1
    return {"left": xs[0], "right": xs[-1], "top": ys[0], "keyboard": y + 1}


def keyboard_rows(img, g):
    """The keyboard's four key rows and the window's bottom edge, the end of the keyboard's background under them."""
    px = img.load()
    background = px[g["left"] + 1, g["keyboard"] + 4]
    x = g["left"] + int((g["right"] - g["left"]) * 0.32)
    runs, start = [], None
    for y in range(g["keyboard"], img.size[1]):
        key = not near(px[x, y], background, 14)
        if key and start is None:
            start = y
        elif not key and start is not None:
            runs.append((start, y - 1))
            start = None
    rows = [r for r in runs if r[1] - r[0] >= 60]
    if len(rows) < 4:
        return None, None
    # Rows below the window would be LX's bar; the keyboard's last row is the one its background closes.
    middle = (g["left"] + g["right"]) // 2
    for i in range(3, len(rows)):
        y = rows[i][1] + 1
        while y < img.size[1] and near(px[middle, y], background, 14):
            y += 1
        if y - 1 < rows[i][1] + 40:
            return rows[i - 3:i + 1], y - 1
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-keyboard-edges")
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
        sh("logcat -c", check=False)
        open_from_menu("lxprobetype")
        shown = wait_for(lambda: display_of(PROBE))
        if not step("the probe opens in an LX window", bool(shown), f"display={shown}"):
            return 1
        time.sleep(2.5)
        g = window(grab())
        if not step("the probe is on the screen", g is not None):
            return 1
        sh(f"input tap {(g['left'] + g['right']) // 2} {g['top'] + 40}")
        step("a tap on the field brings the keyboard", bool(wait_for(lambda: keyboard_up(probe_lines()), timeout=5)))
        time.sleep(1.5)
        img = grab()
        g = window(img)
        rows, bottom = keyboard_rows(img, g) if g else (None, None)
        if not step("the keyboard's four rows and the window's bottom edge are found", rows is not None,
                    f"window={g} rows={rows} bottom={bottom}"):
            return 1
        sh("logcat -c", check=False)
        mid = lambda r: (r[0] + r[1]) // 2  # noqa: E731
        key_x = g["left"] + int((g["right"] - g["left"]) * 0.45)

        def tap(name, x, y, expect=None):
            """Taps once; [expect] is the letter it should type, "del", "space", "letter" or None for no text."""
            if g["left"] <= x <= g["right"] and y > bottom:
                raise AssertionError(f"{name}: ({x},{y}) is below the window")
            old = probe_lines()
            before = last_text()
            gave = lx_gave_keyboard_back()
            sh(f"input tap {x} {y}")
            if expect is not None:
                wait_for(lambda: last_text() != before, timeout=3, step=0.3)
            time.sleep(0.6)
            lines = probe_lines()
            after = last_text()
            hidden = "keyboard false" in lines[len(old):] or lx_gave_keyboard_back() > gave
            typed = after[len(before):] if after.startswith(before) else f"<{before!r} -> {after!r}>"
            if expect == "del":
                good = len(after) == len(before) - 1
            elif expect == "space":
                good = after.endswith(" ")
            elif expect == "letter":
                good = len(typed) == 1 and typed.isalpha()
            elif expect is None:
                good = after == before
            else:
                good = typed.lower() == expect
            step(f"{name} at ({x},{y})", good and not hidden, f"typed={typed!r} keyboard_hidden={hidden}")

        # 1, 2 and 3 dp inside the content's edges; LX's old border covered about 3.5 dp there.
        dp = 2
        for d in (1, 2, 3):
            tap(f"Q row, {d} dp inside the left edge", g["left"] + d * dp - 1, mid(rows[0]), "q")
            tap(f"Q row, {d} dp inside the right edge", g["right"] - d * dp + 1, mid(rows[0]), "p")
            tap(f"A row, {d} dp inside the left edge", g["left"] + d * dp - 1, mid(rows[1]), "a")
            tap(f"A row, {d} dp inside the right edge", g["right"] - d * dp + 1, mid(rows[1]), "l")
            tap(f"Delete, {d} dp inside the right edge", g["right"] - d * dp + 1, mid(rows[2]), "del")
        for d in (1, 2, 3):
            tap("a Q row key before the space", key_x, mid(rows[0]), "letter")
            tap(f"space bar, {d} dp above the window's bottom edge", (g["left"] + g["right"]) // 2, bottom - d * dp + 1, "space")
        # Outside the content: the band beside the maximised window, its border and its title.
        tap("the band left of the window", max(2, (g["left"] - 3) // 2), mid(rows[0]))
        tap("the band right of the window", min(717, (g["right"] + 722) // 2), mid(rows[1]))
        tap("the window's left border", g["left"] - 2, mid(rows[1]))
        tap("the title", (g["left"] + g["right"]) // 2 - 80, CLOSE[1])
        time.sleep(1)
        tap("a Q row key after the frame taps", key_x, mid(rows[0]), "letter")

        step("the keyboard never went down", "keyboard false" not in probe_lines())
        step("LX never took the keyboard", lx_gave_keyboard_back() == 0)
        after = window(grab())
        step("the window did not move or resize", after is not None and all(after[k] == g[k] for k in g), f"after={after}")
        resized = [line for line in sh("logcat -d -s LxPerf:D", check=False).splitlines() if "resized" in line]
        step("no tap started a resize", not resized, " | ".join(resized[-2:]))

        sh(f"input tap {NAV_BACK[0]} {NAV_BACK[1]}")
        time.sleep(1.5)
        sh(f"input tap {CLOSE[0]} {CLOSE[1]}")
        step("closing the window ends the probe", wait_for(lambda: display_of(PROBE) is None, timeout=8) is True)
        crash = sh("logcat -d -b crash", check=False)
        step("no crash from LX, lxdb or the probe", not any(p in crash for p in (PKG, "lxdb")), crash[-300:])
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
