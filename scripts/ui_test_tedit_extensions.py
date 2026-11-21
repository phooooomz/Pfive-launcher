#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Installs a real Open VSX extension through Tedit's own screens on a phone and measures how much the screen flickers.

The owner saw Tedit's screen flicker while an extension installed (13 November 2026). This test does what they did:
open Tedit, open Extensions, search the marketplace, press Install, once from the list and once from an extension's
own page. The screen is recorded meanwhile, and every frame of the recording is compared with the one before it in
the places that should stay still while an install runs: the icons of the extensions in the list, the large icon
on the extension's page, and the text under it. A place counts a flicker each time it changes and changes back
within a few frames. The finished install is also checked: the row reads Installed and the extension is on disk.

LX's files and preferences are copied off the phone first and put back at the end, so the extensions installed
here and any Tedit setting touched go away again. Needs the marketplace allowed in Tedit, as the owner has it.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_tedit_extensions.py --out build/ui-test-ext [--query yaml]
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_test import PKG, Ui, adb, sh  # noqa: E402
from ui_test_file_manager import restore_data, save_data  # noqa: E402

TEDIT_WINDOW = r"^(Window|Jendela) Tedit$"
EXTENSIONS = r"^(Extensions|Ekstensi)$"
MARKET = r"^Marketplace$"
SEARCH_FIELD = r"^(Search extensions|Cari ekstensi)$"
INSTALL = r"^(Install|Pasang)$"
INSTALLING = r"^(Installing|Memasang)"
SEARCH_OPEN = r"^(Search|Cari)$"


def inside(n, box):
    x1, y1, x2, y2 = n["bounds"]
    return box[0] <= x1 and x2 <= box[2] and box[1] <= y1 and y2 <= box[3]


def record(path, seconds):
    """Starts screenrecord on the phone; returns the process. The video is written on the phone until it stops."""
    serial = os.environ.get("ANDROID_SERIAL")
    cmd = ["adb"] + (["-s", serial] if serial else []) + ["shell", "screenrecord", "--bit-rate", "12000000",
                                                           "--time-limit", str(seconds), path]
    return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def frames(video, box, fps):
    """Grey frames of [box] (x1, y1, x2, y2) in the video, one bytes object per frame."""
    x1, y1, x2, y2 = box
    w, h = x2 - x1, y2 - y1
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", video, "-vf", f"fps={fps},crop={w}:{h}:{x1}:{y1},format=gray",
                          "-f", "rawvideo", "-"], capture_output=True, timeout=300).stdout
    n = w * h
    return [raw[i:i + n] for i in range(0, len(raw) - n + 1, n)], w, h


def flickers(seq, threshold=6.0):
    """How often a region changed and came back. Returns (flickers, changes, worst mean difference)."""
    def diff(a, b):
        return sum(abs(p - q) for p, q in zip(a[::7], b[::7])) / max(1, len(a[::7]))
    changes, worst, fl = 0, 0.0, 0
    for i in range(1, len(seq)):
        d = diff(seq[i - 1], seq[i])
        worst = max(worst, d)
        if d > threshold:
            changes += 1
            # A flicker: the region changes, then within three frames looks like it did before the change.
            for j in range(i + 1, min(len(seq), i + 4)):
                if diff(seq[i - 1], seq[j]) <= threshold:
                    fl += 1
                    break
    return fl, changes, round(worst, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-ext")
    ap.add_argument("--query", default="yaml")
    ap.add_argument("--page-query", default="markdownlint")
    ap.add_argument("--seconds", type=int, default=45)
    ap.add_argument("--fps", type=int, default=30)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    ui = Ui(a.out)
    results = []

    def step(name, ok, evidence):
        results.append({"step": name, "ok": bool(ok), "evidence": evidence})
        print(("PASS " if ok else "FAIL ") + name + ": " + str(evidence), flush=True)

    backup = os.path.join(a.out, "lx-data-before.tar")
    save_data(backup)
    sh("logcat -c", check=False)
    log = subprocess.Popen(["adb"] + (["-s", os.environ["ANDROID_SERIAL"]] if os.environ.get("ANDROID_SERIAL") else [])
                           + ["logcat", "-v", "threadtime"], stdout=open(os.path.join(a.out, "logcat.txt"), "w"),
                           stderr=subprocess.DEVNULL)
    try:
        sh("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME", check=False)
        time.sleep(2.5)
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(1.5)
        # Tedit through LX's search, as a person opens it.
        found = ui.wait(regex=SEARCH_OPEN, timeout=10)
        if not found:
            raise RuntimeError("LX search button not found")
        ui.tap(found[0])
        sh("input text Tedit", check=False)
        time.sleep(1.2)
        hits = [n for n in ui.find(regex=r"^Tedit$") if n["text"]]
        if not hits:
            raise RuntimeError("Tedit not in search results")
        ui.tap(hits[-1])
        win = ui.wait(regex=TEDIT_WINDOW, timeout=12)
        step("Tedit opens from search", win, win[0]["bounds"] if win else "no Tedit window")
        if not win:
            return 1
        box = win[0]["bounds"]
        # A terminal left open from an earlier session takes the height the list needs; close it with the rail's button.
        handle = [n for n in ui.find(regex=r"^(Ubah tinggi terminal|Resize terminal)$") if inside(n, box)]
        if handle:
            # The terminal's own close button: the lowest "close panel" in the window, on the terminal's bar.
            close = [n for n in ui.nodes if re.match(r"^(Tutup panel|Close panel)$", n["desc"] or "") and inside(n, box)
                     and n["bounds"][1] > handle[0]["bounds"][1]]
            if close:
                ui.tap(close[0])
                time.sleep(1.0)
        ext = [n for n in ui.find(regex=EXTENSIONS) if inside(n, box)]
        if not ext:
            raise RuntimeError("Extensions button not found in Tedit")
        ui.tap(ext[0])
        time.sleep(1.0)
        market = [n for n in ui.find(regex=MARKET) if inside(n, box)]
        if market:
            ui.tap(market[0])
        for kind, query in (("list", a.query), ("page", a.page_query)):
            # The field shows its hint only while empty; after a search uiautomator reports the text typed instead.
            field = [n for n in ui.find(regex=SEARCH_FIELD) if inside(n, box)] or \
                [n for n in ui.nodes if n["cls"].endswith("EditText") and inside(n, box)]
            if not field:
                raise RuntimeError("marketplace search field not found")
            ui.tap(field[0])
            sh("input keyevent KEYCODE_MOVE_END; input keyevent --longpress KEYCODE_DEL", check=False)
            for _ in range(30):
                sh("input keyevent KEYCODE_DEL", check=False)
            sh(f"input text {query}", check=False)
            sh("input keyevent KEYCODE_BACK", check=False)  # keyboard down, so the list has the window to itself
            time.sleep(4.0)
            rows = []
            for _ in range(10):
                rows = [n for n in ui.find(regex=r", ") if inside(n, box) and n["clickable"]
                        and n["bounds"][3] - n["bounds"][1] > 60]
                if rows:
                    break
                time.sleep(1.0)
            target = None
            for r in rows:
                buttons = [n for n in ui.find(regex=INSTALL, refresh=False) if inside(n, r["bounds"]) and n["clickable"]]
                if buttons:
                    target = (r, buttons[0])
                    break
            if target is None:
                ui.shot(f"no-install-{kind}")
                labels = [n["desc"] or n["text"] for n in ui.nodes if inside(n, box) and (n["desc"] or n["text"])][:40]
                step(f"{kind}: an extension to install", False, f"no row with Install for '{query}' ({len(rows)} rows); on screen: {labels}")
                continue
            row, button = target
            name = row["desc"].split(",")[0]
            if kind == "page":
                ui.tap(row)
                time.sleep(3.0)
                # The README can have its own "Install" heading; the button is the clickable one nearest the top.
                button = min((n for n in ui.find(regex=INSTALL) if inside(n, box) and n["clickable"]),
                             key=lambda n: n["bounds"][1], default=None)
                if button is None:
                    step("page: Install on the extension's page", False, "no Install button")
                    continue
            # Places that must stay still during the install. In the list: every visible row's icon (36 dp square at
            # the row's left after 12 dp of padding). On the page: the 56 dp icon and the text block beside it.
            dpx = 2.0
            if kind == "list":
                still = []
                for r in rows:
                    x1, y1, x2, y2 = r["bounds"]
                    ix, iy = x1 + int(12 * dpx), y1 + int(10 * dpx)
                    if iy + int(36 * dpx) <= y2:
                        still.append(("icon " + r["desc"].split(",")[0], (ix, iy, ix + int(36 * dpx), iy + int(36 * dpx))))
            else:
                bx1, by1, bx2, by2 = box
                head_top = button["bounds"][1] - int(140 * dpx)
                still = [("page icon", (bx1 + int(16 * dpx), max(by1, head_top), bx1 + int(16 * dpx) + int(56 * dpx),
                                        max(by1, head_top) + int(56 * dpx))),
                         ("page body under the buttons", (bx1 + int(8 * dpx), button["bounds"][3] + int(8 * dpx),
                                                          bx2 - int(8 * dpx), min(by2, button["bounds"][3] + int(220 * dpx))))]
            if kind == "page":
                # The page's own icon: the 56 dp image at the top of the page.
                ui.dump()
                big = [n for n in ui.nodes if n["cls"].endswith("ImageView") and inside(n, box)
                       and abs((n["bounds"][2] - n["bounds"][0]) - int(56 * dpx)) <= 4]
                if big:
                    still[0] = ("page icon", big[0]["bounds"])
            before = set(sh(f"run-as {PKG} ls files/tedit/extensions", check=False).split())
            remote = f"/sdcard/Download/lx-ext-{kind}.mp4"
            sh(f"rm -f {remote}", check=False)
            rec = record(remote, a.seconds)
            time.sleep(1.5)
            started = time.time()
            ui.tap(button)
            done = False
            new = set()
            while time.time() - started < a.seconds - 3:
                # Finished when the extension is on disk and no install label is left on screen.
                new = set(sh(f"run-as {PKG} ls files/tedit/extensions", check=False).split()) - before
                if new and not [n for n in ui.find(regex=INSTALLING) if n["text"]]:
                    done = True
                    break
                time.sleep(0.5)
            took = round(time.time() - started, 1)
            time.sleep(1.0)
            sh("pkill -INT screenrecord", check=False)
            rec.wait(timeout=30)
            time.sleep(1.0)
            video = os.path.join(a.out, f"install-{kind}.mp4")
            adb("pull", remote, video, check=False)
            sh(f"rm -f {remote}", check=False)
            step(f"{kind}: {name} installs", done, f"{took} s; new on disk: {sorted(new)}")
            if not os.path.exists(video):
                step(f"{kind}: screen recording", False, "no video")
                continue
            for label, region in still:
                seq, _, _ = frames(video, region, a.fps)
                fl, ch, worst = flickers(seq)
                step(f"{kind}: {label} stays still during the install", fl == 0,
                     f"{fl} flickers, {ch} changes in {len(seq)} frames, largest mean change {worst}")
            if kind == "page":
                back = [n for n in ui.find(regex=r"^(Back|Kembali)$") if inside(n, box)]
                if back:
                    ui.tap(back[0])
                    time.sleep(1.0)
    finally:
        log.terminate()
        restore_data(backup)
        sh("input keyevent KEYCODE_HOME", check=False)
        with open(os.path.join(a.out, "results.json"), "w") as f:
            json.dump(results, f, indent=1)
    crashes = subprocess.run(["grep", "-c", "-E", "FATAL EXCEPTION|ANR in com.lxlauncher",
                              os.path.join(a.out, "logcat.txt")], capture_output=True, text=True).stdout.strip()
    print(f"crash or ANR lines in the log: {crashes}")
    failed = [r for r in results if not r["ok"]]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
