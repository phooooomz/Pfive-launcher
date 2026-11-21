#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Window styles at the smallest interface size. Sets Interface size to 85%,
then picks every window style from the title bar menu of LX Settings and checks
that the window stays open and LX records no crash. Compact at 85% used to throw
from TextView autosize. A screenshot of each style lands in the output folder,
for checking by eye that the window edge runs over the title bar.

Clears LX's data at the start and at the end, so run it only on a test device.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_window_styles.py --restore-home <component>
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from ui_test import ACT, PKG, Ui, bar_button, settings_tab, sh, DOCK_SETTINGS  # noqa: E402

STYLES = [("Compact / Slim", "Ringkas / Ramping", "compact"), ("Retro / Classic", "Retro / Klasik", "retro"),
          ("Minimal Dark", "Minimalis Gelap", "minimal"), ("Cyberpunk Neon", "Cyberpunk Neon", "cyberpunk"),
          ("Rounded Modern", "Rounded Modern", "rounded"), ("Tactical / Technical", "Taktis / Teknis", "tactical"),
          ("Glassmorphism", "Glassmorphism", "glass"), ("Nature Inspired", "Nuansa Alam", "nature"),
          ("Material You", "Material You", "material"), ("Hybrid / Linux Desktop", "Hybrid / Desktop Linux", "linux"),
          ("Follow the LX theme", "Ikut tema LX", "theme")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-window-styles")
    ap.add_argument("--restore-home", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ui = Ui(args.out)
    results = []

    def step(name, ok, evidence=""):
        results.append({"step": name, "result": "PASS" if ok else "FAIL", "evidence": evidence})
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  {evidence}", flush=True)
        return ok

    def window(regex):
        return ui.find(regex=rf"^(?:Window|Jendela) ({regex})$")

    def tap_re(regex, timeout=8):
        n = ui.wait(timeout=timeout, regex=regex)
        if n:
            ui.tap(n[0])
        return bool(n)

    def crash_lines():
        return [line for line in sh("logcat -d -b crash", check=False).splitlines() if PKG in line]

    try:
        sh("pm clear " + PKG, check=False)
        sh(f"cmd package set-home-activity {ACT}", check=False)
        sh("logcat -b crash -c", check=False)
        sh(f"am start -n {ACT}", check=False)
        time.sleep(4)
        # A fresh install opens the Welcome window; close it so LX Settings is the only window.
        for n in ui.find(regex=r"^(Close|Tutup)$"):
            ui.tap(n)
            time.sleep(1)
        hit = bar_button(ui, DOCK_SETTINGS)
        if hit:
            ui.tap(hit[0])
        ui.wait(regex=r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$")
        settings_tab(ui, r"^(Windows and panels|Jendela dan panel)$")

        # The label above the slider has the same words; only the SeekBar itself moves the value.
        slider = []
        for _ in range(8):
            slider = [n for n in ui.find(regex=r"^(Interface size|Ukuran antarmuka)$") if n["cls"].endswith("SeekBar")]
            w = window("LX Settings|Pengaturan LX")
            if slider or not w:
                break
            x1, y1, x2, y2 = w[0]["bounds"]
            sh(f"input swipe {(x1 + x2) // 2} {y2 - 80} {(x1 + x2) // 2} {y1 + 200} 350")
            time.sleep(0.6)
        if slider:
            x1, y1, x2, y2 = slider[0]["bounds"]
            cy = (y1 + y2) // 2
            sh(f"input swipe {(x1 + x2) // 2} {cy} {max(0, x1 - 60)} {cy} 600")
            time.sleep(3)
        # The window is rebuilt at the new size; read the value from the slider's own row, so a battery
        # level of 85% elsewhere on screen cannot pass for it.
        small = []
        for _ in range(6):
            bar = [n for n in ui.find(regex=r"^(Interface size|Ukuran antarmuka)$") if n["cls"].endswith("SeekBar")]
            if bar and bar[0]["ancestors"]:
                row = bar[0]["ancestors"][-1]
                small = [n for n in ui.nodes if re.fullmatch(r"85\s?%", n["text"] or "") and row in n["ancestors"]]
            if small:
                break
            time.sleep(1)
        step("Interface size goes down to 85%", bool(small), small[0]["text"] if small else "85% not shown beside the slider")

        for en, idn, sid in STYLES:
            w = window("LX Settings|Pengaturan LX")
            if not w:
                step(f"{en} at 85%", False, "LX Settings window gone")
                break
            b = w[0]["bounds"]
            sh(f"input swipe {b[0] + 150} {b[1] + 20} {b[0] + 150} {b[1] + 20} 900")
            time.sleep(1)
            names = f"{re.escape(en)}|{re.escape(idn)}"
            picked = tap_re(r"^(Window style|Gaya jendela)$", 4) and tap_re(rf"^({names})$", 4)
            time.sleep(3)
            alive = bool(window("LX Settings|Pengaturan LX"))
            crashed = crash_lines()
            if crashed:
                # Cleared once recorded, so the next style is judged on its own.
                sh("logcat -b crash -c", check=False)
            ui.shot(f"85-{sid}")
            evidence = "; ".join(crashed[:2]) if crashed else ("" if alive else "window gone")
            step(f"{en} at 85% keeps LX Settings open without a crash", picked and alive and not crashed,
                 evidence or ("" if picked else "not in the menu"))
    finally:
        sh(f"cmd package set-home-activity {args.restore_home}", check=False)
        sh("pm clear " + PKG, check=False)
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(2)
        restored = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                      check=False).strip().splitlines()[-1]
        step("Home restored to the previous launcher", restored == args.restore_home, restored)

    with open(os.path.join(args.out, "results.json"), "w") as f:
        json.dump(results, f, indent=2)
    failed = [r for r in results if r["result"] == "FAIL"]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
