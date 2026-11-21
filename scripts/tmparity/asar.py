#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Copies top-level packages out of an Electron .asar archive, such as VS Code's node_modules.asar.

usage: asar.py <archive.asar> <out dir> <package> [<package> ...]

An .asar starts with two pickles: 4 bytes saying the first is 4 bytes long, then the header pickle's size, then the
header itself (a size and a JSON tree). File offsets in the tree count from the end of the header pickle. Files the
tree marks "unpacked" live next to the archive in <archive>.unpacked and are not copied here.
"""
import json
import os
import struct
import sys


def main() -> None:
    archive, out, wanted = sys.argv[1], sys.argv[2], set(sys.argv[3:])
    with open(archive, 'rb') as f:
        _, header_pickle, _, json_size = struct.unpack('<IIII', f.read(16))
        tree = json.loads(f.read(json_size))
        base = 8 + header_pickle

        def walk(node: dict, path: list) -> None:
            for name, child in node.get('files', {}).items():
                p = path + [name]
                if p[0] not in wanted:
                    continue
                if 'files' in child:
                    walk(child, p)
                elif not child.get('unpacked'):
                    f.seek(base + int(child['offset']))
                    data = f.read(child['size'])
                    dest = os.path.join(out, *p)
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    with open(dest, 'wb') as w:
                        w.write(data)

        walk(tree, [])


if __name__ == '__main__':
    main()
