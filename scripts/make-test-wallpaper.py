#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Writes the wallpaper fixture the UI tests pick through the Android photo picker.

The fixture is a plain test card: diagonal stripes and the words "LX TEST IMAGE".
It must never be the design reference (referensi.png). A reference picked as a
wallpaper during a test stays on the device and looks like LX drawing its
desktop from a screenshot.

Usage: scripts/make-test-wallpaper.py <out.png>   (900x1600, about 30 KB)
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

W, H = 900, 1600


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else "build/test-fixtures/lx-wallpaper-test.png"
    img = Image.new("RGB", (W, H), (34, 38, 46))
    d = ImageDraw.Draw(img)
    for x in range(-H, W, 80):
        d.line([(x, 0), (x + H, H)], fill=(52, 58, 70), width=24)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 72)
    except OSError:
        font = ImageFont.load_default()
    for y, text in ((680, "LX TEST IMAGE"), (780, f"{W} x {H}")):
        box = d.textbbox((0, 0), text, font=font)
        d.text(((W - (box[2] - box[0])) // 2, y), text, fill=(200, 205, 214), font=font)
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    img.save(out, optimize=True)
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
