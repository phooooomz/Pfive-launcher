#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Real Android app windows launched from LX, checked through the system's own
records (dumpsys activity / window), not through screenshots.

A: one app opens as a freeform window smaller than the screen, takes touch input,
   moves by its caption and resizes from its corner.
B: a second app opens alongside; focus moves between them; the keyboard works in
   a window; Back works; a third window opens; minimise, restore (from LX),
   maximise and close work.
C: each listed app that is installed is launched from LX and its window mode and
   size recorded.

Needs a device where freeform windows are available (FEATURE_FREEFORM_WINDOW_MANAGEMENT,
or "Enable freeform windows" in Developer options followed by a restart). On other
devices every launch is expected to be full screen and the script says so.
Private apps (browsers, messengers) are only measured: no screenshots, no typing.

The caption is drawn by the system, not by LX. Its buttons are found by their
position in dp, which is the same on XOS 15 and on Waydroid (Android 13). A
caption button that is tapped at the right place and does nothing is reported
as PLATFORM, with the evidence, and counted apart from LX's own failures.

LX's own data is not cleared: it is copied off the device first and put back at the
end, then compared, so a launcher someone already set up is left as it was. Where
run-as is refused (Waydroid), LX's data is cleared before and after instead, and
on any other device without run-as the script stops before changing anything. On
Waydroid, the test apps are force-stopped before and after the run; on a phone they
are only closed through their caption, because stopping the clock there would drop
its alarms.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_app_windows.py --restore-home <component>
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_test import PKG, ACT, Ui, sh, bar_button, MENU, menu_hits  # noqa: E402

# (label regex, menu search texts tried in turn, package or part of it)
CALC = ("Kalkulator|Calculator", ("calc", "kalku"), "calculator")
CLOCK = ("Jam|Clock", ("clock", "jam"), "deskclock")
SETTINGS = ("Pengaturan|Setelan|Settings", ("setting", "pengaturan", "setelan"), "com.android.settings")
SURVEY = [("Chrome", ("chrome",), "com.android.chrome"), ("YouTube", ("youtube",), "com.google.android.youtube"),
          ("WhatsApp", ("whatsapp",), "com.whatsapp"), ("Files", ("files",), "com.google.android.apps.nbu.files"),
          ("Kontak|Contacts", ("contact", "kontak"), "com.google.android.contacts"),
          ("Galeri|Gallery", ("galler",), "com.android.gallery3d"), SETTINGS]

# System caption geometry in dp, measured on XOS 15 (320 dpi) and Waydroid 13 (180 dpi).
CAPTION_Y = 21
CAPTION_BUTTON = {"minimize": 108, "maximize": 64, "close": 21}
CAPTION_HEIGHT = 42


def tasks(pkg_part):
    out = sh("dumpsys activity activities", check=False)
    seen = {}
    for m in re.finditer(r"\* Task\{\w+ #(\d+) type=standard [AI]=([^ ]+) U=0 visible=(\w+) visibleRequested=\w+ mode=(\w+)", out):
        if pkg_part in m.group(2):
            seen.setdefault(int(m.group(1)), {"id": int(m.group(1)), "visible": m.group(3) == "true", "mode": m.group(4)})
    return list(seen.values())


def frame(pkg_part):
    out = sh("dumpsys window windows", check=False)
    for b in re.split(r"\n  Window #\d+ ", out):
        head = b.split("\n", 1)[0]
        if pkg_part in head and "Splash" not in head and "PopupWindow" not in head:
            m = re.search(r"mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b) or \
                re.search(r" frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b)
            if m:
                return tuple(int(x) for x in m.groups())
    return None


def focus():
    m = re.search(r"mCurrentFocus=Window\{\w+ u0 ([^ }/]+)", sh("dumpsys window", check=False))
    return m.group(1) if m else ""


def adb_cmd():
    serial = os.environ.get("ANDROID_SERIAL")
    return ["adb"] + (["-s", serial] if serial else [])


def run_as_works():
    return "uid=" in sh(f"run-as {PKG} id", check=False)


def save_data(path):
    """Copies LX's files and preferences to a tar on this machine; returns its digest."""
    sh(f"am force-stop {PKG}", check=False)
    data = subprocess.run(adb_cmd() + ["exec-out", f"run-as {PKG} tar cf - files shared_prefs"],
                          capture_output=True, timeout=60).stdout
    if not data:
        raise RuntimeError("could not copy LX's data off the device")
    with open(path, "wb") as f:
        f.write(data)
    return digest()


def digest():
    out = sh(f"run-as {PKG} sh -c 'cat files/* shared_prefs/* 2>/dev/null' | md5sum", check=False)
    return out.split()[0] if out.strip() else ""


def restore_data(path):
    # The renaming copy: unpacking straight into emptied folders lost the race against Android restarting LX as Home.
    from ui_test_file_manager import restore_data as restore_checked
    restore_checked(path)
    return digest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-app-windows")
    ap.add_argument("--restore-home", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ui = Ui(args.out)
    results = []
    sw, shh = map(int, re.search(r"(\d+)x(\d+)", sh("wm size").strip().splitlines()[-1]).groups())
    density = int(re.findall(r"(\d+)", sh("wm density").strip().splitlines()[-1])[-1])
    waydroid = "waydroid" in sh("getprop ro.product.device", check=False).lower()
    installed = set(re.findall(r"package:(\S+)", sh("pm list packages", check=False)))

    def dp(v):
        return round(v * density / 160)

    def present(app):
        return any(app[2] in p for p in installed)

    survey_apps = [a for a in SURVEY if present(a)]
    apps = [a for a in [CALC, CLOCK, SETTINGS] + survey_apps if present(a)]
    apps = list(dict.fromkeys(apps))

    def step(name, ok, evidence="", platform=False):
        result = "PASS" if ok else ("PLATFORM" if platform else "FAIL")
        results.append({"step": name, "result": result, "evidence": evidence})
        print(f"[{result}] {name}  {evidence}", flush=True)
        return ok

    def tap_re(regex, timeout=8):
        n = ui.wait(timeout=timeout, regex=regex)
        if n:
            ui.tap(n[0])
        return bool(n)

    def tap_at(x, y):
        sh(f"input tap {x} {y}")
        time.sleep(1.2)

    # While an app window has focus, the UI dump only shows that window, and a tap on LX
    # reaches LX without moving focus to it. Bar buttons are located once, while LX has focus.
    bar = {}

    def read_bar(refresh=True):
        """Bar buttons are the nodes inside the taskbar whose description is the app's name."""
        tb = ui.find(label="Taskbar", refresh=refresh)
        if not tb:
            return
        x1, y1, x2, y2 = tb[0]["bounds"]
        for label, _, _ in apps:
            hit = [n for n in ui.find(regex=rf"^({label})$", refresh=False) if n["pkg"] == PKG
                   and re.fullmatch(label, n["desc"]) and x1 <= n["bounds"][0] and n["bounds"][2] <= x2
                   and y1 <= n["bounds"][1] and n["bounds"][3] <= y2]
            if hit:
                bar[label] = hit[0]
            else:
                bar.pop(label, None)

    def launch(app):
        """Taps the app's bar button. The bar's contents can shift (running apps join it), so its
        positions are read again whenever LX has focus and the dump can see them."""
        if focus() == PKG:
            read_bar()
        node = bar.get(app[0])
        if node:
            ui.tap(node)
            time.sleep(3.5)
        return bool(node)

    def visible(pkg_part):
        return [x for x in tasks(pkg_part) if x["visible"]]

    def windowed(pkg_part):
        t = visible(pkg_part)
        f = frame(pkg_part)
        small = f is not None and (f[2] - f[0]) < sw and (f[3] - f[1]) < shh
        return bool(t) and t[0]["mode"] == "freeform" and small, (t[0]["mode"] if t else "none", f)

    def caption(pkg_part, which):
        """Taps a caption button; returns where, or None when the window has no frame."""
        f = frame(pkg_part)
        if not f:
            return None
        at = (f[2] - dp(CAPTION_BUTTON[which]), f[1] + dp(CAPTION_Y))
        tap_at(*at)
        time.sleep(0.5)
        return at

    def drag_by_caption(f, dx, dy):
        """Drags a window by the empty stretch of caption between its back and minimise buttons."""
        x, y = (f[0] + dp(42) + f[2] - dp(CAPTION_BUTTON["minimize"]) - dp(20)) // 2, f[1] + dp(CAPTION_Y)
        sh(f"input swipe {x} {y} {x + dx} {y + dy} 900")
        time.sleep(1.5)

    def uncovered(f, other, y_from):
        """The middle of the widest stretch of window f, at least y_from below its top and away
        from its edges, that window other does not cover; None when no stretch is dp(56) wide,
        about one fingertip."""
        best = None
        for y in range(f[1] + y_from, min(f[3], shh) - dp(24), dp(8)):
            run = []
            for x in range(f[0] + dp(24), min(f[2], sw) - dp(24), dp(4)):
                if other[0] <= x <= other[2] and other[1] <= y <= other[3]:
                    run = []
                    continue
                run.append(x)
                if len(run) * dp(4) >= dp(56) and (best is None or len(run) > best[0]):
                    best = (len(run), (run[0] + run[-1]) // 2, y)
            if best:
                return best[1], best[2]
        return None

    original_home = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                       check=False).strip().splitlines()[-1]
    print(f"device: {sw}x{shh} @{density} dpi, waydroid={waydroid}, home={original_home}", flush=True)
    sh("logcat -b crash -c", check=False)
    if waydroid:
        # Start every test app fresh: a calculator left holding text typed by an earlier suite, or a
        # task an earlier run maximised, would decide the result before LX does anything.
        for app in apps:
            for p in installed:
                if app[2] in p and p != PKG:
                    sh(f"am force-stop {p}", check=False)
    backup = os.path.join(args.out, "lx-data.tar")
    saved = run_as_works()
    if saved:
        before = save_data(backup)
        print("LX data saved", before, flush=True)
    elif waydroid:
        # run-as is refused on Waydroid, so LX's data cannot be copied off; Waydroid is a test
        # container, so LX starts clean instead and is cleared again at the end.
        sh("pm clear " + PKG)
        print("NOTE run-as is not available here: LX's data was cleared and will be cleared again at the end", flush=True)
    else:
        print("run-as is not available, so LX's data could not be saved; nothing was changed", flush=True)
        return 2
    try:
        sh(f"cmd package set-home-activity {ACT}")
        sh("input keyevent KEYCODE_HOME")
        time.sleep(4)
        tap_re(r"^(Close|Tutup)$", timeout=3)
        # On a tablet or desktop the dock is a column at the side and pinned apps go to the taskbar;
        # on a phone the dock's entries lead the bottom bar, so apps are pinned to the dock.
        dock = ui.find(label="Dock")
        side_dock = bool(dock) and (dock[0]["bounds"][3] - dock[0]["bounds"][1]) > (dock[0]["bounds"][2] - dock[0]["bounds"][0])
        pin = r"^(Pin to taskbar|Sematkan ke taskbar)$" if side_dock else r"^(Pin to dock|Sematkan ke dock)$"
        # Empty the dock, then pin every app to it: dock entries come first in the bar, so all of
        # them stay on screen. This happens before any window opens, because typed text then goes
        # to the window.
        for _ in range(12):
            tb = ui.find(label="Taskbar")
            if not tb:
                break
            y1, y2 = tb[0]["bounds"][1], tb[0]["bounds"][3]
            entries = [n for n in ui.find(regex=r".+", refresh=False) if n["desc"] and n["pkg"] == PKG
                       and y1 <= n["bounds"][1] and n["bounds"][3] <= y2 and n["desc"] not in ("Taskbar",)
                       and not re.search(r"(Applications|Aplikasi|Workspace|Ruang|Search|Cari)", n["desc"])]
            if not entries:
                break
            ui.long_press(entries[0])
            if not tap_re(r"^(Remove from dock|Lepas dari dock)$", timeout=3):
                sh("input keyevent KEYCODE_BACK")
                break
            time.sleep(0.8)
        for label, queries, _ in apps:
            # Pinning is set-up, not the behaviour under test; each query gets a second try, because one
            # Waydroid run on 2026-10-30 lost the Gallery pin once and passed on the next run.
            for query in [q for q in queries for _ in range(2)]:
                ui.tap(bar_button(ui, MENU)[-1])
                if not ui.wait(timeout=6, regex=r"^(Search apps|Cari aplikasi)$"):
                    sh("input keyevent KEYCODE_HOME")
                    continue
                sh(f"input text {query}")
                time.sleep(1.5)
                if not ui.find(label=query):
                    print(f"NOTE typed '{query}' did not reach the menu's search field", flush=True)
                hit = menu_hits(ui, rf"^({label})$")
                if hit:
                    ui.long_press(hit[0])
                    tap_re(pin, timeout=4)
                    time.sleep(0.8)
                # Home closes the menu whatever it shows; Back would first clear the search.
                sh("input keyevent KEYCODE_HOME")
                time.sleep(1)
                read_bar()
                if label in bar:
                    break

        time.sleep(1.5)
        read_bar()
        step("All test apps are on LX's bar", len(bar) == len(apps), [sorted(bar), [a[2] for a in apps]])

        # ---- A: one app ----
        launch(CALC)
        ok, ev = windowed(CALC[2])
        step("A. App launched from LX opens as a freeform window smaller than the screen", ok, ev)
        ui.shot("01-calculator-window")
        for key in ("7", "\\+", "8", "="):
            tap_re(rf"^{key}$|^(plus|tambah)$" if key == "\\+" else rf"^{key}$|^(equals|sama dengan)$" if key == "=" else rf"^{key}$",
                   timeout=4)
            time.sleep(0.4)
        shown = ui.find(regex=r"^15$")
        step("A. Touch input reaches the app (7 + 8 = 15)", bool(shown))
        f0 = frame(CALC[2])
        if f0:
            drag_by_caption(f0, dp(30), dp(110))
        f1 = frame(CALC[2])
        step("A. The window moves when its caption is dragged", bool(f0 and f1 and f1[1] - f0[1] > dp(50)), f"{f0} -> {f1}")
        how = None
        if f1:
            # XOS takes a resize from just inside the corner; AOSP 13 from just outside it.
            for inset, name in ((dp(3), "inside"), (-dp(6), "outside")):
                x, y = f1[2] - inset, f1[3] - inset
                sh(f"input swipe {x} {y} {x - dp(50)} {y - dp(75)} 900")
                time.sleep(1.5)
                f2 = frame(CALC[2])
                if f2 and (f2[2] - f2[0], f2[3] - f2[1]) != (f1[2] - f1[0], f1[3] - f1[1]):
                    how = name
                    break
        f2 = frame(CALC[2])
        step("A. The window resizes from its corner", how is not None, f"{f1} -> {f2} ({how})")

        # ---- B: two apps ----
        # Settings comes second and the clock last: a clock window that redraws every second stops
        # uiautomator from taking a dump, so the steps that read the UI come first.
        launch(SETTINGS)
        ok, ev = windowed(SETTINGS[2])
        both = [x for x in tasks(CALC[2]) + tasks(SETTINGS[2]) if x["visible"] and x["mode"] == "freeform"]
        step("B. A second app opens as a window while the first stays visible", ok and len(both) >= 2, [ev, both])
        ui.shot("02-two-windows")
        step("B. The newest window has focus", SETTINGS[2] in focus(), focus())
        # The keyboard first, while nothing lies over Settings' search field.
        field = [n for n in ui.wait(timeout=5, regex=r"^(Search settings|Cari setelan|Telusuri setelan|Cari|Search)$")
                 if "settings" in n["pkg"]]
        if field:
            ui.tap(field[0])
            time.sleep(1.5)
            sh("input text wifi")
            time.sleep(2)
        ime = "mInputShown=true" in sh("dumpsys input_method", check=False)
        typed = [n for n in ui.find(regex=r"^wifi$") if "settings" in n["pkg"]]
        step("B. The keyboard types into an app window", bool(field) and bool(typed),
             f"field={bool(field)} typed={bool(typed)} on-screen keyboard shown={ime}")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)
        if ime:
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1.2)
        alive = visible(SETTINGS[2])
        step("B. Back works inside the window and the app stays open", bool(alive), alive)

        # Slide Settings sideways so the calculator's left part shows. Sideways, because a window
        # pushed down would cover LX's taskbar: app windows always sit above the home screen.
        fc, fs = frame(CALC[2]), frame(SETTINGS[2])
        if fc and fs:
            drag_by_caption(fs, max(0, fc[0] + dp(140) - fs[0]), 0)
        fc, fs = frame(CALC[2]), frame(SETTINGS[2])
        spot = uncovered(fc, fs, dp(CAPTION_HEIGHT + 60)) if fc and fs else None
        if spot:
            tap_at(*spot)
        step("B. Tapping the other window moves focus to it", CALC[2] in focus(), [fs, spot, focus()])
        # The calculator now lies over part of Settings. On XOS focus follows a touch only when the
        # window's content handles it (a tap on the caption or on blank space leaves focus where it
        # was), so Settings gets a short scroll down and back up where the calculator does not cover it.
        fc, fs = frame(CALC[2]), frame(SETTINGS[2])
        spot = uncovered(fs, fc, dp(CAPTION_HEIGHT + 90)) if fc and fs else None
        if spot:
            x, y = spot
            sh(f"input swipe {x} {y + dp(30)} {x} {y - dp(20)} 300")
            time.sleep(0.8)
            sh(f"input swipe {x} {y - dp(20)} {x} {y + dp(30)} 300")
            time.sleep(1.2)
        step("B. Touching the first window again gives it focus back", SETTINGS[2] in focus(), [spot, focus()])

        launch(CLOCK)
        ok, ev = windowed(CLOCK[2])
        three = [x for x in tasks(CALC[2]) + tasks(SETTINGS[2]) + tasks(CLOCK[2]) if x["visible"] and x["mode"] == "freeform"]
        step("B. A third app opens as a window; all three are on screen", ok and len(three) >= 3, [ev, three])
        shown_before = bool(visible(CLOCK[2]))
        at = caption(CLOCK[2], "minimize")
        step("B. Minimise hides the window", shown_before and not visible(CLOCK[2]) and bool(tasks(CLOCK[2])),
             [at, tasks(CLOCK[2])])
        launch(CLOCK)
        back = [x for x in visible(CLOCK[2]) if x["mode"] == "freeform"]
        step("B. Launching it again from LX restores the window", bool(back), tasks(CLOCK[2]))
        def close(app):
            """Brings a window to the front from LX and taps its close button. Another window can lie
            over the caption, so the tap only counts once the app holds focus. Returns (closed, evidence)."""
            sh("input keyevent KEYCODE_HOME")
            time.sleep(1.5)
            launch(app)
            had = visible(app[2])
            if not had or had[0]["mode"] != "freeform":
                return False, {"before": tasks(app[2]), "note": "not on screen as a window"}
            if app[2] not in focus():
                return False, {"before": had, "note": f"not in front, focus is {focus()}"}
            f = frame(app[2])
            at = caption(app[2], "close")
            left = tasks(app[2])
            return not left, {"tap": at, "frame": f, "before": had, "after": left}

        # Close Settings and the calculator while both are windows on screen. The minimise button on
        # the same caption row, found with the same arithmetic, worked above; a close button that does
        # nothing there is the system's, not LX's.
        closing = {app[2]: close(app) for app in (SETTINGS, CALC)}
        closed = all(c for c, _ in closing.values())
        on_screen = all("tap" in ev for _, ev in closing.values())
        minimise_ok = any(r["result"] == "PASS" for r in results if r["step"].startswith("B. Minimise"))
        step("B. Close ends a window's task", closed, closing, platform=not closed and on_screen and minimise_ok)
        for app in (SETTINGS, CALC):
            if visible(app[2]):
                caption(app[2], "minimize")

        # Closing went through Home, which hides the other windows on some systems.
        if not visible(CLOCK[2]):
            launch(CLOCK)
        at = caption(CLOCK[2], "maximize")
        fm = frame(CLOCK[2])
        step("B. Maximise fills the screen", bool(fm and (fm[2] - fm[0]) >= sw - 4), [at, fm, tasks(CLOCK[2])])
        # Home returns to LX, and LX asks for a window again. XOS turns the running task back into a
        # window; Android 13 brings the same task forward and keeps it maximised, because launch
        # options only shape a new task. That case is the platform's and is reported as such.
        prior = tasks(CLOCK[2])
        sh("input keyevent KEYCODE_HOME")
        time.sleep(2)
        launch(CLOCK)
        ok, ev = windowed(CLOCK[2])
        later = tasks(CLOCK[2])
        kept = (not ok and bool(prior) and bool(later) and prior[0]["id"] == later[0]["id"]
                and later[0]["visible"] and later[0]["mode"] == "fullscreen")
        step("B. After maximise, Home and a launch from LX bring it back as a window", ok, [ev, prior, later], platform=kept)
        if ok:
            c, ev = close(CLOCK)
            step("B. Close ends the restored window's task", c, ev, platform=not c and "tap" in ev and minimise_ok)
        else:
            # Waydroid draws a maximised window's caption under its status bar, out of reach.
            sh("input keyevent KEYCODE_HOME")
            time.sleep(2)

        # ---- C: individual apps ----
        survey = {}
        for app in survey_apps:
            pkg = app[2]
            if not launch(app):
                survey[pkg] = {"windowed": False, "note": "not on the bar"}
                continue
            ok, ev = windowed(pkg)
            survey[pkg] = {"windowed": ok, "mode": ev[0], "frame": ev[1]}
            if ok:
                caption(pkg, "close")
            if visible(pkg):
                if ok:
                    caption(pkg, "minimize")
                else:
                    sh("input keyevent KEYCODE_HOME")
                time.sleep(1)
            survey[pkg]["closed"] = not tasks(pkg)
        for pkg, r in survey.items():
            step(f"C. {pkg} opens as a window", r["windowed"], r)

        crashes = sh("logcat -d -b crash", check=False)
        step("LX did not crash", PKG not in crashes, "crash buffer clean" if PKG not in crashes else crashes[:200])
    finally:
        if waydroid:
            # Waydroid's caption close does nothing (see the PLATFORM step); its apps hold no one's data.
            for app in apps:
                for p in installed:
                    if app[2] in p and p != PKG:
                        sh(f"am force-stop {p}", check=False)
        sh(f"cmd package set-home-activity {args.restore_home}", check=False)
        if saved:
            after = restore_data(backup)
            if step("LX's own data is back as it was before the run", bool(before) and after == before,
                    f"{before} -> {after}"):
                os.remove(backup)
        else:
            sh("pm clear " + PKG, check=False)
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(2)
        restored = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                      check=False).strip().splitlines()[-1]
        step("Home restored to the previous launcher", restored == args.restore_home, restored)
        left = [x for app in apps for x in tasks(app[2])]
        step("No window the test opened is left behind", not left, left)

    with open(os.path.join(args.out, "results.json"), "w") as f:
        json.dump(results, f, indent=2)
    count = {k: sum(1 for r in results if r["result"] == k) for k in ("PASS", "FAIL", "PLATFORM")}
    print(f"\n{count['PASS']} passed, {count['FAIL']} failed, {count['PLATFORM']} platform-limited")
    return 1 if count["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
