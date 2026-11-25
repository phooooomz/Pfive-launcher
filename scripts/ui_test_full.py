#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Third batch of end-to-end UI tests: everything the first two batches leave
out. LX Files with a real granted folder, layout export and import through the
system picker, wallpapers, window snap/tile/move, search into settings, hidden
apps, presets and performance mode, the uninstall hand-off (cancelled), notes
persistence, keyboard shortcuts, accessibility modes and the phone layout.

Needs /sdcard/Documents/LXTest/hello.txt and /sdcard/Pictures/lx-wallpaper-test.png
on the device (scripts/test-device.sh creates them).

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_full.py --restore-home <component>
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from ui_test import (PKG, ACT, Ui, sh, resumed_package, settings_tab, device_indonesian, bar_button, on_workspace,  # noqa: E402
                     DOCK_SETTINGS, DOCK_FILES, DOCK_MONITOR, MENU, menu_hits)

TEST_DIR = "/sdcard/Documents/LXTest"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-full")
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

    def window(regex):
        return ui.find(regex=rf"^(?:Window|Jendela) ({regex})$")

    def close_window(regex):
        w = window(regex)
        if not w:
            return
        x1, y1, x2, y2 = w[0]["bounds"]
        for n in ui.find(regex=r"^(Close|Tutup)$", refresh=False):
            if y1 <= n["bounds"][1] <= y1 + 120 and x1 <= n["bounds"][0] <= x2:
                ui.tap(n)
                return

    def dock(regex):
        hit = bar_button(ui, regex)
        if hit:
            ui.tap(hit[0])
        return bool(hit)

    def settings_page(tab):
        if not window("LX Settings|Pengaturan LX"):
            dock(DOCK_SETTINGS)
            ui.wait(regex=r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$")
        return settings_tab(ui, rf"^({tab})$")

    def scroll_window_until(regex, name_re="LX Settings|Pengaturan LX", tries=6):
        for _ in range(tries):
            n = ui.find(regex=regex)
            if n:
                return n
            w = window(name_re)
            if not w:
                return []
            x1, y1, x2, y2 = w[0]["bounds"]
            cx = (x1 + x2) // 2
            sh(f"input swipe {cx} {y2 - 80} {cx} {y1 + 200} 350")
            time.sleep(0.6)
        return ui.find(regex=regex)

    # DocumentsUI lists the device under its user-visible name.
    device_root = "|".join(re.escape(n) for n in
                           (sh("settings get global device_name", check=False).strip(), "WayDroid x86_64 Device") if n)
    screen_w, screen_h = map(int, re.search(r"(\d+)x(\d+)", sh("wm size")).groups())

    def find_scrolling(regex, swipes=8):
        """Waits for a node, swiping the list up between looks (long folder lists on a phone)."""
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

    def device_root_folder():
        """Shows the top of internal storage in DocumentsUI, which reopens the last folder it showed."""
        tap_re(r"^(Show roots|Tampilkan root)$", timeout=3)
        tap_re(rf"^({device_root})$", timeout=3, index=0)
        time.sleep(1.5)

    def open_group(name):
        """Opens a category in the Applications menu by its name (the row reads "Name, N apps")."""
        row = menu_hits(ui, rf"^({name}), \d+ ")
        if row:
            ui.tap(row[0])
            time.sleep(1)
        return bool(row)

    def back_to_lx():
        for _ in range(4):
            if resumed_package() == PKG:
                return
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1.2)

    def device_ls(path):
        return sh(f"ls {path}", check=False)

    sh("logcat -b crash -c", check=False)
    try:
        sh("pm clear " + PKG)
        sh(f"cmd package set-home-activity {ACT}")
        sh("input keyevent KEYCODE_HOME")
        time.sleep(4)
        c = ui.wait(regex=r"^(Close|Tutup)$")
        if c:
            ui.tap(c[0])

        # ---- LX Files with a granted folder ----
        dock(DOCK_FILES)
        tap_re(r"^(Choose a folder|Pilih folder)$", index=-1)
        time.sleep(2.5)
        # The picker reopens the last folder it showed; start from the device root.
        if not ui.wait(timeout=3, regex=r"^Documents$"):
            device_root_folder()
        tap_scrolling(r"^Documents$")
        tap_scrolling(r"^LXTest$")
        tap_re(r"^(USE THIS FOLDER|Use this folder|GUNAKAN FOLDER INI|Gunakan folder ini)$")
        tap_re(r"^(ALLOW|Allow|IZINKAN|Izinkan)$")
        time.sleep(2)
        back_to_lx()
        listed = ui.wait(regex=r"^hello\.txt$")
        step("LX Files lists the granted folder", bool(listed) and resumed_package() == PKG)
        ui.shot("01-files-granted")

        tap_re(r"^(New folder|Folder baru)$")
        time.sleep(0.8)
        sh("input text Sub")
        sh("input keyevent KEYCODE_ENTER")
        time.sleep(2)
        step("New folder is created on storage", "Sub" in device_ls(TEST_DIR), device_ls(TEST_DIR).split())

        f = ui.wait(regex=r"^hello\.txt$")
        if f:
            ui.long_press(f[0])
            tap_re(r"^(Copy|Salin)$")
        tap_re(r"^(Folder Sub|Sub)$")
        time.sleep(1.5)
        tap_re(r"^(Paste|Tempel)$")
        time.sleep(2.5)
        step("Copy and paste puts the file in the subfolder", "hello.txt" in device_ls(TEST_DIR + "/Sub"),
             device_ls(TEST_DIR + "/Sub").split())

        f = ui.wait(regex=r"^hello\.txt$")
        if f:
            ui.long_press(f[0])
            tap_re(r"^(Rename|Ganti nama)$")
            # The dialog's field takes focus just after the dialog appears; text typed before that is lost
            # and Enter then keeps the old name. Wait for the focused field instead of a fixed pause.
            deadline = time.time() + 6
            while time.time() < deadline and not [n for n in ui.find(regex=r"^hello\.txt$")
                                                  if n["focused"] and n["cls"].endswith("EditText")]:
                time.sleep(0.3)
            sh("input text renamed.txt")
            sh("input keyevent KEYCODE_ENTER")
            time.sleep(2)
        sub = device_ls(TEST_DIR + "/Sub")
        step("Rename changes the file name on storage", "renamed.txt" in sub and "hello.txt" not in sub, sub.split())

        # Details and Delete work on the renamed file; without it both fail instead of passing unseen.
        f = ui.wait(regex=r"^renamed\.txt$")
        renamed_listed = bool(f)
        if not f:
            step("Details show the file type", False, "renamed.txt is not listed")
        else:
            ui.long_press(f[0])
            tap_re(r"^(Details|Detail)$")
            details = ui.wait(regex=r"(Type|Jenis): text/plain")
            step("Details show the file type", bool(details))
            sh("input keyevent KEYCODE_ESCAPE")
            time.sleep(0.6)
            f = ui.wait(regex=r"^renamed\.txt$")
            ui.long_press(f[0])
            tap_re(r"^(Delete|Hapus)$")
            # Wait for the dialog, or the closing menu's own Delete item gets tapped again.
            if ui.wait(regex=r"^(Delete|Hapus) renamed\.txt\?$"):
                tap_re(r"^(Delete|Hapus)$", index=-1)
            time.sleep(2)
        step("Delete with confirmation removes the file", renamed_listed and "renamed.txt" not in device_ls(TEST_DIR + "/Sub"),
             device_ls(TEST_DIR + "/Sub").split() or "empty")

        tap_re(r"^(Up one folder|Naik satu folder)$")
        time.sleep(1.5)
        tap_re(r"^(Search in this folder|Cari di folder ini)$")
        time.sleep(0.8)
        sh("input text hello")
        sh("input keyevent KEYCODE_ENTER")
        time.sleep(2.5)
        step("Search inside the granted folder finds the file", bool(ui.wait(regex=r"^hello\.txt$")))
        ui.shot("02-files-search")
        close_window("LX Files")

        # ---- Wallpapers ----
        settings_page("Desktop")
        # Data was cleared at the start: a new install draws the theme wallpaper, no image.
        wp = ui.wait(regex=r"^Wallpaper, (Follows the theme|Ikut tema)")
        step("New install starts on the theme wallpaper", bool(wp), wp[0]["desc"][:40] if wp else "")
        tap_re(r"^(Wallpaper),")
        tap_re(r"^(Deep blue|Biru pekat)$")
        time.sleep(2.5)
        ui.shot("03-wallpaper-deep")
        settings_page("Desktop")
        wp = ui.wait(regex=r"^Wallpaper, (Deep blue|Biru pekat)")
        step("Built-in wallpaper choice is applied", bool(wp), wp[0]["desc"][:40] if wp else "")
        # The desktop has one wallpaper: workspace 2 shows the same one, and no workspace has its own button.
        sh("input keycombination 113 9", check=False)  # Ctrl+2
        time.sleep(1.5)
        settings_page("Desktop")
        same = ui.wait(regex=r"^Wallpaper, (Deep blue|Biru pekat)")
        settings_page("Workspaces|Workspace")
        own = ui.find(regex=r"^(Wallpaper for|Wallpaper untuk) ")
        step("Every workspace shows the same wallpaper", bool(same) and not own,
             (same[0]["desc"][:40] if same else "workspace 2 differs") + ("" if not own else ", per-workspace button still shown"))
        close_window("LX Settings|Pengaturan LX")
        sh("input keycombination 113 8", check=False)  # Ctrl+1
        time.sleep(1.5)
        settings_page("Desktop")
        tap_re(r"^(Wallpaper),")
        tap_re(r"^(Choose an image|Pilih gambar)$")
        time.sleep(3)
        sh("logcat -c", check=False)
        # Android 13 and later open the photo picker, newest first (test-device.sh makes the
        # fixture the newest image); older systems the document picker.
        img = ui.wait(timeout=5, regex=r"^(Photo taken on|Foto diambil pada|lx-wallpaper-test)")
        if not img:
            device_root_folder()
            tap_scrolling(r"^Pictures$")
            img = find_scrolling(r"lx-wallpaper-test")
        if img:
            ui.tap(img[0])
            # Newer photo pickers (Android 15 with Google Photos) confirm a single pick with Done.
            tap_re(r"^(Done|DONE|Selesai|SELESAI)$", timeout=3)
        time.sleep(5)
        back_to_lx()
        log = sh("logcat -d -s LxWallpaper", check=False)
        settings_page("Desktop")
        wp = ui.wait(regex=r"^Wallpaper, (Your image|Gambar Anda)")
        # 900x1600 is the generated test card; anything else means another photo was picked.
        shown = "imported wallpaper image 900x1600" in log and "could not be shown" not in log
        step("A picked image becomes the workspace wallpaper", bool(wp) and shown and resumed_package() == PKG,
             (re.findall(r"imported wallpaper image \S+", log) or ["no import logged"])[0])
        ui.shot("04-wallpaper-image")
        # Back to the theme wallpaper, so the test card does not stay on the device.
        tap_re(r"^(Wallpaper),")
        tap_re(r"^(Follows the theme|Ikut tema)$")
        time.sleep(2.5)
        settings_page("Desktop")
        wp = ui.wait(regex=r"^Wallpaper, (Follows the theme|Ikut tema)")
        step("Theme wallpaper choice is applied", bool(wp), wp[0]["desc"][:40] if wp else "")

        # ---- Window styles: each one from the window menu, ending back on the LX theme ----
        styles = [("Minimal Dark", "Minimalis Gelap"), ("Glassmorphism", "Glassmorphism"), ("Cyberpunk Neon", "Cyberpunk Neon"),
                  ("Rounded Modern", "Rounded Modern"), ("Tactical / Technical", "Taktis / Teknis"),
                  ("Compact / Slim", "Ringkas / Ramping"), ("Material You", "Material You"), ("Retro / Classic", "Retro / Klasik"),
                  ("Nature Inspired", "Nuansa Alam"), ("Hybrid / Linux Desktop", "Hybrid / Desktop Linux"),
                  ("Follow the LX theme", "Ikut tema LX")]
        settings_page("Windows and panels|Jendela dan panel")
        failed_styles = []
        for en, idn in styles:
            names = f"{re.escape(en)}|{re.escape(idn)}"
            w = window("LX Settings|Pengaturan LX")
            if not w:
                failed_styles.append(f"{en}: window gone")
                break
            b = w[0]["bounds"]
            sh(f"input swipe {b[0] + 150} {b[1] + 20} {b[0] + 150} {b[1] + 20} 900")
            time.sleep(1)
            if not tap_re(r"^(Window style|Gaya jendela)$", timeout=4) or not tap_re(rf"^({names})$", timeout=4):
                failed_styles.append(f"{en}: not in the menu")
                sh("input keyevent KEYCODE_ESCAPE")
                continue
            time.sleep(2.5)
            if not window("LX Settings|Pengaturan LX") or not scroll_window_until(rf"^({names}), (in use|dipakai)$"):
                failed_styles.append(f"{en}: not applied")
            if en == "Cyberpunk Neon":
                ui.shot("05-window-style-cyberpunk")
        step("Every window style applies and keeps LX Settings open", not failed_styles, failed_styles or "11 of 11")

        # ---- Presets, performance, accessibility ----
        settings_page("Theme and colour|Tema dan warna")
        tap_re(r"^Preset,")
        tap_re(r"^Minimal$")
        time.sleep(2.5)
        settings_page("Theme and colour|Tema dan warna")
        step("Minimal preset applies", bool(ui.wait(regex=r"^Preset, Minimal")))
        ui.shot("05-preset-minimal")
        tap_re(r"^Preset,")
        tap_re(r"^Cyberpunk$")
        time.sleep(2.5)
        settings_page("Performance|Performa")
        tap_re(r"^(Mode),")
        tap_re(r"^(Performance|Performa)$", index=-1)
        time.sleep(2.5)
        settings_page("Performance|Performa")
        step("Performance mode applies", bool(ui.wait(regex=r"^Mode, (Performance|Performa)")))
        tap_re(r"^(Mode),")
        tap_re(r"^(Balanced|Seimbang)$")
        time.sleep(2.5)
        settings_page("Accessibility|Aksesibilitas")
        for row in (r"^(High contrast|Kontras tinggi)", r"^(Larger touch targets|Target sentuh lebih besar)"):
            n = ui.wait(regex=row)
            if n:
                ui.tap(n[0])
                time.sleep(2.5)
                settings_page("Accessibility|Aksesibilitas")
        ui.shot("06-high-contrast")
        step("High contrast and larger targets rebuild without a crash", resumed_package() == PKG and
             bool(ui.wait(label="Taskbar")))
        for row in (r"^(High contrast|Kontras tinggi)", r"^(Larger touch targets|Target sentuh lebih besar)"):
            n = ui.wait(regex=row)
            if n:
                ui.tap(n[0])
                time.sleep(2.5)
                settings_page("Accessibility|Aksesibilitas")

        # ---- Export and import through the system picker ----
        # /sdcard is a symlink: find needs the trailing slash to descend into it.
        layouts = "find /sdcard/ -name 'lx-launcher-layout*.json' 2>/dev/null"
        for old in sh(layouts, check=False).split():
            sh(f"rm '{old}'", check=False)
        settings_page("Backup|Cadangan")
        tap_re(r"^(Export layout|Ekspor tata letak)")
        time.sleep(3)
        # Save into the test folder, so the import below knows where to look.
        device_root_folder()
        tap_scrolling(r"^Documents$")
        tap_scrolling(r"^LXTest$")
        tap_re(r"^(SAVE|Save|SIMPAN|Simpan)$")
        time.sleep(3)
        back_to_lx()
        exported = sh(layouts, check=False).split()
        step("Export writes a layout file through the picker", bool(exported), exported[:1])
        # Change something, then import the saved file to bring it back.
        settings_page("Workspaces|Workspace")
        tap_re(r"^(Rename|Ganti nama) (Workspace|Ruang) 1$")
        time.sleep(0.8)
        sh("input text Temporary")
        sh("input keyevent KEYCODE_ENTER")
        time.sleep(1.5)
        renamed = bool(ui.wait(regex=r"(Switch to|Pindah ke) Temporary|Temporary, (current workspace|workspace aktif)|^1 · Temporary"))
        settings_page("Backup|Cadangan")
        tap_re(r"^(Import layout|Impor tata letak)")
        time.sleep(3)
        # The grid view shortens long names ("lx-launcher-la..."), so match the start only.
        layout_re = r"^lx-launcher-la"
        pick = ui.wait(timeout=5, regex=layout_re)
        if not pick:
            device_root_folder()
            tap_scrolling(r"^Documents$")
            tap_scrolling(r"^LXTest$")
            pick = find_scrolling(layout_re)
        if pick:
            ui.tap(pick[0])
        time.sleep(3)
        back_to_lx()  # leave a picker that is still open, so later steps run in LX
        restored = on_workspace(ui, 1) and not ui.find(regex=r"Temporary")
        step("Import restores the exported layout", renamed and restored,
             "renamed to Temporary, then import brought back Workspace 1" if restored else "")
        ui.shot("07-import")

        # ---- Windows: snap, tile, move to another workspace ----
        dock(DOCK_MONITOR)
        time.sleep(2)
        win = window("System Monitor|Monitor Sistem")
        step("System Monitor opens with live readings", bool(win) and bool(ui.find(regex=r"(free|kosong)$", refresh=False)))
        dock(DOCK_FILES)
        time.sleep(1.5)
        win = window("LX Files")
        if win:
            x1, y1, x2, y2 = win[0]["bounds"]
            ui.long_press({"bounds": (x1 + 120, y1 + 8, x1 + 300, y1 + 40)})
            tap_re(r"^(Snap to the left|Tempel ke kiri)")
            time.sleep(1.2)
        win = window("LX Files")
        # The Settings tab "Desktop" shares the label; the canvas is the largest node.
        canvas = sorted(ui.find(label="Desktop", refresh=False),
                        key=lambda n: (n["bounds"][2] - n["bounds"][0]) * (n["bounds"][3] - n["bounds"][1]), reverse=True)
        snapped = False
        if win and canvas:
            wx1, wy1, wx2, wy2 = win[0]["bounds"]
            cx1, cy1, cx2, cy2 = canvas[0]["bounds"]
            if cx2 - cx1 >= cy2 - cy1:  # landscape: the left half
                snapped = abs(wx1 - cx1) < 8 and wx2 - wx1 < (cx2 - cx1) * 0.6
            else:  # portrait: "left" is the top half, as the menu item says
                snapped = abs(wy1 - cy1) < 8 and wy2 - wy1 < (cy2 - cy1) * 0.6
        step("Snap left fills the left half", snapped, str(win[0]["bounds"]) if win else "")
        # Tile from the title bar menu: an empty spot of desktop is hard to find on a phone.
        win = window("LX Files")
        if win:
            x1, y1, x2, y2 = win[0]["bounds"]
            ui.long_press({"bounds": (x1 + 120, y1 + 8, x1 + 300, y1 + 40)})
        tap_re(r"^(Tile windows|Rapikan jendela)$")
        time.sleep(1.2)
        a, b = window("LX Files"), window("System Monitor|Monitor Sistem")
        overlap = True
        if a and b:
            ax1, ay1, ax2, ay2 = a[0]["bounds"]
            bx1, by1, bx2, by2 = b[0]["bounds"]
            overlap = ax1 < bx2 - 4 and bx1 < ax2 - 4 and ay1 < by2 - 4 and by1 < ay2 - 4
        step("Tile arranges open windows side by side", bool(a and b) and not overlap)
        ui.shot("08-tiled")
        win = window("LX Files")
        if win:
            x1, y1, x2, y2 = win[0]["bounds"]
            ui.long_press({"bounds": (x1 + 120, y1 + 8, x1 + 300, y1 + 40)})
            tap_re(r"^(Move to|Pindah ke) (Workspace|Ruang) 2$")
            time.sleep(1.2)
        gone = not window("LX Files")
        tap_re(r"^(Switch to|Pindah ke) (Workspace|Ruang) 2$")
        time.sleep(1.2)
        step("A window moves to another workspace", gone and bool(window("LX Files")))
        tap_re(r"^(Switch to|Pindah ke) (Workspace|Ruang) 1$")
        time.sleep(1)

        # ---- Search into settings, keyboard shortcuts ----
        sh("input keycombination 113 39", check=False)
        time.sleep(1)
        sh("input text dock")
        time.sleep(1.2)
        tap_re(r"^Dock, (LX Settings|Pengaturan LX)$")
        time.sleep(1.5)
        step("Search opens the matching settings page", bool(ui.wait(regex=r"^(Show dock|Tampilkan dock)")))
        sh("input keycombination 113 9", check=False)  # Ctrl+2
        time.sleep(1.2)
        step("Ctrl+2 switches to workspace 2", on_workspace(ui, 2))
        sh("input keycombination 57 134", check=False)  # Alt+F4 closes the focused window
        time.sleep(1.2)
        step("Alt+F4 closes the focused window", not window("LX Files"))
        sh("input keycombination 113 8", check=False)  # Ctrl+1
        time.sleep(1)

        # ---- Hidden apps and the uninstall hand-off ----
        tap_re(MENU, index=-1)
        tap_re(r"^(Search apps|Cari aplikasi)$")
        sh("input text zarch")
        time.sleep(1.2)
        z = menu_hits(ui, r"^ZArchiver$")
        if z:
            ui.long_press(z[0])
            time.sleep(1.2)  # long press also starts a drag; the menu opens when it ends in place
            tap_re(r"^(Hide from the menu|Sembunyikan dari menu)$")
            time.sleep(1)
        hidden_gone = not menu_hits(ui, r"^ZArchiver$")
        # Empty the search field; Back might only close the keyboard.
        sh("input keyevent " + " ".join(["KEYCODE_DEL"] * 6))
        time.sleep(0.8)
        open_group("Hidden|Tersembunyi")
        in_hidden = menu_hits(ui, r"^ZArchiver$")
        step("Hide moves the app to the Hidden category", hidden_gone and bool(in_hidden))
        if in_hidden:
            ui.long_press(in_hidden[0])
            time.sleep(1.2)
            tap_re(r"^(Show in the menu|Tampilkan di menu)$")
            time.sleep(1)
            # With nothing hidden any more the Hidden category is gone and the menu shows its categories.
            # ZArchiver sorts last in All apps, below the fold, so it is found through the search.
            tap_re(r"^(Search apps|Cari aplikasi)$")
            sh("input text zarch")
            time.sleep(1.2)
            z = menu_hits(ui, r"^ZArchiver$")
            dialog = ""
            if z:
                ui.long_press(z[0])
                time.sleep(1.2)
                tap_re(r"^(Uninstall|Copot pemasangan)$")
                time.sleep(2.5)
                dialog = resumed_package()
                ui.shot("09-uninstall-dialog")
            step("Uninstall hands over to Android's dialog", bool(z) and dialog not in (PKG, ""),
                 dialog if z else "ZArchiver not found in the menu")
            if z:
                tap_re(r"^(Cancel|CANCEL|Batal|BATAL)$", timeout=4)
                time.sleep(1.5)
        still = "ru.zdevs.zarchiver" in sh("pm list packages ru.zdevs.zarchiver", check=False)
        step("Cancelling the dialog keeps the app installed", still)
        sh("input keyevent KEYCODE_HOME")
        time.sleep(1.5)

        # ---- Notes persistence, clock format ----
        # Windows from the steps above cover the widget column; close them first.
        for name in ("LX Settings|Pengaturan LX", "System Monitor|Monitor Sistem", "LX Files"):
            close_window(name)
            time.sleep(0.8)
        notes = ui.find(regex=r"^(Tap to write a note|Ketuk untuk menulis catatan)$")
        if notes:
            ui.tap(notes[0])
            sh("input text Buy%scoffee")
            time.sleep(1.5)
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1)
        sh("am force-stop " + PKG)
        time.sleep(1)
        sh("input keyevent KEYCODE_HOME")
        time.sleep(4)
        step("Quick notes text survives process death", bool(ui.wait(regex=r"^Buy coffee$")))
        clock = ui.find(label="Clock") or ui.find(label="Jam")
        if clock:
            ui.long_press(clock[0])
            tap_re(r"^(Use 12-hour clock|Pakai format 12 jam)$")
            time.sleep(1.5)
        step("Clock widget switches to 12-hour format", bool(ui.wait(regex=r"^\d{1,2}:\d{2}\s?(AM|PM|am|pm)$")))
        ui.shot("10-notes-clock")

        # ---- Phone layout ----
        sh("wm size 720x1600")
        sh("wm density 320")
        time.sleep(5)
        ui.shot("11-phone")
        alive = resumed_package() == PKG and bool(ui.wait(label="Taskbar"))
        step("Phone-sized screen renders the launcher", alive)
        tap_re(MENU, index=-1)
        time.sleep(1.5)
        ui.shot("12-phone-menu")
        step("Applications menu opens on the phone layout", bool(ui.wait(regex=r"^(Search apps|Cari aplikasi)$")))
        # A category opens as a cascading submenu beside the categories, never as a panel across the screen.
        cat = menu_hits(ui, r"^(All apps|Semua aplikasi), \d+ ")
        if cat:
            ui.tap(cat[0])
            time.sleep(1.5)
        ui.shot("12b-phone-submenu")
        columns = [n for n in ui.find(regex=r"^(Applications|Aplikasi|All apps|Semua aplikasi)$")
                   if n["pkg"] == PKG and n["bounds"][3] - n["bounds"][1] > 150]
        first = [n for n in columns if re.fullmatch(r"Applications|Aplikasi", n["desc"] or "")]
        sub = [n for n in columns if re.fullmatch(r"All apps|Semua aplikasi", n["desc"] or "")]
        cascade = bool(cat and first and sub) and sub[0]["bounds"][2] - sub[0]["bounds"][0] < 720 * 0.6 \
            and first[0]["bounds"][0] < sub[0]["bounds"][0]
        step("A category opens as a submenu beside the categories, not across the screen", cascade,
             [n["bounds"] for n in first + sub] or "menu columns not found")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.6)
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)
        sh("wm size reset")
        sh("wm density reset")
        time.sleep(4)

        crashes = sh("logcat -d -b crash", check=False)
        step("No crash recorded for LX", PKG not in crashes, "crash buffer clean" if PKG not in crashes else crashes[:300])
    finally:
        sh("wm size reset", check=False)
        sh("wm density reset", check=False)
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
