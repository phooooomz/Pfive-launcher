#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""UI test for File Manager on a real phone, safe for the owner's own Home screen.

It never clears LX's data. LX's files and preferences are copied off the device first
(run-as) and put back at the end, the All files access mode is restored to what it was,
and every fixture lives in a folder named LXFM-Test on internal storage and, when a card
is mounted, on the SD card; both folders are removed afterwards.

Archive results are checked on the device with md5sum, not on screen.

Usage: ANDROID_SERIAL=<serial> scripts/ui_test_file_manager.py --out build/ui-test-fm [--cycles 40]
"""
import argparse
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_test import PKG, Ui, adb, sh  # noqa: E402

FIX = "LXFM-Test"
INTERNAL = "/storage/emulated/0"
WINDOW = r"^(Window|Jendela) File Manager$"
FM_BUTTON = r"^File Manager(, (open|terbuka|active window|jendela aktif|minimised|diminimalkan))?$"
APPS = r"^(Applications|Aplikasi)$"


def adb_cmd():
    serial = os.environ.get("ANDROID_SERIAL")
    return ["adb"] + (["-s", serial] if serial else [])


def layout_header(data):
    """The first line of files/layout.lxl inside a tar of LX's data (format, checksum and length), or None."""
    with tarfile.open(fileobj=io.BytesIO(data)) as t:
        try:
            f = t.extractfile("files/layout.lxl")
        except KeyError:
            return None
        return f.readline().strip() if f else None


def save_data(path):
    sh(f"am force-stop {PKG}", check=False)
    data = subprocess.run(adb_cmd() + ["exec-out", f"run-as {PKG} tar cf - files shared_prefs"],
                          capture_output=True, timeout=60).stdout
    if not data or layout_header(data) is None:
        raise RuntimeError("could not copy LX's data and layout off the device; stopping before anything changes")
    if nested_in(data):
        raise RuntimeError("LX's data holds files/files or shared_prefs/shared_prefs from an earlier restore; "
                           "move those back by hand before testing")
    with open(path, "wb") as f:
        f.write(data)


def top_entries(data):
    """The names directly inside files/ and shared_prefs/ in a tar of LX's data."""
    out = {"files": set(), "shared_prefs": set()}
    with tarfile.open(fileobj=io.BytesIO(data)) as t:
        for name in t.getnames():
            parts = name.strip("/").split("/")
            if len(parts) >= 2 and parts[0] in out:
                out[parts[0]].add(parts[1])
    return out


def nested_in(data):
    e = top_entries(data)
    return "files" in e["files"] or "shared_prefs" in e["shared_prefs"]


def restore_data(path):
    """Puts LX's files and preferences back as [path] holds them, and checks that the layout on the device is the copy's.

    LX is the phone's Home, and Android starts it again by itself, once within 200 ms of a force-stop on 13 November
    2026. Deleting LX's folders and then unpacking into them lost that race: LX found no layout, made the default one
    with its welcome window, and saved it over the owner's. The copy is now unpacked beside the live folders, swapped in
    by renames between two force-stops, and read back; a lost race is tried again.

    On 14 November 2026 the race was lost the other way: LX made a new shared_prefs between the delete and the rename,
    the rename moved the copy inside it, and the owner's preferences sat in shared_prefs/shared_prefs, unread, while the
    layout check passed. The swap now runs in one shell with `mv -T`, which fails instead of nesting, and the check also
    looks for nested folders and for every file and preference the copy holds.
    """
    with open(path, "rb") as f:
        data = f.read()
    want = layout_header(data)
    entries = top_entries(data)
    stage = "restore.tmp"
    got, missing = None, None
    for _ in range(4):
        sh(f"run-as {PKG} rm -rf {stage}", check=False)
        sh(f"run-as {PKG} mkdir {stage}", check=False)
        subprocess.run(adb_cmd() + ["shell", f"run-as {PKG} tar xf - -C {stage}"], input=data, capture_output=True, timeout=60)
        sh(f"am force-stop {PKG}; run-as {PKG} sh -c 'rm -rf files shared_prefs && mv -T {stage}/files files && "
           f"mv -T {stage}/shared_prefs shared_prefs'; am force-stop {PKG}", check=False)
        sh(f"run-as {PKG} rm -rf {stage}", check=False)
        got = subprocess.run(adb_cmd() + ["exec-out", f"run-as {PKG} head -n 1 files/layout.lxl"],
                             capture_output=True, timeout=30).stdout.strip()
        missing = []
        for folder, names in entries.items():
            have = set(sh(f"run-as {PKG} ls -1A {folder}", check=False).split())
            missing += [f"{folder}/{n}" for n in sorted(names - have)]
            if folder in have:
                missing.append(f"{folder}/{folder} (nested)")
        if got == want and not missing:
            return
        time.sleep(1)
    raise RuntimeError(f"LX's data did not come back: layout {got!r} against {want!r}, missing {missing}; restore {path} by hand")


def sd_root():
    for line in sh("sm list-volumes public", check=False).splitlines():
        m = re.search(r"mounted (\S+)$", line.strip())
        if m and m.group(1) != "null":
            return f"/storage/{m.group(1)}"
    return None


def md5(path):
    out = sh(f"md5sum '{path}' 2>/dev/null", check=False).split()
    return out[0] if out else ""


def tree(path):
    """Relative path -> md5 for every file under path (on the device)."""
    out = sh(f"cd '{path}' 2>/dev/null && find . -type f -exec md5sum {{}} +", check=False)
    result = {}
    for line in out.splitlines():
        parts = line.split(None, 1)
        if len(parts) == 2:
            result[parts[1].strip()[2:]] = parts[0]
    return result


def hostile_zip():
    """A ZIP that tries to escape with ../, an absolute path and a symlink, next to two honest files."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("ok/readme.txt", "inside\n")
        z.writestr("../escape.txt", "escaped\n")
        z.writestr("/abs-escape.txt", "escaped\n")
        link = zipfile.ZipInfo("ok/link")
        link.create_system = 3
        link.external_attr = (0o120777 << 16)
        z.writestr(link, "/sdcard")
        z.writestr("ok/second.txt", "inside too\n")
    return buf.getvalue()


def push_bytes(data, dest):
    with tempfile.NamedTemporaryFile(delete=False) as f:
        f.write(data)
        name = f.name
    try:
        adb("push", name, dest)
    finally:
        os.unlink(name)


def make_fixtures(base):
    sh(f"mkdir -p '{base}/proj/src' '{base}/proj/empty' '{base}/sort'", check=False)
    files = {
        "proj/readme.md": "# MyProject\n",
        "proj/src/main.c": "int main(void) { return 0; }\n",
        "proj/Ünïcødé ✓.txt": "unicode name\n",
        "sort/b 2.txt": "2\n",
        "sort/b 10.txt": "10\n",
        "sort/a.txt": "a\n",
        ".hidden-note": "hidden\n",
    }
    for rel, text in files.items():
        push_bytes(text.encode(), f"{base}/{rel}")
    sh(f"dd if=/dev/urandom of='{base}/proj/blob.bin' bs=1048576 count=24 2>/dev/null", check=False, timeout=120)
    push_bytes(hostile_zip(), f"{base}/evil.zip")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/ui-test-fm")
    ap.add_argument("--cycles", type=int, default=40)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ui = Ui(args.out)
    results = []

    def step(name, ok, evidence=""):
        results.append({"step": name, "result": "PASS" if ok else "FAIL", "evidence": evidence})
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  {evidence}", flush=True)
        return ok

    if "uid=" not in sh(f"run-as {PKG} id", check=False):
        print("run-as is not available for LX; this test refuses to run without a way to restore LX's data")
        return 2
    backup = os.path.join(args.out, "lx-data-before.tar")
    save_data(backup)
    appop_before = sh(f"appops get {PKG} MANAGE_EXTERNAL_STORAGE", check=False).strip()
    sd = sd_root()
    bases = [f"{INTERNAL}/Documents/{FIX}"] + ([f"{sd}/{FIX}"] if sd else [])
    print("sd card:", sd, "appop before:", appop_before)
    try:
        for b in bases:
            sh(f"rm -rf '{b}'", check=False)
        make_fixtures(bases[0])
        if sd:
            sh(f"mkdir -p '{bases[1]}'", check=False)
        sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow", check=False)
        sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(2)

        # Start from a closed File Manager, so the first tap opens it.
        open_win = ui.find(regex=WINDOW)
        if open_win:
            x1, y1, x2, y2 = open_win[0]["bounds"]
            close = [n for n in ui.find(regex=r"^(Tutup|Close)$", refresh=False) if x1 <= n["bounds"][0] < x2 and y1 <= n["bounds"][1] < y1 + 140]
            if close:
                ui.tap(close[0])
                time.sleep(1)

        # Taskbar: File Manager sits right of the Applications button.
        apps = ui.wait(regex=APPS)
        fm = ui.find(regex=FM_BUTTON, refresh=False)
        ok = bool(apps and fm) and fm[0]["bounds"][0] >= apps[0]["bounds"][2] - 4 and \
            fm[0]["bounds"][0] - apps[0]["bounds"][2] < 60
        step("taskbar: File Manager button right of Applications", ok,
             f"apps={apps[0]['bounds'] if apps else None} fm={fm[0]['bounds'] if fm else None}")
        if not fm:
            raise RuntimeError("no File Manager button")
        ui.tap(fm[0])
        win = ui.wait(regex=WINDOW, timeout=10)
        step("File Manager opens as an LX window", bool(win), str(win[0]["bounds"]) if win else "")
        ui.shot("01-open")

        # Re-tapping the button never opens a second window: it minimises the focused window and
        # restores it on the next tap.
        for _ in range(2):
            ui.tap(ui.find(regex=FM_BUTTON)[0])
            time.sleep(0.6)
        wins = ui.wait(regex=WINDOW)
        step("taskbar button restores the same window, never a duplicate", len(wins) == 1, f"windows={len(wins)}")

        fm_win = lambda: (ui.find(regex=WINDOW) or [None])[0]

        def inside(n):
            w = fm_win()
            return w is not None and w["bounds"][0] <= n["bounds"][0] and n["bounds"][2] <= w["bounds"][2] + 2 and \
                w["bounds"][1] <= n["bounds"][1] and n["bounds"][3] <= w["bounds"][3] + 2

        def scroll_content(up=True):
            w = fm_win()
            if not w:
                return
            x = (w["bounds"][0] + w["bounds"][2]) // 2
            a, b = (w["bounds"][3] - 160, w["bounds"][1] + 420) if up else (w["bounds"][1] + 420, w["bounds"][3] - 160)
            sh(f"input swipe {x} {a} {x} {b} 350")
            time.sleep(0.8)

        def tap_label(regex, timeout=6, long=False, scroll=False):
            found = [n for n in ui.wait(regex=regex, timeout=timeout) if n["pkg"] == PKG]
            # Items further down a long folder are off screen until the list scrolls to them.
            if not found and scroll:
                for direction in (True, True, True, False, False, False, False):
                    scroll_content(direction)
                    found = [n for n in ui.find(regex=regex) if n["pkg"] == PKG]
                    if found:
                        break
            if not found:
                return False
            # The topmost clickable match: a label can also sit on something covered, such as a folder
            # named Documents under the open drawer, or the selection bar's Copy under an open menu.
            # Views drawn on top come later in the dump.
            clickable = [n for n in found if n["clickable"]]
            (ui.long_press if long else ui.tap)((clickable or found)[-1])
            return True

        def in_fixture(sub=""):
            """True when the path line ends in the fixture folder (or its subfolder); every step that
            writes or deletes runs only then, so nothing lands in the owner's own folders."""
            want = sub or FIX
            w = fm_win()
            if not w:
                return False
            # The crumbs share the path field with its three-dot button, the topmost one in the window.
            field = [n for n in ui.find(regex=r"^(Tindakan lain|More actions)$", refresh=False) if n["pkg"] == PKG]
            if not field:
                return False
            y1, y2 = min(n["bounds"][1] for n in field), min(n["bounds"][3] for n in field)
            # Only the window's own nodes: desktop widgets behind it can share the height of the path field.
            own = ui.nodes.index(w)
            crumbs = [n for n in ui.nodes if own in n["ancestors"] and n["text"] and n["text"] != "/" and y1 <= n["bounds"][1] < y2]
            return bool(crumbs) and crumbs[-1]["text"] == want

        def menu_path(*labels):
            """Opens the window's menu and walks it: each label is a row; rows with a submenu are tapped to open it."""
            if not tap_label(r"^Menu$"):
                return False
            for label in labels:
                time.sleep(0.5)
                if not tap_label(rf"^{label}(, (membuka submenu|opens a submenu))?$"):
                    sh("input keyevent KEYCODE_BACK")
                    return False
            return True

        def open_item(name, timeout=8):
            return tap_label(rf"^{re.escape(name)}$", timeout=timeout, scroll=True)

        def device_exists(path):
            return sh(f"[ -e '{path}' ] && echo yes || echo no", check=False).strip() == "yes"

        def wait_device(path, timeout=40, gone=False):
            end = time.time() + timeout
            while time.time() < end:
                if device_exists(path) != gone:
                    return True
                time.sleep(1)
            return False

        def go_fixture():
            """Sidebar > Documents > LXFM-Test."""
            tap_label(r"^(Tempat|Places)$")
            time.sleep(0.8)
            if not tap_label(r"^(Dokumen|Documents)(, .*)?$"):
                return False
            time.sleep(1.5)
            return open_item(FIX)

        # Navigation through the sidebar to the fixture folder, and the path line.
        ok = go_fixture()
        time.sleep(1.2)
        here = in_fixture()
        step("sidebar Documents opens, the fixture folder opens, the path names it", ok and here,
             f"crumbs={[n['text'] for n in ui.nodes if n['text'] in ('Documents', FIX)]}")
        base = bases[0]
        if not here:
            raise RuntimeError("not in the fixture folder; stopping before any step writes to storage")

        # Hidden files stay hidden until the View menu shows them.
        hidden_before = bool(ui.find(label=".hidden-note"))
        menu_path("Tampilan", "Tampilkan berkas tersembunyi")
        hidden_after = bool(ui.wait(label=".hidden-note", timeout=5))
        menu_path("Tampilan", "Tampilkan berkas tersembunyi")
        step("hidden files toggle", not hidden_before and hidden_after, f"before={hidden_before} after={hidden_after}")

        # Names sort the way people count: b 2 before b 10, in the list view.
        open_item("sort")
        time.sleep(1.5)
        menu_path("Tampilan", "Daftar")
        time.sleep(1.0)
        rows = {n["text"]: n["bounds"][1] for n in ui.find(regex=r"^(a|b 2|b 10)\.txt$") if n["text"]}
        ok = len(rows) == 3 and rows["a.txt"] < rows["b 2.txt"] < rows["b 10.txt"]
        step("natural sort in the list view: a, b 2, b 10", ok, str(rows))
        menu_path("Tampilan", "Kisi")
        tap_label(r"^(Mundur|Back)$")
        time.sleep(1.2)

        # New folder and new text file through File > New, written to the device.
        if not in_fixture():
            raise RuntimeError("left the fixture folder; stopping")
        menu_path("Berkas", "Baru", "Folder baru")
        time.sleep(0.8)
        sh("input keyevent KEYCODE_MOVE_END")
        sh("input keyevent --longpress KEYCODE_DEL")
        for _ in range(20):
            sh("input keyevent KEYCODE_DEL")
        sh("input text Uji%sFolder")
        tap_label(r"^Buat$")
        step("File > New > Folder creates the folder", wait_device(f"{base}/Uji Folder", 10), "")
        menu_path("Berkas", "Baru", "Berkas teks")
        time.sleep(0.8)
        tap_label(r"^Buat$")
        step("File > New > Text file creates Teks baru.txt", wait_device(f"{base}/Teks baru.txt", 10), "")

        # Rename through the context menu (long press).
        tap_label(r"^Uji Folder$", long=True, scroll=True)
        tap_label(r"^Ganti nama$")
        time.sleep(0.8)
        sh("input keyevent KEYCODE_MOVE_END")
        for _ in range(12):
            sh("input keyevent KEYCODE_DEL")
        sh("input text Uji%sGanti")
        tap_label(r"^Ganti nama$")
        step("rename through the context menu", wait_device(f"{base}/Uji Ganti", 10) and not device_exists(f"{base}/Uji Folder"), "")

        # Compress the project folder in all three formats, then compare every byte on this machine.
        if not in_fixture():
            raise RuntimeError("left the fixture folder; stopping")
        source = tree(f"{base}/proj")
        for label, ext in (("ZIP", "zip"), ("TAR", "tar"), ("TAR.GZ", "tar.gz")):
            tap_label(r"^proj$", long=True, scroll=True)
            tap_label(r"^Kompres(, (membuka submenu|opens a submenu))?$")
            tap_label(rf"^{re.escape(label)}$")
            time.sleep(0.8)
            tap_label(r"^Kompres$")
            made = wait_device(f"{base}/proj.{ext}", 60)
            local = os.path.join(args.out, f"proj.{ext}")
            if made:
                time.sleep(1.5)
                adb("pull", f"{base}/proj.{ext}", local, check=False)
            content = {}
            try:
                if ext == "zip":
                    with zipfile.ZipFile(local) as z:
                        bad = z.testzip()
                        for i in z.infolist():
                            if not i.is_dir():
                                import hashlib
                                content[i.filename] = hashlib.md5(z.read(i)).hexdigest()
                        ok_crc = bad is None
                else:
                    import hashlib
                    with tarfile.open(local) as t:
                        for m in t.getmembers():
                            if m.isfile():
                                content[m.name] = hashlib.md5(t.extractfile(m).read()).hexdigest()
                    ok_crc = True
            except Exception as e:  # noqa: BLE001
                ok_crc = False
                content = {"error": str(e)}
            expect = {f"proj/{k}": v for k, v in source.items()}
            step(f"compress the folder to {label}: every file round-trips and the folder name is kept",
                 made and ok_crc and content == expect, f"files={len(content)} expected={len(expect)}")

        # Extract the ZIP here: proj already exists, so the conflict sheet asks; Keep both gives proj (2).
        tap_label(r"^proj\.zip$", scroll=True)
        tap_label(r"^Ekstrak di sini$")
        asked = bool(ui.wait(regex=r"sudah ada$", timeout=15))
        tap_label(r"^Simpan keduanya$")
        done = wait_device(f"{base}/proj (2)", 40)
        time.sleep(1)
        step("extract ZIP here asks about the taken name and keeps both", asked and done and tree(f"{base}/proj (2)") == source,
             f"asked={asked} made={done}")
        for ext in ("tar", "tar.gz"):
            tap_label(rf"^proj\.{re.escape(ext)}$", scroll=True)
            tap_label(r"^Ekstrak di sini$")
            if ui.wait(regex=r"sudah ada$", timeout=15):
                tap_label(r"^Simpan keduanya$")
            n = 3 if ext == "tar" else 4
            done = wait_device(f"{base}/proj ({n})", 40)
            time.sleep(1)
            step(f"extract {ext.upper()} here gives an identical copy", done and tree(f"{base}/proj ({n})") == source, f"proj ({n})")

        # A hostile ZIP: escapes and the link are skipped, the honest files arrive, nothing lands outside.
        tap_label(r"^evil\.zip$", scroll=True)
        tap_label(r"^Ekstrak di sini$")
        ok_dir = wait_device(f"{base}/evil/ok/readme.txt", 40) or wait_device(f"{base}/ok/readme.txt", 5)
        outside = [p for p in (f"{INTERNAL}/Documents/escape.txt", f"{base}/escape.txt", "/storage/emulated/0/abs-escape.txt",
                               f"{base}/evil/ok/link", f"{base}/ok/link") if device_exists(p)]
        step("hostile ZIP: path escapes and the symlink are skipped, honest files extracted", ok_dir and not outside,
             f"outside={outside}")

        # Copy to the SD card through Copy and Paste; the file arrives byte for byte.
        if sd:
            tap_label(r"^proj\.zip$", long=True, scroll=True)
            tap_label(r"^Salin$")
            tap_label(r"^(Tempat|Places)$")
            time.sleep(0.8)
            sd_rows = [n for n in ui.find(regex=r"^(Kartu SD|SD card)") if n["pkg"] == PKG and n["clickable"]]
            if sd_rows:
                ui.tap(sd_rows[0])
                time.sleep(1.5)
                open_item(FIX)
                time.sleep(1.2)
                if in_fixture():
                    menu_path("Edit", "Tempel")
            ok = wait_device(f"{bases[1]}/proj.zip", 40) and md5(f"{bases[1]}/proj.zip") == md5(f"{base}/proj.zip")
            step("copy to the SD card through Copy and Paste", ok, f"sd={bases[1]}")
            # Move it back: Cut on the card, Paste on internal storage under a free name.
            tap_label(r"^proj\.zip$", long=True, scroll=True)
            tap_label(r"^Potong$")
            go_fixture()
            open_item("Uji Ganti")
            time.sleep(1.2)
            if in_fixture("Uji Ganti"):
                menu_path("Edit", "Tempel")
            moved = wait_device(f"{base}/Uji Ganti/proj.zip", 40) and wait_device(f"{bases[1]}/proj.zip", 10, gone=True)
            step("move from the SD card back to internal storage removes the source", moved, "")
            tap_label(r"^(Mundur|Back)$")
            time.sleep(1.2)
        else:
            step("SD card copy and move", False, "NOT TESTED: no SD card mounted")

        # Delete with confirmation.
        if not in_fixture():
            raise RuntimeError("left the fixture folder; stopping")
        tap_label(r"^Teks baru\.txt$", long=True, scroll=True)
        tap_label(r"^Hapus$")
        confirm = bool(ui.wait(regex=r"^Hapus .Teks baru\.txt.\?$", timeout=5))
        hits = [n for n in ui.find(label="Hapus") if n["pkg"] == PKG]
        if hits:
            ui.tap(hits[-1])
        step("delete asks first and removes the file", confirm and wait_device(f"{base}/Teks baru.txt", 15, gone=True), f"asked={confirm}")

        # Search from this folder.
        tap_label(r"^(Cari di folder ini|Search in this folder)$")
        time.sleep(0.8)
        sh("input text main")
        sh("input keyevent KEYCODE_ENTER")
        step("search finds main.c below the folder", bool(ui.wait(label="main.c", timeout=15)), "")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)

        # Properties count a folder's size.
        tap_label(r"^proj$", long=True, scroll=True)
        tap_label(r"^Properti$")
        size = ui.wait(regex=r"\d+ berkas", timeout=10)
        step("properties count a folder's files", bool(size), size[0]["text"] if size else "")
        hits = [n for n in ui.find(label="Tutup") if n["pkg"] == PKG and inside(n)]
        if hits:
            ui.tap(hits[-1])

        # Resize cycles: restore to a floating window, then drag its corner back and forth.
        tap_label(r"^Pulihkan$")
        time.sleep(1.2)
        w = fm_win()
        cycles_ok = 0
        if w:
            for i in range(args.cycles):
                x2, y2 = w["bounds"][2] - 4, w["bounds"][3] - 4
                dx, dy = (-160, -260) if i % 2 == 0 else (160, 260)
                sh(f"input swipe {x2} {y2} {x2 + dx} {y2 + dy} 250")
                time.sleep(0.3)
                if i % 5 == 4 or i == args.cycles - 1:
                    w = fm_win()
                    buttons = [n for n in ui.find(regex=r"^(Menu|Tutup|Cari di folder ini)$", refresh=False) if inside(n)]
                    if w and len(buttons) >= 3:
                        cycles_ok += 5 if i % 5 == 4 else (i % 5) + 1
                else:
                    w = w
            ui.shot("09-after-resize")
        crash = sh("logcat -d -b crash", check=False)
        step(f"{args.cycles} resize cycles keep the window, its title bar and toolbar whole", w is not None and cycles_ok >= args.cycles and PKG not in crash,
             f"checked={cycles_ok}")
        tap_label(r"^Maksimalkan$")

        results_path = os.path.join(args.out, "results.json")
        with open(results_path, "w") as f:
            json.dump(results, f, indent=2)
    finally:
        for b in bases:
            sh(f"rm -rf '{b}'", check=False)
        mode = "allow" if "allow" in appop_before else "default"
        sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE {mode}", check=False)
        restore_data(backup)
        sh("input keyevent KEYCODE_HOME", check=False)
        sh("rm -f /data/local/tmp/lx-ui.xml /data/local/tmp/lx-shot.png", check=False)
    failed = [r for r in results if r["result"] == "FAIL"]
    print(f"{len(results) - len(failed)} passed, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
