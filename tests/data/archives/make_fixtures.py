#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Writes the archive fixtures read by tests/unit/archive_test.cpp.

The archives come from Python's zipfile, tarfile and gzip so the C++ reader is
checked against an independent writer. Output is deterministic; run it from
anywhere and commit the results:

    python3 -I tests/data/archives/make_fixtures.py
"""
import gzip
import io
import os
import random
import stat
import tarfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DATE = (2024, 5, 17, 10, 30, 42)
MTIME = 1715941842  # 2024-05-17 10:30:42 UTC

LONG_DIR = "/".join(["segment-%02d-%s" % (i, "x" * 40) for i in range(6)])  # about 300 bytes
LONG_NAME = "n" * 120 + ".txt"
LONG_LINK = "../" + "t" * 150


def payload(seed, size):
    rnd = random.Random(seed)
    return bytes(rnd.getrandbits(8) for _ in range(size))


def write(name, data):
    with open(os.path.join(HERE, name), "wb") as f:
        f.write(data)


def zip_basic(compression):
    buf = io.BytesIO()
    entries = [
        ("docs/", b""),
        ("docs/empty/", b""),
        ("docs/readme.txt", b"hello from python\n" * 50),
        ("zero.bin", b""),
        ("unicode/héllo wörld ✓.txt", "über".encode()),
        ("big/random.bin", payload(7, 12000)),
    ]
    with zipfile.ZipFile(buf, "w", compression) as zf:
        for name, data in entries:
            zf.writestr(zipfile.ZipInfo(name, DATE), data, compress_type=compression)
    return buf.getvalue()


def zip_forced64():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        info = zipfile.ZipInfo("forced64.txt", DATE)
        info.compress_type = zipfile.ZIP_DEFLATED
        with zf.open(info, "w", force_zip64=True) as f:
            f.write(b"zip64 local header\n" * 20)
    return buf.getvalue()


def zip_cp437():
    # zipfile always writes non-ASCII names as UTF-8, so the CP437 bytes are
    # patched in afterwards over placeholders of the same length. Names carry no CRC.
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as zf:
        zf.writestr(zipfile.ZipInfo("cafX.txt", DATE), b"cp437 one")
        zf.writestr(zipfile.ZipInfo("YZber.txt", DATE), b"cp437 two")
    data = buf.getvalue()
    data = data.replace(b"cafX.txt", b"caf\x82.txt").replace(b"YZber.txt", b"\x9a\x81ber.txt")
    return data


def zip_unix():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        target = zipfile.ZipInfo("target.txt", DATE)
        target.create_system = 3
        target.external_attr = (stat.S_IFREG | 0o640) << 16
        zf.writestr(target, b"the target\n")
        link = zipfile.ZipInfo("link", DATE)
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        zf.writestr(link, b"target.txt")
        fifo = zipfile.ZipInfo("fifo", DATE)
        fifo.create_system = 3
        fifo.external_attr = (stat.S_IFIFO | 0o644) << 16
        zf.writestr(fifo, b"")
    return buf.getvalue()


def tar_bytes(fmt, members):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=fmt) as tf:
        for info, data in members:
            tf.addfile(info, io.BytesIO(data) if data is not None else None)
    return buf.getvalue()


def member(name, kind=tarfile.REGTYPE, data=None, mtime=MTIME, **extra):
    info = tarfile.TarInfo(name)
    info.type = kind
    info.mtime = mtime
    info.mode = 0o755 if kind == tarfile.DIRTYPE else 0o644
    info.size = len(data) if data is not None else 0
    for k, v in extra.items():
        setattr(info, k, v)
    return info, data


def gnu_tar():
    return tar_bytes(tarfile.GNU_FORMAT, [
        member("dir", tarfile.DIRTYPE),
        member("dir/" + LONG_NAME, data=b"gnu long name\n"),
        member("dir/longlink", tarfile.SYMTYPE, linkname=LONG_LINK),
        member("dir/hard", tarfile.LNKTYPE, linkname="dir/" + LONG_NAME),
        member("dir/old.txt", data=b"before 1970\n", mtime=-86400),
        member("dev/null", tarfile.CHRTYPE, devmajor=1, devminor=3),
        member("dev/sda", tarfile.BLKTYPE, devmajor=8, devminor=0),
        member("dev/pipe", tarfile.FIFOTYPE),
        member("after.txt", data=b"after the devices\n"),
    ])


def pax_tar():
    return tar_bytes(tarfile.PAX_FORMAT, [
        member(LONG_DIR, tarfile.DIRTYPE),
        member(LONG_DIR + "/deep.txt", data=b"pax long path\n"),
        member("unicode/日本語 é.txt", data="ok".encode()),
        member("fraction.txt", data=b"half a second\n", mtime=1700000000.5),
        member("symlink", tarfile.SYMTYPE, linkname=LONG_LINK),
    ])


def ustar_tgz():
    tar = tar_bytes(tarfile.USTAR_FORMAT, [
        member("a.txt", data=b"first member\n" * 100),
        member("b.txt", data=payload(3, 3000)),
        member("c/", tarfile.DIRTYPE),
        member("c/d.txt", data=b"second member\n"),
    ])
    return tar


def main():
    write("py_deflate.zip", zip_basic(zipfile.ZIP_DEFLATED))
    write("py_stored.zip", zip_basic(zipfile.ZIP_STORED))
    write("py_forced64.zip", zip_forced64())
    write("cp437.zip", zip_cp437())
    write("unix.zip", zip_unix())
    write("gnu.tar", gnu_tar())
    write("pax.tar", pax_tar())
    tar = ustar_tgz()
    write("ustar.tar.gz", gzip.compress(tar, mtime=0))
    # Two gzip members, split on a block boundary inside the second file.
    split = 3 * 512
    write("concat.tar.gz", gzip.compress(tar[:split], mtime=0) + gzip.compress(tar[split:], mtime=0))


if __name__ == "__main__":
    main()
