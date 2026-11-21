#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Lists an archive with Python's own zipfile or tarfile, for the interop test.

Usage: verify_interop.py zip|tar|tgz ARCHIVE

Prints one line per entry, "<kind> <size> <crc32> <name>", where kind is f
(file), d (folder) or o (anything else) and names lose a trailing slash. Exits
non-zero when Python finds the archive damaged (bad CRC, short data, bad header).
"""
import sys
import tarfile
import zipfile
import zlib


def crc_of(stream):
    crc = 0
    size = 0
    while True:
        chunk = stream.read(1 << 16)
        if not chunk:
            return size, crc
        size += len(chunk)
        crc = zlib.crc32(chunk, crc)


def list_zip(path):
    with zipfile.ZipFile(path) as zf:
        bad = zf.testzip()
        if bad is not None:
            raise SystemExit("CRC error in " + bad)
        for info in zf.infolist():
            if info.is_dir():
                print("d 0 00000000 " + info.filename.rstrip("/"))
                continue
            with zf.open(info) as f:
                size, crc = crc_of(f)
            print("f %d %08x %s" % (size, crc, info.filename))


def list_tar(path, mode):
    with tarfile.open(path, mode) as tf:
        for m in tf:
            if m.isdir():
                print("d 0 00000000 " + m.name.rstrip("/"))
            elif m.isreg():
                size, crc = crc_of(tf.extractfile(m))
                if size != m.size:
                    raise SystemExit("short data in " + m.name)
                print("f %d %08x %s" % (size, crc, m.name))
            else:
                print("o 0 00000000 " + m.name)


def main():
    kind, path = sys.argv[1], sys.argv[2]
    sys.stdout.reconfigure(encoding="utf-8")
    if kind == "zip":
        list_zip(path)
    elif kind == "tar":
        list_tar(path, "r:")
    else:
        list_tar(path, "r:gz")


if __name__ == "__main__":
    main()
