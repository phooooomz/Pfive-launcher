#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Builds the built-in photo wallpapers in app/src/main/assets/wallpapers as WebP from the 1440x3200 sources in
gambar/wallpaper/, and prints each one's size and PSNR against its source. Each source file carries the wallpaper's
English name from the picker (Neon Circuit is neon-circuit.jpg).

WebP at quality 90 keeps every wallpaper above 40 dB PSNR (34 dB in the worst 64x64 block) at a fifth of the size of
the JPEG q95 and PNG files it replaces. Android reads WebP from API 14, so every supported version decodes it.

Usage: scripts/make-wallpaper-webp.py [--quality 90]
"""
import argparse
import math
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_DIR = os.path.join(ROOT, "gambar", "wallpaper")
SOURCES = {
    "neon_circuit": "neon-circuit.jpg",
    "violet_blade": "violet-blade.jpg",
    "violet_grid": "violet-grid.jpg",
    "orange_core": "orange-core.jpg",
    "ember_shards": "ember-shards.png",
    "prism_shards": "prism-shards.png",
    "pc_rig": "pc-rig.png",
}


def psnr(a, b):
    pa, pb = a.tobytes(), b.tobytes()
    total = sum((x - y) * (x - y) for x, y in zip(pa, pb))
    mse = total / len(pa)
    return float("inf") if mse == 0 else 10 * math.log10(255 * 255 / mse)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--quality", type=int, default=90)
    args = parser.parse_args()
    out_dir = os.path.join(ROOT, "app", "src", "main", "assets", "wallpapers")
    os.makedirs(out_dir, exist_ok=True)
    for name, source in SOURCES.items():
        src = os.path.join(SOURCE_DIR, source)
        if not os.path.isfile(src):
            sys.exit(f"missing source {src}")
        image = Image.open(src).convert("RGB")
        dst = os.path.join(out_dir, name + ".webp")
        image.save(dst, "WEBP", quality=args.quality, method=6)
        back = Image.open(dst).convert("RGB")
        print(f"{name:14s} {os.path.getsize(src):>9d} -> {os.path.getsize(dst):>8d} bytes  PSNR {psnr(image, back):5.2f} dB")


if __name__ == "__main__":
    main()
