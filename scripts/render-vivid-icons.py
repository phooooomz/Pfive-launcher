#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Renders the Vivid Dark icons LX uses into app/src/main/res/drawable-nodpi.

The icons come from Vivid Plasma Themes by L4ki (GPL-3.0), pinned to one
commit. The SVG sources are kept in third_party/vivid-icons/svg, which is the
preferred form for modifying them; the WebP files in the APK are renderings of
those sources. Only generic folder, file-type and tool icons are taken: no
brand logos and no icons of named KDE applications. The theme's web browser,
file manager and PDF icons were left out after review: they resemble the
Firefox logo, the Dolphin mascot and the Acrobat logo.

Rendering uses a headless Chromium, because the icons rely on gradients, clip
paths and blur filters that Android's VectorDrawable cannot express.

Usage: scripts/render-vivid-icons.py --chrome /path/to/chrome-headless-shell
"""
import argparse
import hashlib
import os
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMMIT = "598e24d41b1a040620e81e971020e32fa9480944"
BASE = ("https://raw.githubusercontent.com/L4ki/Vivid-Plasma-Themes/" + COMMIT +
        "/Vivid%20Icons%20Themes/Vivid-Dark-Icons/")
LICENSE_URL = "https://raw.githubusercontent.com/L4ki/Vivid-Plasma-Themes/" + COMMIT + "/LICENSE"
SVG_DIR = os.path.join(ROOT, "third_party", "vivid-icons", "svg")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
SIZE = 192  # 48 dp at xxxhdpi; Android scales down for smaller densities

# drawable name: path inside the icon theme
ICONS = {
    "vivid_home": "places/64/user-home.svg",
    "vivid_file_image": "mimetypes/64/image-x-generic.svg",
    "vivid_app_settings": "apps/48/preferences-system.svg",
    "vivid_app_monitor": "apps/48/utilities-system-monitor.svg",
    "vivid_app_notes": "apps/48/accessories-text-editor.svg",
    "vivid_app_games": "apps/48/applications-games.svg",
}


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def render(chrome, svg_path, png_path):
    with tempfile.TemporaryDirectory() as tmp:
        page = os.path.join(tmp, "icon.html")
        with open(page, "w") as f:
            f.write('<html><body style="margin:0;background:transparent">'
                    f'<img src="file://{svg_path}" style="width:{SIZE}px;height:{SIZE}px;display:block">'
                    "</body></html>")
        subprocess.run([chrome, "--headless", "--disable-gpu", "--hide-scrollbars",
                        "--default-background-color=00000000", f"--window-size={SIZE},{SIZE}",
                        f"--screenshot={png_path}", "file://" + page],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chrome", required=True, help="a Chromium or chrome-headless-shell binary")
    args = ap.parse_args()
    os.makedirs(SVG_DIR, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for name, path in ICONS.items():
        url = BASE + urllib.parse.quote(path)
        data = fetch(url)
        svg = os.path.join(SVG_DIR, path.replace("/", "_"))
        with open(svg, "wb") as f:
            f.write(data)
        rows.append(f"{name}\t{path}\t{hashlib.sha256(data).hexdigest()}")
        with tempfile.NamedTemporaryFile(suffix=".png") as png:
            render(args.chrome, svg, png.name)
            Image.open(png.name).convert("RGBA").save(os.path.join(OUT, name + ".webp"), "WEBP", lossless=True,
                                                       quality=100, method=6)
    with open(os.path.join(os.path.dirname(SVG_DIR), "LICENSE"), "wb") as f:
        f.write(fetch(LICENSE_URL))
    with open(os.path.join(os.path.dirname(SVG_DIR), "SOURCES.txt"), "w") as f:
        f.write("Vivid Plasma Themes, commit " + COMMIT + "\n")
        f.write("drawable\tpath in Vivid Icons Themes/Vivid-Dark-Icons\tSHA-256 of the SVG\n\n")
        f.write("\n".join(rows) + "\n")
    total = sum(os.path.getsize(os.path.join(OUT, n + ".webp")) for n in ICONS)
    print(f"{len(ICONS)} icons, {total / 1024:.0f} KB in {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
