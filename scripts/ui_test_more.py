#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Second batch of end-to-end UI tests: folders, widget editing, Android widget
hosting, workspace management, LX Files and Back handling.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_more.py --restore-home <component>
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from ui_test import PKG, ACT, Ui, sh, resumed_package, device_indonesian, settings_tab, bar_button, DOCK_SETTINGS, DOCK_FILES, MENU, menu_hits  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-more")
    ap.add_argument("--restore-home", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ui = Ui(args.out)
    results = []

    def step(name, ok, evidence=""):
        results.append({"step": name, "result": "PASS" if ok else "FAIL", "evidence": evidence})
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  {evidence}")
        return ok

    def menu_app(query, label):
        ui.tap(ui.wait(regex=MENU)[-1])
        search = ui.wait(regex=r"^(Search apps|Cari aplikasi)$")
        ui.tap(search[0])
        sh(f"input text {query}")
        time.sleep(1.2)
        return menu_hits(ui, label)

    def add_to_desktop(query, label):
        apps = menu_app(query, label)
        if apps:
            ui.long_press(apps[0])
            add = ui.wait(regex=r"^(Add to desktop|Tambah ke desktop)$")
            if add:
                ui.tap(add[0])
        sh("input keyevent KEYCODE_HOME")  # closes the menu; Escape would first clear its search
        time.sleep(1)

    def desktop_nodes(regex):
        desk = ui.find(label="Desktop")
        if not desk:
            return []
        dx1, dy1, dx2, dy2 = desk[0]["bounds"]
        # Shortcut icons are a grid cell wide; a widget can share the label (the clock widget is "Jam").
        return [n for n in ui.find(regex=regex, refresh=False) if n["pkg"] == PKG and
                n["bounds"][0] >= dx1 and n["bounds"][2] <= dx2 and n["bounds"][1] >= dy1 and n["bounds"][3] <= dy2
                and n["bounds"][2] - n["bounds"][0] < (dx2 - dx1) // 2]

    sh("logcat -b crash -c", check=False)
    try:
        sh("pm clear " + PKG)
        sh(f"cmd package set-home-activity {ACT}")
        sh("input keyevent KEYCODE_HOME")
        time.sleep(4)
        close = ui.wait(regex=r"^(Close|Tutup)$")
        if close:
            ui.tap(close[0])

        # Back does nothing harmful on an empty Home.
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)
        step("Back on the empty desktop keeps LX in front", resumed_package() == PKG, resumed_package())

        # Folder by dragging one shortcut onto another.
        # Calculator and clock exist on stock Android and on vendor phones; the
        # clock is "Jam" on an Indonesian device, which no English query finds.
        clock_query, clock_re = ("jam", r"^Jam$") if device_indonesian() else ("clock", r"^Clock$")
        add_to_desktop("ulator", r"^(Calculator|Kalkulator)$")
        add_to_desktop(clock_query, clock_re)
        a = desktop_nodes(r"^(Calculator|Kalkulator)$")
        b = desktop_nodes(clock_re)
        if a and b:
            (ax, ay), (bx, by) = Ui.center(a[0]), Ui.center(b[0])
            sh(f"input draganddrop {bx} {by} {ax} {ay} 1500")
            time.sleep(2)
        folder = ui.wait(regex=r", folder (with|berisi) 2 (apps|aplikasi)$")
        step("Dropping a shortcut on another makes a folder", bool(folder), folder[0]["desc"] if folder else "")
        ui.shot("01-folder")
        if folder:
            ui.tap(folder[0])
            inside = ui.wait(regex=r"^(Calculator|Kalkulator)$")
            step("Tapping the folder opens it with both apps",
                 bool(inside) and bool(ui.find(regex=clock_re, refresh=False)))
            ui.shot("02-folder-open")
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1)
            step("Back closes the folder popup", not ui.find(regex=r"^(Rename folder|Ganti nama folder) "))

        # Edit mode: drag and resize a widget.
        notes = ui.wait(regex=r"^(Quick notes|Catatan cepat)$")
        notes = [n for n in notes if n["desc"]]
        if notes:
            ui.long_press(notes[0])
            arrange = ui.wait(regex=r"^(Move or resize|Pindah atau ubah ukuran)$")
            if arrange:
                ui.tap(arrange[0])
        frame = [n for n in ui.wait(regex=r"^(Quick notes|Catatan cepat)$") if n["desc"]]
        step("Widget menu enters arrange mode", bool(ui.find(regex=r"^(Done|Selesai)$")))
        if frame:
            x1, y1, x2, y2 = frame[0]["bounds"]
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            sh(f"input swipe {cx} {cy} {cx - 400} {cy - 150} 800")
            time.sleep(1.2)
            moved = [n for n in ui.find(regex=r"^(Quick notes|Catatan cepat)$") if n["desc"]]
            nb = moved[0]["bounds"] if moved else None
            # Near a screen edge the widget stops there and only moves along the other axis.
            step("Dragging in arrange mode moves the widget", bool(nb and max(abs(nb[0] - x1), abs(nb[1] - y1)) > 100),
                 f"{frame[0]['bounds']} -> {nb}")
            handle = [n for n in ui.find(regex=r"^(Resize widget|Ubah ukuran widget)$") if nb and
                      nb[0] <= Ui.center(n)[0] <= nb[2] and nb[1] <= Ui.center(n)[1] <= nb[3]]
            if handle and nb:
                hx, hy = Ui.center(handle[0])
                sh(f"input swipe {hx} {hy} {hx + 160} {hy + 120} 800")
                time.sleep(1.2)
                resized = [n for n in ui.find(regex=r"^(Quick notes|Catatan cepat)$") if n["desc"]]
                rb = resized[0]["bounds"] if resized else None
                grew = rb and ((rb[2] - rb[0]) - (nb[2] - nb[0]) > 60 or (rb[3] - rb[1]) - (nb[3] - nb[1]) > 60)
                step("Corner handle resizes the widget", bool(grew), f"{nb} -> {rb}")
            ui.shot("03-widget-edit")
        done = ui.find(regex=r"^(Done|Selesai)$")
        if done:
            ui.tap(done[0])
        step("Done leaves arrange mode", not ui.find(regex=r"^(Done|Selesai)$"))

        # Android widget hosting.
        ui.tap(bar_button(ui, DOCK_SETTINGS)[0])
        settings_tab(ui, r"^(Widgets|Widget)$")
        add = []
        win = ui.wait(regex=r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$")
        for _ in range(4):  # the row sits below the fold
            add = ui.wait(timeout=2, regex=r"^(Android widget|Widget Android), (Add|Tambah)")
            if add or not win:
                break
            x1, y1, x2, y2 = win[0]["bounds"]
            cx = (x1 + x2) // 2
            sh(f"input swipe {cx} {y2 - 60} {cx} {y1 + 160} 400")
            time.sleep(0.6)
        hosted = False
        if add:
            ui.tap(add[0])
            choice = ui.wait(regex=r"(Clock|Analog|Digital|Jam)")
            ui.shot("04-widget-providers")
            if choice:
                label = choice[0]["desc"] or choice[0]["text"]
                ui.tap(choice[0])
                time.sleep(2)
                allow = ui.wait(timeout=5, regex=r"^(CREATE|Create|Buat|BUAT|Allow|Izinkan)$")
                ui.shot("05-bind-dialog")
                step("Android asks the user to allow widget binding", bool(allow) or resumed_package() == PKG,
                     "system bind dialog shown" if allow else "already allowed")
                if allow:
                    ui.tap(allow[-1])
                    time.sleep(2)
                    # Some providers open a configuration screen.
                    if resumed_package() != PKG:
                        sh("input keyevent KEYCODE_BACK")
                        time.sleep(2)
                hosted = bool(ui.wait(regex=r"(Android widget|Widget Android) (added|ditambahkan)|added$|ditambahkan$", timeout=4)) or \
                    bool([n for n in ui.find(regex=r"Clock|Jam|Analog") if n["cls"].endswith("AppWidgetHostView")])
                step("Provider widget is hosted on the desktop", hosted, label)
                ui.shot("06-android-widget")
        else:
            step("Settings offers Android widgets", False, "row not found")

        # Workspace management: add, rename, remove with content moved.
        tab = None if ui.find(regex=r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$") else bar_button(ui, DOCK_SETTINGS)
        if tab:
            ui.tap(tab[0])
        ui.wait(regex=r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$")
        settings_tab(ui, r"^(Workspaces|Workspace)$")
        addws = ui.wait(regex=r"^(Add workspace|Tambah workspace)$")
        if addws:
            ui.tap(addws[0])
        fifth = ui.wait(regex=r"(Switch to|Pindah ke) (Workspace|Ruang) 5$")
        step("Add workspace creates a fifth workspace", bool(fifth))
        ren = ui.wait(regex=r"^(Rename|Ganti nama) (Workspace|Ruang) 5$")
        if ren:
            ui.tap(ren[0])
            sh("input text Studio")
            sh("input keyevent KEYCODE_ENTER")
            time.sleep(1.2)
        step("Workspace rename is shown in the taskbar", bool(ui.wait(regex=r"(Switch to|Pindah ke) Studio$")))
        rm = ui.wait(regex=r"^(Remove|Singkirkan) Studio$")
        if rm:
            ui.tap(rm[0])
            move = ui.wait(regex=r"^(Move its content to|Pindahkan isinya ke) ")
            if move:
                ui.tap(move[0])
            time.sleep(1.2)
        step("Workspace removal asks where content goes and removes it", bool(rm) and not ui.find(regex=r"Studio"))
        ui.shot("07-workspaces")

        # LX Files: empty state and the system folder picker.
        sh("input keyevent KEYCODE_ESCAPE")
        files = bar_button(ui, DOCK_FILES)
        if files:
            ui.tap(files[0])
        empty = ui.wait(regex=r"(only sees folders you (pick|choose)|hanya melihat folder yang Anda pilih)")
        step("LX Files explains the folder grant before any access", bool(empty))
        grant = ui.find(regex=r"^(Choose a folder|Pilih folder)$")
        if grant:
            ui.tap(grant[-1])
            time.sleep(3)
            picker = resumed_package()
            step("Choose a folder opens Android's folder picker", picker != PKG and picker != "", picker)
            ui.shot("08-saf-picker")
            # The picker reopens the last folder, and Back walks up the folders before it closes.
            for _ in range(4):
                sh("input keyevent KEYCODE_BACK")
                time.sleep(1.5)
                if resumed_package() == PKG:
                    break
            step("Cancelling the picker returns to LX", resumed_package() == PKG)

        crashes = sh("logcat -d -b crash", check=False)
        step("No crash recorded for LX", PKG not in crashes, "crash buffer clean" if PKG not in crashes else crashes[:300])
    finally:
        sh(f"cmd package set-home-activity {args.restore_home}", check=False)
        # Every batch starts from cleared data; clear it again so no test state (accent, widgets,
        # the test wallpaper) stays behind on the device.
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
