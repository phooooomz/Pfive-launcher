#!/usr/bin/env python3
"""Small adb + uiautomator helper for the maintenance device checks on the Infinix.

  ui.py tap "<regex>"        tap the first node whose text or content-desc matches
  ui.py find "<regex>"       print matching nodes with their bounds
  ui.py dump                 print every node with text or content-desc
"""
import os
import re
import subprocess
import sys
import time


def _serial():
    serial = os.environ.get("ANDROID_SERIAL", "")
    if not serial:
        try:
            with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".serial")) as f:
                serial = f.read().strip()
        except OSError:
            pass
    if not serial:
        sys.exit("set ANDROID_SERIAL or put the phone's serial in scripts/device/.serial")
    return serial


SERIAL = _serial()


def adb(*args, check=True):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, check=check).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/data/local/tmp/lx-ui.xml", check=False)
    xml = adb("shell", "cat", "/data/local/tmp/lx-ui.xml", check=False)
    out = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n)
        d = re.search(r' content-desc="([^"]*)"', n)
        text = t.group(1) if t else ""
        desc = d.group(1) if d else ""
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        out.append((text, desc, (x1, y1, x2, y2)))
    return out


def find(pattern):
    rx = re.compile(pattern, re.I)
    return [n for n in nodes() if (n[0] and rx.search(n[0])) or (n[1] and rx.search(n[1]))]


def tap(pattern, retries=3):
    for _ in range(retries):
        hits = find(pattern)
        if hits:
            x1, y1, x2, y2 = hits[0][2]
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return hits[0]
        time.sleep(1)
    raise SystemExit(f"no node matches {pattern!r}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "tap":
        print(tap(sys.argv[2]))
    elif cmd == "find":
        for n in find(sys.argv[2]):
            print(n)
    elif cmd == "dump":
        for n in nodes():
            if n[0] or n[1]:
                print(n)
