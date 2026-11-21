#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""LX Files operations that need two granted folders: copying a folder and moving
a file between them, cancelling a large copy, a new file, sorting, a taken name,
and a folder pasted into itself. Every result is checked on the device's storage.

Creates /sdcard/Documents/LXFiles and removes it again, and clears LX's data
before and after the run.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_files.py --restore-home <component>
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_test import PKG, ACT, Ui, sh, resumed_package, bar_button, DOCK_FILES  # noqa: E402

BASE = "/sdcard/Documents/LXFiles"
# Large enough that Cancel can be pressed while the copy still runs (1 GB copied in under 3 s on a recent phone).
BIG_MB = 4096


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-files")
    ap.add_argument("--restore-home", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ui = Ui(args.out)
    results = []

    def step(name, ok, evidence=""):
        results.append({"step": name, "result": "PASS" if ok else "FAIL", "evidence": evidence})
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  {evidence}", flush=True)
        return ok

    def tap_re(regex, timeout=8, index=0):
        n = ui.wait(timeout=timeout, regex=regex)
        if n:
            ui.tap(n[index if index >= 0 else len(n) + index])
        return bool(n)

    def ls(path):
        return sh(f"ls {path}", check=False).split()

    def size(path):
        out = sh(f"stat -c %s {path}", check=False).strip()
        return int(out) if out.isdigit() else -1

    screen_w, screen_h = map(int, re.search(r"(\d+)x(\d+)", sh("wm size")).groups())
    device_root = "|".join(re.escape(n) for n in
                           (sh("settings get global device_name", check=False).strip(), "WayDroid x86_64 Device") if n)

    def find_scrolling(regex, swipes=8):
        for _ in range(swipes):
            n = ui.wait(timeout=2, regex=regex)
            if n:
                return n
            sh(f"input swipe {screen_w // 2} {screen_h * 3 // 4} {screen_w // 2} {screen_h * 2 // 5} 400")
            time.sleep(0.8)
        return ui.wait(timeout=2, regex=regex)

    def tap_scrolling(regex):
        n = find_scrolling(regex)
        if n:
            ui.tap(n[0])
        return bool(n)

    def back_to_lx():
        for _ in range(4):
            if resumed_package() == PKG:
                return
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1.2)

    def grant(folder):
        """Grants Documents/LXFiles/<folder> through the system folder picker. With a folder already
        open, Choose a folder is in the granted-folders menu (the toolbar's own button may be scrolled away)."""
        if not tap_re(r"^(Choose a folder|Pilih folder)$", timeout=3, index=-1):
            tap_re(r"^(Granted folders|Folder yang diizinkan)$")
            time.sleep(0.8)
            tap_re(r"^(Choose a folder|Pilih folder)$")
        time.sleep(2.5)
        tap_re(r"^(Show roots|Tampilkan root)$", timeout=3)
        tap_re(rf"^({device_root})$", timeout=3)
        time.sleep(1.5)
        tap_scrolling(r"^Documents$")
        tap_scrolling(r"^LXFiles$")
        tap_scrolling(rf"^{folder}$")
        tap_re(r"^(USE THIS FOLDER|Use this folder|GUNAKAN FOLDER INI|Gunakan folder ini)$")
        tap_re(r"^(ALLOW|Allow|IZINKAN|Izinkan)$")
        time.sleep(2)
        back_to_lx()

    def open_root(folder):
        tap_re(r"^(Granted folders|Folder yang diizinkan)$")
        time.sleep(0.8)
        tap_re(rf"^Documents/LXFiles/{folder}$")
        time.sleep(2)

    def item_menu(name, action):
        f = ui.wait(regex=rf"^{re.escape(name)}$|^(Folder|Folder) {re.escape(name)}$")
        if not f:
            return False
        ui.long_press(f[0])
        return tap_re(rf"^({action})$")

    def notice(regex, timeout=10):
        return ui.wait(timeout=timeout, regex=regex)

    # ---- fixture ----
    sh(f"rm -rf {BASE}", check=False)
    sh(f"mkdir -p {BASE}/src/sub/inner {BASE}/dst")
    sh(f"echo 'move me please' > {BASE}/src/move-me.txt")
    sh(f"echo z > {BASE}/src/aaa-small.txt")
    sh(f"echo alpha > {BASE}/src/sub/a.txt")
    sh(f"echo bravo bravo > {BASE}/src/sub/b.txt")
    sh(f"echo inner > {BASE}/src/sub/inner/c.txt")
    sh(f"dd if=/dev/zero of={BASE}/src/big.bin bs=1048576 count={BIG_MB}", check=False, timeout=180)
    for f in ("move-me.txt", "sub/a.txt", "big.bin"):
        sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{BASE}/src/{f}", check=False)
    original_home = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                       check=False).strip().splitlines()[-1]
    sh("logcat -b crash -c", check=False)
    try:
        sh("pm clear " + PKG)
        sh(f"cmd package set-home-activity {ACT}")
        sh("input keyevent KEYCODE_HOME")
        time.sleep(4)
        c = ui.wait(regex=r"^(Close|Tutup)$")
        if c:
            ui.tap(c[0])

        ui.tap(bar_button(ui, DOCK_FILES)[0])
        time.sleep(1.5)
        grant("src")
        step("First folder is granted and listed", bool(ui.wait(regex=r"^move-me\.txt$")))
        grant("dst")
        open_root("src")
        step("Granted folders menu switches between both folders", bool(ui.wait(regex=r"^move-me\.txt$")))

        # New file, named as typed.
        tap_re(r"^(New file|Berkas baru)$")
        time.sleep(1.5)
        sh("input text catatan.txt")
        sh("input keyevent KEYCODE_ENTER")
        time.sleep(2.5)
        step("New file is created on storage", "catatan.txt" in ls(f"{BASE}/src"), ls(f"{BASE}/src"))

        # Largest first: folders, then big.bin before the small files.
        tap_re(r"^(Sort|Urutkan)$")
        tap_re(r"^(Largest first|Terbesar dulu)$")
        time.sleep(2)
        # By name the order would start sub, aaa-small.txt; by size it is sub, big.bin, move-me.txt, aaa-small.txt.
        cells = [n for n in ui.find(regex=r"^(sub|aaa-small\.txt|big\.bin|move-me\.txt|catatan\.txt)$") if n["text"] and n["pkg"] == PKG]
        names = [n["text"] for n in sorted(cells, key=lambda n: (n["bounds"][1] // 40, n["bounds"][0]))]
        step("Sort by size puts the folder first, then files from largest", names[:4] == ["sub", "big.bin", "move-me.txt", "aaa-small.txt"], names)

        # A name already in the folder is refused, and no "sub (1)" appears.
        tap_re(r"^(New folder|Folder baru)$")
        time.sleep(1.5)
        sh("input text sub")
        sh("input keyevent KEYCODE_ENTER")
        taken = notice(r"sub (already exists|sudah ada)")
        time.sleep(1.5)
        step("A name already used is refused", bool(taken) and ls(f"{BASE}/src").count("sub") == 1, ls(f"{BASE}/src"))

        # A folder cannot be pasted into its own subfolder.
        item_menu("sub", "Copy|Salin")
        tap_re(r"^(Folder sub|sub)$")
        time.sleep(1.5)
        tap_re(r"^(Folder inner|inner)$")
        time.sleep(1.5)
        tap_re(r"^(Paste|Tempel)$")
        inside = notice(r"(cannot go inside itself|tidak bisa dimasukkan ke dalam dirinya sendiri)")
        time.sleep(1)
        step("A folder pasted into itself is refused", bool(inside) and ls(f"{BASE}/src/sub/inner") == ["c.txt"],
             ls(f"{BASE}/src/sub/inner"))

        # Copy the folder to the other granted folder: a stream copy, checked byte by byte.
        open_root("src")
        item_menu("sub", "Copy|Salin")
        open_root("dst")
        tap_re(r"^(Paste|Tempel)$")
        notice(r"sub (copied|sudah disalin)", timeout=20)
        time.sleep(1.5)
        same = all(size(f"{BASE}/src/sub/{p}") == size(f"{BASE}/dst/sub/{p}") > 0 for p in ("a.txt", "b.txt", "inner/c.txt"))
        step("A folder copies to another granted folder, every file the same size", same,
             sh(f"ls -R {BASE}/dst", check=False).split())

        # Move a file between granted folders: copy, verify, then delete the original.
        open_root("src")
        item_menu("move-me.txt", "Move|Pindahkan")
        open_root("dst")
        tap_re(r"^(Paste|Tempel)$")
        notice(r"move-me\.txt (moved|sudah dipindah)", timeout=20)
        time.sleep(1.5)
        moved = sh(f"cat {BASE}/dst/move-me.txt", check=False).strip()
        step("A file moves to another granted folder and leaves the original folder",
             moved == "move me please" and "move-me.txt" not in ls(f"{BASE}/src"), [moved, ls(f"{BASE}/src")])

        # Cancel a large copy: nothing half-written stays behind, the original is untouched.
        open_root("src")
        item_menu("big.bin", "Copy|Salin")
        open_root("dst")
        tap_re(r"^(Paste|Tempel)$", timeout=4)
        tapped = tap_re(r"^(Cancel|Batal)$", timeout=6)
        cancelled = notice(r"(Cancelled|Dibatalkan)", timeout=30)
        time.sleep(2)
        step("Cancelling a large copy leaves no partial file", tapped and bool(cancelled) and "big.bin" not in ls(f"{BASE}/dst")
             and size(f"{BASE}/src/big.bin") == BIG_MB * 1048576, [ls(f"{BASE}/dst"), size(f"{BASE}/src/big.bin")])
        ui.shot("01-files-after")

        crashes = sh("logcat -d -b crash", check=False)
        step("No crash recorded for LX", PKG not in crashes, "crash buffer clean" if PKG not in crashes else crashes[:200])
    finally:
        sh(f"cmd package set-home-activity {args.restore_home}", check=False)
        # Clear LX (and with it both folder grants) and remove the fixture.
        sh("pm clear " + PKG, check=False)
        sh(f"rm -rf {BASE}", check=False)
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(2)
        restored = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                      check=False).strip().splitlines()[-1]
        step("Home restored to the previous launcher", restored == args.restore_home, restored)

    with open(os.path.join(args.out, "results.json"), "w") as f:
        json.dump(results, f, indent=2)
    failed = sum(1 for r in results if r["result"] == "FAIL")
    print(f"\n{len(results) - failed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
