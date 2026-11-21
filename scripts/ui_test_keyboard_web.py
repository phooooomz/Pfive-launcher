#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Typing on the phone's keyboard, key by key, into a browser-engine app in an LX window. Safe for the owner's phone.

The owner's Brave put its keyboard away after the first key they touched (Android's own keyboard history on their phone,
12 November 2026). The older keyboard tests typed with `input text`, which never touches the keyboard, into a plain
text field. This one opens the test APK's web probe (a WebView, the same engine as Chrome and Brave) from LX's
Applications menu, taps its field, then taps letter keys on the keyboard drawn in the window, one at a time and then in
quick bursts, and after each checks that the letter arrived and the keyboard stayed. It records what the app saw (its
window focus, the page's focus and blur), LX's keyboard hand-offs and Android's reasons for each keyboard hide.

Screenshots stay in memory: they are only scanned for the probe's colours and the keyboard's rows. LX's files are
copied off first and put back at the end.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_keyboard_web.py --out build/ui-test-keyboard-web [--keys 50] [--bursts 5]
Needs LX's debug APK, the test APK and Gboard; written for a 720x1600 phone with three-button navigation.
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_test import PKG, sh  # noqa: E402
from ui_test_file_manager import restore_data, save_data  # noqa: E402
from ui_test_framed_apps import display_of, open_from_menu, wait_for  # noqa: E402
from ui_test_keyboard import BUTTON, EMPTY, FIELD, NAV_BACK, PROBE, keyboard_up, probe_lines  # noqa: E402
from ui_test_keyboard_edges import grab, keyboard_rows, last_text, near, window  # noqa: E402


def page(img):
    """The page's edges and where the keyboard starts under it. The focused field's outline breaks the probe's colours
    for a few pixels, so the keyboard starts where they stop for good, not at the first other pixel."""
    g = window(img)
    if g is None:
        return None
    w, h = img.size
    px = img.load()
    col = int(w * 0.9)
    last, gap, y = g["top"], 0, g["top"]
    while y < h and gap <= 8:
        if any(near(px[col, y], c) for c in (FIELD, BUTTON, EMPTY)):
            last, gap = y, 0
        else:
            gap += 1
        y += 1
    g["keyboard"] = last + 1
    return g


def lx_input():
    return [line.split(": ", 1)[-1].strip() for line in sh("logcat -d -s LxInput:D", check=False).splitlines() if ": " in line]


def ime_hides():
    """Android's record of each keyboard hide since the log was cleared: who asked and why."""
    out = sh("logcat -d -s ImeTracker:I", check=False).splitlines()
    return [line.split(": ", 1)[-1].strip() for line in out if "onRequestHide" in line or "onHidden" in line]


def close_window(g):
    """Taps the window's close button: the red one of the three caption dots above the page, in the owner's style."""
    img = grab()
    px = img.load()
    hits = [(x, y) for y in range(max(0, g["top"] - 220), g["top"], 2) for x in range((g["left"] + g["right"]) // 2, g["right"], 2)
            if px[x, y][0] > 200 and px[x, y][1] < 110 and px[x, y][2] < 110]
    if not hits:
        return False
    sh(f"input tap {sum(h[0] for h in hits) // len(hits)} {sum(h[1] for h in hits) // len(hits)}")
    return wait_for(lambda: display_of(PROBE) is None, timeout=8) is True


def key_rows(img, g):
    """The keyboard's key rows under the page, top first, for a keyboard that runs down to the navigation bar."""
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
    return [r for r in runs if r[1] - r[0] >= 60]


def button_of(img, g):
    """The middle of the page's Send button, under the field."""
    px = img.load()
    x = (g["left"] + g["right"]) // 2
    ys = [y for y in range(g["top"], g["keyboard"]) if near(px[x, y], BUTTON)]
    return (x, (ys[0] + ys[-1]) // 2) if ys else None


def early_rounds(step, g, key, delays, where):
    """For each delay: the Send button puts the keyboard away (the page's field loses focus), the field is tapped,
    and [delay] seconds later a key. The key must type its letter and the keyboard stay up. Returns the passes."""
    field = ((g["left"] + g["right"]) // 2, g["top"] + 60)
    passed = 0
    for d in delays:
        button = button_of(grab(), g)
        if button is None:
            step(f"{where}: the Send button is on the screen", False)
            break
        sh(f"input tap {button[0]} {button[1]}")
        wait_for(lambda: not keyboard_up(probe_lines()), timeout=4)
        time.sleep(1.0)
        # The tap on the field puts the caret where it lands, inside a long text too, so the letter is counted, not
        # looked for at the end. The log starts empty for each round: the probe logs the whole text on each key.
        before = last_text()
        sh("logcat -c", check=False)
        sh(f"input tap {field[0]} {field[1]}; sleep {d}; input tap {key[0]} {key[1]}")
        time.sleep(2.0)
        new = probe_lines()
        after = last_text() if any(line.startswith("text ") for line in new) else before
        typed = len(after) - len(before)
        ok = keyboard_up(new) and "field blur" not in new and typed == 1
        passed += ok
        print(f"  {where}: key {d:.2f} s after the field tap: {'ok' if ok else 'LOST'}  letters added={typed} "
              f"probe={[line[:40] for line in new]}", flush=True)
        if not ok:
            # Back on the field, so the next round starts the same way.
            sh(f"input tap {field[0]} {field[1]}")
            wait_for(lambda: keyboard_up(probe_lines()), timeout=5)
            time.sleep(1.0)
    step(f"{where}: {len(delays)} keys tapped while the keyboard slides up type and keep it up", passed == len(delays),
         f"{passed}/{len(delays)}")
    return passed


def outside_lx(step, delays):
    """The same early keys with the probe as an ordinary Android app over the whole screen, outside LX: the line to
    compare an LX window against."""
    sh("logcat -c", check=False)
    sh(f"am start -W -n {PROBE}/.KeyboardWebProbeActivity", check=False)
    time.sleep(3)
    g = page(grab())
    if not step("outside LX: the probe's page is on the screen", g is not None, str(g)):
        return
    sh(f"input tap {(g['left'] + g['right']) // 2} {g['top'] + 60}")
    wait_for(lambda: keyboard_up(probe_lines()), timeout=6)
    time.sleep(1.5)
    img = grab()
    g = page(img)
    rows = key_rows(img, g) if g else []
    if not step("outside LX: the keyboard's rows are found", len(rows) >= 4, f"{g} {rows}"):
        return
    key = (g["left"] + int((g["right"] - g["left"]) * 0.45), (rows[0][0] + rows[0][1]) // 2)
    early_rounds(step, g, key, delays, "outside LX")
    sh(f"am force-stop {PROBE}", check=False)
    sh("input keyevent KEYCODE_HOME", check=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-keyboard-web")
    ap.add_argument("--keys", type=int, default=50, help="single key taps, each checked on its own")
    ap.add_argument("--bursts", type=int, default=5, help="bursts of 8 quick taps, as a person types")
    ap.add_argument("--early", type=int, default=14, help="keys tapped while the keyboard slides up (a multiple of 7)")
    ap.add_argument("--fresh", type=int, default=8, help="windows opened and typed into right away")
    ap.add_argument("--floating", action="store_true", help="type into the window at its floating place, scaled down")
    ap.add_argument("--outside-only", action="store_true",
                    help="only the early keys with the probe outside LX, as an ordinary app; LX is left alone")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    results = []

    def step(name, ok, evidence=""):
        results.append({"step": name, "result": "PASS" if ok else "FAIL", "evidence": evidence})
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  {evidence}", flush=True)
        return ok

    if args.outside_only:
        try:
            outside_lx(step, [0.3, 0.45, 0.6, 0.8, 1.0, 1.2, 1.5] * max(1, args.early // 7))
        finally:
            sh(f"am force-stop {PROBE}", check=False)
            sh("input keyevent KEYCODE_HOME", check=False)
        failed = [r for r in results if r["result"] == "FAIL"]
        print(f"{len(results) - len(failed)} passed, {len(failed)} failed")
        return 1 if failed else 0

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
        time.sleep(5)
        sh("logcat -c", check=False)
        open_from_menu("lxprobeweb")
        shown = wait_for(lambda: display_of(PROBE))
        if not step("the web probe opens in an LX window", bool(shown), f"display={shown}"):
            return 1
        time.sleep(3)
        g = page(grab())
        if not step("the probe's page is on the screen", g is not None, str(g)):
            return 1
        if args.floating:
            # A double tap on the title bar gives the window back its floating place, which on a phone is narrower
            # than an app's layout: the app is drawn scaled down.
            title = ((g["left"] + g["right"]) // 3, g["top"] - 50)
            sh(f"input tap {title[0]} {title[1]}; input tap {title[0]} {title[1]}")
            time.sleep(2.5)
            g = page(grab())
            if not step("the window floats at its own place", g is not None and g["right"] - g["left"] < 600, str(g)):
                return 1
        sh(f"input tap {(g['left'] + g['right']) // 2} {g['top'] + 60}")
        up = wait_for(lambda: keyboard_up(probe_lines()), timeout=6)
        step("a tap on the page's field brings the keyboard", bool(up), " | ".join(probe_lines()[-5:]))
        time.sleep(1.5)
        img = grab()
        g = page(img)
        rows, bottom = keyboard_rows(img, g) if g else (None, None)
        if rows is None and g is not None:
            found = key_rows(img, g)
            rows = found[:4] if len(found) >= 4 else None
        if not step("the keyboard's rows are found in the window", rows is not None, f"window={g} rows={rows}"):
            return 1
        mid = (rows[0][0] + rows[0][1]) // 2
        width = g["right"] - g["left"]
        # Letter keys across the Q row (w e r t y u i o), away from the window's edges and the keyboard's own borders.
        xs = [g["left"] + int(width * f) for f in (0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85)]
        sh("logcat -c", check=False)

        closed_at = None
        typed_ok = 0
        for i in range(args.keys):
            before = last_text()
            old = len(probe_lines())
            sh(f"input tap {xs[i % len(xs)]} {mid}")
            wait_for(lambda: last_text() != before, timeout=2.5, step=0.2)
            time.sleep(0.25)
            after = last_text()
            new = probe_lines()[old:]
            hidden = "keyboard false" in new or "field blur" in new
            grew = len(after) == len(before) + 1
            typed_ok += grew and not hidden
            if hidden and closed_at is None:
                closed_at = i + 1
                step(f"key {i + 1}: the letter arrives and the keyboard stays", False,
                     f"before={before!r} after={after!r} probe={new} lx={lx_input()[-3:]} ime={ime_hides()[-3:]}")
                # The person taps the field again to carry on, as the owner did.
                sh(f"input tap {(g['left'] + g['right']) // 2} {g['top'] + 60}")
                wait_for(lambda: keyboard_up(probe_lines()), timeout=6)
                time.sleep(1.2)
        step(f"{args.keys} single key taps type their letter with the keyboard up", typed_ok == args.keys and closed_at is None,
             f"typed {typed_ok}/{args.keys}, first close at key {closed_at}")

        burst_ok = 0
        for b in range(args.bursts):
            before = last_text()
            old = len(probe_lines())
            # Eight taps in a row with no wait, about as fast as `input` can send them.
            sh("; ".join(f"input tap {x} {mid}" for x in xs))
            time.sleep(1.5)
            after = last_text()
            new = probe_lines()[old:]
            hidden = "keyboard false" in new or "field blur" in new
            got = len(after) - len(before)
            ok = got == len(xs) and not hidden
            burst_ok += ok
            if not ok:
                step(f"burst {b + 1}: eight quick taps type eight letters, keyboard up", False,
                     f"got {got} probe={new[-6:]} lx={lx_input()[-3:]} ime={ime_hides()[-3:]}")
                if hidden:
                    sh(f"input tap {(g['left'] + g['right']) // 2} {g['top'] + 60}")
                    wait_for(lambda: keyboard_up(probe_lines()), timeout=6)
                    time.sleep(1.2)
        step(f"{args.bursts} bursts of quick taps all arrive with the keyboard up", burst_ok == args.bursts, f"{burst_ok}/{args.bursts}")

        # A key tapped while the keyboard is still sliding up, as soon as a person sees it.
        delays = [0.3, 0.45, 0.6, 0.8, 1.0, 1.2, 1.5] * max(1, args.early // 7)
        early_rounds(step, g, (xs[3], mid), delays, "in an LX window")

        lines = probe_lines()
        step("the app kept its window focus while typing", "focus false" not in lines,
             f"focus changes seen by the app: {sum(l.startswith('focus ') for l in lines)}")
        step("LX never took the keyboard", not any("back to LX" in l for l in lx_input()), " | ".join(lx_input()[-3:]))
        with open(os.path.join(args.out, "logs.txt"), "w") as f:
            f.write("== probe ==\n" + "\n".join(lines) + "\n== LxInput ==\n" + "\n".join(lx_input())
                    + "\n== ImeTracker hides ==\n" + "\n".join(ime_hides()) + "\n")

        sh(f"input tap {NAV_BACK[0]} {NAV_BACK[1]}")
        time.sleep(1.2)
        step("the window's close button ends the probe", close_window(g))

        # A window just opened, its field tapped while the window still settles, and typed into at once.
        fresh_ok = 0
        for r in range(args.fresh):
            sh("logcat -c", check=False)
            open_from_menu("lxprobeweb")
            if not wait_for(lambda: display_of(PROBE)):
                step(f"fresh window {r + 1} opens", False)
                break
            wait_for(lambda: page(grab()) is not None, timeout=6, step=0.2)
            time.sleep(0.3 * (r % 4))
            g2 = page(grab()) or g
            sh(f"input tap {(g2['left'] + g2['right']) // 2} {g2['top'] + 60}")
            if not wait_for(lambda: keyboard_up(probe_lines()), timeout=6, step=0.2):
                step(f"fresh window {r + 1}: the field tap brings the keyboard", False, " | ".join(probe_lines()[-5:]))
            time.sleep(0.4)
            img = grab()
            g3 = page(img)
            rows3, _ = keyboard_rows(img, g3) if g3 else (None, None)
            if rows3 is None:
                step(f"fresh window {r + 1}: the keyboard's rows are found", False, f"{g3}")
            else:
                m3 = (rows3[0][0] + rows3[0][1]) // 2
                w3 = g3["right"] - g3["left"]
                sh("; ".join(f"input tap {g3['left'] + int(w3 * f)} {m3}; sleep 0.15" for f in (0.25, 0.45, 0.65)))
                time.sleep(1.5)
                lines3 = probe_lines()
                ok = keyboard_up(lines3) and "field blur" not in lines3 and len(last_text()) == 3
                fresh_ok += ok
                if not ok:
                    step(f"fresh window {r + 1}: three keys typed right away arrive, keyboard up", False,
                         f"text={last_text()!r} probe={lines3[-8:]} lx={lx_input()[-3:]} ime={ime_hides()[-3:]}")
            sh(f"input tap {NAV_BACK[0]} {NAV_BACK[1]}")
            time.sleep(1.2)
            if not close_window(g3 or g2):
                step(f"fresh window {r + 1} closes", False)
                break
            time.sleep(1)
        if args.fresh:
            step(f"{args.fresh} windows typed into right after opening keep the keyboard", fresh_ok == args.fresh, f"{fresh_ok}/{args.fresh}")
        crash = sh("logcat -d -b crash", check=False)
        step("no crash from LX, lxdb or the probe", not any(p in crash for p in (PKG, "lxdb")), crash[-300:])
    finally:
        sh(f"am force-stop {PROBE}", check=False)
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
