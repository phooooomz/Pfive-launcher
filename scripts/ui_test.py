#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""End-to-end UI tests for LX-LAUNCHER on a real Android target (device or Waydroid).

Drives the installed debug build with `adb shell input` and reads the screen
through `uiautomator dump`. Every step records PASS or FAIL with evidence; the
run never reports success for a step it did not observe.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test.py [--out build/ui-test]
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.lxlauncher"
ACT = f"{PKG}/.LauncherActivity"


def adb(*args, check=True, timeout=60):
    serial = os.environ.get("ANDROID_SERIAL")
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed: {r.stderr.strip()}")
    return r.stdout


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


class Ui:
    def __init__(self, out):
        self.out = out
        self.nodes = []

    def dump(self):
        for _ in range(3):
            sh("uiautomator dump /data/local/tmp/lx-ui.xml", check=False, timeout=30)
            xml = sh("cat /data/local/tmp/lx-ui.xml", check=False)
            if xml.strip().startswith("<?xml"):
                break
            time.sleep(1)
        else:
            raise RuntimeError("uiautomator dump failed")
        root = ET.fromstring(xml[xml.index("<?xml"):])
        self.nodes = []

        # Each node keeps the positions of its ancestors in self.nodes, so a caller can tell
        # what is inside a container from what only lies behind it on screen.
        def walk(el, ancestors):
            for n in el.findall("node"):
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
                if not m:
                    walk(n, ancestors)
                    continue
                x1, y1, x2, y2 = map(int, m.groups())
                self.nodes.append({
                    "text": n.get("text", ""), "desc": n.get("content-desc", ""),
                    "pkg": n.get("package", ""), "cls": n.get("class", ""),
                    "bounds": (x1, y1, x2, y2), "clickable": n.get("clickable") == "true",
                    "focused": n.get("focused") == "true", "ancestors": ancestors,
                })
                walk(n, ancestors + (len(self.nodes) - 1,))
        walk(root, ())
        return self.nodes

    def find(self, label=None, contains=None, regex=None, refresh=True):
        if refresh:
            self.dump()
        out = []
        for n in self.nodes:
            vals = (n["text"], n["desc"])
            if label is not None and label in vals:
                out.append(n)
            elif contains is not None and any(contains in v for v in vals):
                out.append(n)
            elif regex is not None and any(re.search(regex, v) for v in vals if v):
                out.append(n)
        return out

    def wait(self, timeout=8, **kw):
        end = time.time() + timeout
        while time.time() < end:
            found = self.find(**kw)
            if found:
                return found
            time.sleep(0.6)
        return []

    @staticmethod
    def center(n):
        x1, y1, x2, y2 = n["bounds"]
        return (x1 + x2) // 2, (y1 + y2) // 2

    def tap(self, n):
        x, y = self.center(n)
        sh(f"input tap {x} {y}")
        time.sleep(0.8)

    def long_press(self, n, ms=900):
        x, y = self.center(n)
        sh(f"input swipe {x} {y} {x} {y} {ms}")
        time.sleep(1.0)

    def shot(self, name):
        path = os.path.join(self.out, f"{name}.png")
        sh("screencap -p /data/local/tmp/lx-shot.png", check=False)
        adb("pull", "/data/local/tmp/lx-shot.png", path, check=False)
        return path


SETTINGS_WINDOW = r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$"
SETTINGS_BACK = r"^(Back to all settings|Kembali ke daftar pengaturan)$"


def settings_tab(ui, tab_re):
    """Opens a page of the open LX Settings window. In a phone-wide window the
    pages are a list (an open page has a row back to it at its top); from
    560 dp they are a column on the left. The list scrolls, so it is swiped up,
    then down, until the page's title shows. Only nodes inside the window
    count: "Desktop" is also the desktop canvas label."""
    win = ui.find(regex=SETTINGS_WINDOW)
    if not win:
        return False
    x1, y1, x2, y2 = win[0]["bounds"]
    inside = lambda n: x1 <= n["bounds"][0] < x2 and y1 < n["bounds"][1] < y2
    back = [n for n in ui.find(regex=SETTINGS_BACK) if inside(n)]
    if back:
        ui.tap(back[0])
        time.sleep(0.8)
    for attempt in range(8):
        hit = [n for n in ui.find(regex=tab_re) if inside(n) and n["text"] and n["bounds"][2] - n["bounds"][0] > 30]
        if hit:
            ui.tap(hit[0])
            time.sleep(0.8)
            return True
        x = (x1 + x2) // 2
        a, b = (y2 - 120, y1 + 260) if attempt < 4 else (y1 + 260, y2 - 120)
        sh(f"input swipe {x} {a} {x} {b} 300")
        time.sleep(0.8)
    return False


# Dock entries by their visible label (dock on the side) or by their spoken
# name (the phone's bottom bar shows icons only).
DOCK_SETTINGS = r"^(Settings|Setelan|LX Settings|Pengaturan LX)$"
DOCK_FILES = r"^(Files|Berkas|LX Files|File Manager)$"
DOCK_MONITOR = r"^(Monitor|System Monitor|Monitor Sistem)$"


def bar_button(ui, regex, timeout=8):
    """Finds a dock or taskbar entry. On a phone the bottom bar scrolls, so
    entries past its edge are brought in by swiping the bar, then swiping it
    back. Nodes inside a window are skipped: a window title can share the name."""
    found = ui.wait(regex=regex, timeout=timeout)
    if found:
        return found
    tb = ui.find(label="Taskbar")
    if not tb:
        return []
    x1, y1, x2, y2 = tb[0]["bounds"]
    y = (y1 + y2) // 2
    # Swipe across the middle: the Applications button stays fixed at the left end and open
    # windows at the right end; only the pins between them scroll.
    a, b = x1 + (x2 - x1) * 3 // 4, x1 + (x2 - x1) // 4
    for a, b in ((a, b), (b, a)):
        sh(f"input swipe {a} {y} {b} {y} 300")
        time.sleep(0.8)
        found = [n for n in ui.find(regex=regex) if n["bounds"][1] >= y1 - 4]
        if found:
            return found
    return []


MENU = r"^(Applications|Aplikasi)$"
_MENU_ROOT = r"Applications|Aplikasi|Choose an app for the desktop|Pilih aplikasi untuk desktop"
# Menu rows that are not apps: categories ("Games, 5 apps"), the search well's clear button,
# the offer to search everywhere, and LX's tools at the foot of the menu.
MENU_CHROME = (r", \d+ (apps?|aplikasi)$|^(Clear search|Hapus pencarian)$|"
               r"^(Search|Cari) .+ (everywhere|di semua tempat)$|^(LX Files|File Manager|System Monitor|Monitor Sistem|Notes|Catatan|LX Settings|"
               r"Pengaturan LX)$")


def menu_hits(ui, regex, refresh=True):
    """Rows in the open Applications menu whose description matches regex. The menu's columns
    are the first, named after the menu, and the one beside it; only their descendants count,
    so a dock entry or desktop widget behind the menu (the clock widget is also "Jam") is not
    mistaken for a row."""
    nodes = ui.find(regex=r".+", refresh=refresh) and ui.nodes
    first = [i for i, n in enumerate(nodes) if n["pkg"] == PKG and re.fullmatch(_MENU_ROOT, n["desc"] or "")
             and n["bounds"][3] - n["bounds"][1] > 150 and n["ancestors"]]
    if not first:
        return []
    frame = nodes[first[0]]["ancestors"][-1]
    columns = {i for i, n in enumerate(nodes) if n["ancestors"] and n["ancestors"][-1] == frame}
    return [n for n in nodes if n["desc"] and re.search(regex, n["desc"]) and columns & set(n["ancestors"])]


def on_workspace(ui, n, timeout=8):
    """True when workspace n is the active one: the top panel names it on a
    tablet, and on a phone its button in the top panel is marked current."""
    return bool(ui.wait(regex=rf"^{n} · |^(Workspace|Ruang) {n}, (current workspace|workspace aktif)$", timeout=timeout))


def device_indonesian():
    """System apps carry Indonesian labels (Jam, Pengaturan) on an id-ID device."""
    return sh("getprop persist.sys.locale", check=False).strip().lower().startswith(("id", "in"))


def resumed_package():
    out = sh("dumpsys activity activities", check=False)
    m = re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*?(\S+)/(\S+)", out)
    return m.group(1).split()[-1] if m else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test")
    ap.add_argument("--restore-home", default=None,
                    help="Home activity to restore at the end (default: whatever was Home before the run)")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ui = Ui(args.out)
    results = []

    def step(name, ok, evidence=""):
        results.append({"step": name, "result": "PASS" if ok else "FAIL", "evidence": evidence})
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  {evidence}")
        return ok

    w, h = map(int, re.search(r"(\d+)x(\d+)", sh("wm size")).groups())
    original_home = args.restore_home or sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").strip().splitlines()[-1]
    print("display", w, h, "original home", original_home)

    try:
        sh("logcat -b crash -c", check=False)
        # 1. Choose LX as Home and press Home.
        sh("pm clear " + PKG)
        sh(f"cmd package set-home-activity {ACT}")
        sh("input keyevent KEYCODE_HOME")
        time.sleep(4)
        home = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").strip().splitlines()[-1]
        step("LX is the resolved Home activity", home.endswith("LauncherActivity") and PKG in home, home)
        step("Home key shows LX", resumed_package() == PKG or bool(ui.wait(label="Taskbar")), resumed_package())
        ui.shot("01-home")

        # 2. Welcome window is open and closes.
        welcome = ui.wait(regex=r"^(?:Window|Jendela) (Welcome|Selamat datang)$")
        step("Welcome window present on first start", bool(welcome), welcome[0]["desc"] if welcome else "")
        close = [n for n in ui.find(label="Close", refresh=False) + ui.find(label="Tutup", refresh=False)
                 if welcome and n["bounds"][1] < welcome[0]["bounds"][1] + 140]
        if close:
            ui.tap(close[0])
        step("Close button closes the window", not ui.find(regex=r"^(?:Window|Jendela) (Welcome|Selamat datang)$"))

        # 3. The Applications menu lists categories, and All apps lists the apps.
        ui.tap(ui.wait(regex=MENU)[-1])
        search = ui.wait(regex=r"^(Search apps|Cari aplikasi)$")
        step("Applications menu opens with a search field", bool(search))
        all_apps = menu_hits(ui, r"^(All apps|Semua aplikasi), \d+")
        step("Applications menu lists its categories", bool(all_apps), all_apps[0]["desc"] if all_apps else "")
        if all_apps:
            ui.tap(all_apps[0])
            time.sleep(1)
        ui.shot("02-menu")
        apps = [n for n in menu_hits(ui, r".") if not re.search(MENU_CHROME, n["desc"])]
        step("All apps lists installed apps", len(apps) > 3, f"{len(apps)} app rows")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)

        # 4. Search and launch an app, then return Home.
        ui.tap(search[0])
        # "ulator" matches the English and the Indonesian label (Calculator, Kalkulator).
        sh("input text ulator")
        time.sleep(1.2)
        calc = menu_hits(ui, r"Calculator|Kalkulator")
        step("Native search ranks the calculator for 'ulator'", bool(calc), calc[0]["desc"] or calc[0]["text"] if calc else "")
        if calc:
            ui.tap(calc[0])
            time.sleep(2.5)
            pkg = resumed_package()
            step("Tapping the result opens the app", "calculator" in pkg, pkg)
            ui.shot("03-calculator")
            sh("input keyevent KEYCODE_HOME")
            time.sleep(2)
            step("Home returns to LX", resumed_package() == PKG, resumed_package())

        # 5. Pin to taskbar through the long-press menu.
        ui.tap(ui.wait(regex=MENU)[-1])
        ui.tap(ui.wait(regex=r"^(Search apps|Cari aplikasi)$")[0])
        sh("input text ulator")
        time.sleep(1.2)
        calc = menu_hits(ui, r"Calculator|Kalkulator")
        if calc:
            ui.long_press(calc[0])
            pin = ui.wait(regex=r"^(Pin to taskbar|Sematkan ke taskbar)$")
            ui.shot("04-app-menu")
            step("Long press opens the app menu", bool(pin))
            if pin:
                ui.tap(pin[0])
        sh("input keyevent KEYCODE_HOME")  # closes the menu; Escape would first clear its search
        time.sleep(0.8)
        tb = ui.find(label="Taskbar")
        pinned = [n for n in bar_button(ui, r"Calculator|Kalkulator", timeout=2) if tb and n["bounds"][1] >= tb[0]["bounds"][1] - 4]
        step("Pinned app appears in the taskbar", bool(pinned))

        # 6. Add to desktop.
        ui.tap(ui.wait(regex=MENU)[-1])
        ui.tap(ui.wait(regex=r"^(Search apps|Cari aplikasi)$")[0])
        sh("input text music")
        time.sleep(1.2)
        music = menu_hits(ui, r"Music|Eleven|Musik")
        if music:
            ui.long_press(music[0])
            add = ui.wait(regex=r"^(Add to desktop|Tambah ke desktop)$")
            if add:
                ui.tap(add[0])
        sh("input keyevent KEYCODE_HOME")  # closes the menu; Escape would first clear its search
        time.sleep(1)
        desk = ui.find(label="Desktop") + ui.find(label="Desktop", refresh=False)
        on_desk = [n for n in ui.find(regex=r"Music|Eleven|Musik") if desk and n["pkg"] == PKG and
                   n["bounds"][0] >= desk[0]["bounds"][0] and n["bounds"][3] <= desk[0]["bounds"][3]]
        step("App shortcut added to the desktop", bool(on_desk))
        ui.shot("05-desktop-shortcut")

        # 7. Workspace switch from the taskbar.
        ws2 = ui.wait(regex=r"^(Switch to|Pindah ke) (Workspace|Ruang) 2$")
        if ws2:
            ui.tap(ws2[0])
        step("Taskbar switches to workspace 2", on_workspace(ui, 2))
        ui.shot("06-workspace2")
        ws1 = ui.wait(regex=r"^(Switch to|Pindah ke) (Workspace|Ruang) 1$")
        if ws1:
            ui.tap(ws1[0])

        # 8. LX Settings from the dock; change a setting and check it persisted.
        ui.tap(bar_button(ui, DOCK_SETTINGS)[0])
        win = ui.wait(regex=r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$")
        step("Dock opens LX Settings in a window", bool(win))
        ui.shot("07-settings")
        # The Appearance page is longer than the window; scroll its content until the row shows.
        accent = ui.wait(timeout=3, regex=r"^(Accent colour|Warna aksen),")
        for _ in range(5):
            if accent or not win:
                break
            x1, y1, x2, y2 = win[0]["bounds"]
            cx = (x1 + x2) // 2
            sh(f"input swipe {cx} {y2 - 80} {cx} {y1 + 200} 400")
            time.sleep(0.8)
            accent = ui.find(regex=r"^(Accent colour|Warna aksen),")
        if accent:
            ui.tap(accent[0])
            mag = ui.wait(regex=r"^(Neon magenta|Magenta neon)$")
            if mag:
                ui.tap(mag[0])
        time.sleep(1.5)
        accent = ui.wait(regex=r"^(Accent colour|Warna aksen), (Neon magenta|Magenta neon)$")
        step("Accent change is applied", bool(accent), accent[0]["desc"] if accent else "")
        ui.shot("08-accent-magenta")

        # 9. Window controls: maximise, minimise, restore from the taskbar.
        settings_re = r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$"

        def title_buttons(regex, w):
            return [n for n in ui.find(regex=regex) if w and n["bounds"][1] < w[0]["bounds"][1] + 120]

        def area(b):
            return (b[2] - b[0]) * (b[3] - b[1])

        win = ui.wait(regex=settings_re)
        # Narrow screens open tools maximised; let the window float first so maximise has room to grow.
        rs = title_buttons(r"^(Restore|Pulihkan)$", win)
        if rs:
            ui.tap(rs[0])
            time.sleep(1)
            win = ui.wait(regex=settings_re)
        before = win[0]["bounds"] if win else None
        mx = title_buttons(r"^(Maximise|Maksimalkan)$", win)
        if mx:
            ui.tap(mx[0])
        win2 = ui.wait(regex=settings_re)
        after = win2[0]["bounds"] if win2 else None
        step("Maximise enlarges the window", bool(before and after and area(after) > area(before)), f"{before} -> {after}")
        # Minimise sits in the title bar on wide windows and in the title menu on narrow ones.
        mn = title_buttons(r"^(Minimise|Minimalkan)$", win2)
        if mn:
            ui.tap(mn[0])
        elif win2:
            x1, y1, x2, _ = win2[0]["bounds"]
            ui.long_press({"bounds": (x1 + 80, y1 + 8, x1 + 200, y1 + 40)})
            item = ui.wait(regex=r"^(Minimise|Minimalkan)$")
            if item:
                ui.tap(item[0])
        time.sleep(0.8)
        step("Minimise hides the window", not ui.find(regex=settings_re))
        task = ui.wait(regex=r"(LX Settings|Pengaturan LX), (minimised|diminimalkan)$")
        step("Taskbar shows the minimised window", bool(task))
        if task:
            ui.tap(task[0])
        step("Taskbar restores the window", bool(ui.wait(regex=settings_re)))

        # 10. Drag the window by its title bar after restoring it to floating.
        win = ui.wait(regex=r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$")
        rs = [n for n in ui.find(regex=r"^(Restore|Pulihkan)$") if win and n["bounds"][1] < win[0]["bounds"][1] + 120]
        if rs:
            ui.tap(rs[0])
        win = ui.wait(regex=r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$")
        if win:
            x1, y1, x2, y2 = win[0]["bounds"]
            sx, sy = x1 + 120, y1 + 22
            sh(f"input swipe {sx} {sy} {sx + 180} {sy + 90} 700")
            time.sleep(1)
            moved = ui.wait(regex=r"^(?:Window|Jendela) (LX Settings|Pengaturan LX)$")
            nb = moved[0]["bounds"] if moved else None
            # The window may already touch the right edge, where it stops; it still moves down.
            step("Title bar drag moves the window", bool(nb and max(abs(nb[0] - x1), abs(nb[1] - y1)) > 60),
                 f"{win[0]['bounds']} -> {nb}")
            ui.shot("09-window-dragged")

        # 11. Add a native widget from Settings.
        time.sleep(1)
        settings_tab(ui, r"^(Widgets|Widget)$")
        cal = ui.wait(regex=r"^(Calendar|Kalender), (Add|Tambah)$")
        if cal:
            ui.tap(cal[0])
        time.sleep(1.5)
        frames = [n for n in ui.find(regex=r"^(Calendar|Kalender)$") if n["desc"] in ("Calendar", "Kalender")]
        step("Calendar widget added to the desktop", bool(frames), str(frames[0]["bounds"]) if frames else "")
        ui.shot("10-widget-added")

        # 12. Keyboard shortcut: Ctrl+K opens search, Escape closes it.
        sh("input keycombination 113 39", check=False)  # CTRL_LEFT + K
        time.sleep(1)
        opened = ui.find(regex=r"(Search apps, files|Cari aplikasi, berkas)")
        step("Ctrl+K opens search", bool(opened))
        sh("input keyevent KEYCODE_ESCAPE")
        time.sleep(0.8)

        # 13. Screen size change (stands in for rotation on Waydroid).
        sh("wm size 1028x1920")
        time.sleep(4)
        ui.shot("11-portrait")
        alive = resumed_package() == PKG and bool(ui.wait(label="Taskbar"))
        step("Layout survives a portrait size change", alive)
        sh("wm size reset")
        time.sleep(4)
        step("Layout survives returning to landscape", bool(ui.wait(label="Taskbar")))

        # 14. Process death: force stop and come back Home.
        sh("am force-stop " + PKG)
        time.sleep(1)
        sh("input keyevent KEYCODE_HOME")
        time.sleep(4)
        tb = ui.wait(label="Taskbar")
        pinned = [n for n in bar_button(ui, r"Calculator|Kalkulator", timeout=2) if tb and n["bounds"][1] >= tb[0]["bounds"][1] - 4]
        step("Pinned app survives process death", bool(pinned))
        ui.shot("12-after-restart")
        ui.tap(bar_button(ui, DOCK_SETTINGS)[0])
        accent_re = r"^(Accent colour|Warna aksen), (Neon magenta|Magenta neon)$"
        accent = ui.wait(regex=accent_re)
        swin = ui.find(regex=SETTINGS_WINDOW)
        for _ in range(4):
            if accent or not swin:
                break
            # The accent row sits below the theme previews; scroll the settings page.
            x1, y1, x2, y2 = swin[0]["bounds"]
            sh(f"input swipe {(x1 + x2) // 2} {y2 - 60} {(x1 + x2) // 2} {y1 + (y2 - y1) // 2} 400")
            time.sleep(0.8)
            accent = ui.find(regex=accent_re)
        step("Magenta accent survives process death", bool(accent), accent[0]["desc"] if accent else "")
        cal = [n for n in ui.find(regex=r"^(Calendar|Kalender)$") if n["desc"] in ("Calendar", "Kalender")]
        step("Added widget survives process death", bool(cal))

        crashes = sh("logcat -d -b crash", check=False)
        step("No crash recorded for LX in the crash buffer", PKG not in crashes,
             "crash buffer clean" if PKG not in crashes else crashes[:200])
    finally:
        # 15. Give Home back to the launcher that had it.
        sh("wm size reset", check=False)
        sh(f"cmd package set-home-activity {original_home}", check=False)
        # Every batch starts from cleared data; clear it again so no test state (accent, widgets,
        # the test wallpaper) stays behind on the device.
        sh("pm clear " + PKG, check=False)
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(2)
        restored = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                      check=False).strip().splitlines()[-1]
        step("Home restored to the previous launcher", restored == original_home, restored)

    with open(os.path.join(args.out, "results.json"), "w") as f:
        json.dump(results, f, indent=2)
    failed = [r for r in results if r["result"] == "FAIL"]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
