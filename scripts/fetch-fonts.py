#!/usr/bin/env python3
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
"""Builds the 25 bundled UI fonts in app/src/main/res/font from pinned sources.

Families whose licence has no Reserved Font Name are cut to two static weights
(400 for text, 500 for emphasis) from their variable source and subset to the
Latin scripts LX's own text uses; any other script in an app name falls back
to the system fonts. Lato, Source Sans 3 and IBM Plex Sans carry a Reserved
Font Name, so their official static files are copied byte for byte.

Each family's licence goes to third_party/fonts/<family>/ and, for the APK,
to app/src/main/assets/licenses/fonts/. The SHA-256 of every download is
written to third_party/fonts/SOURCES.txt; a later run refuses a download whose
hash differs from the one recorded there.

Needs Python 3 with fontTools. Usage: scripts/fetch-fonts.py [--cache DIR]
"""
import argparse
import hashlib
import io
import os
import shutil
import sys
import urllib.request
import zipfile

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res", "font")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "licenses", "fonts")
THIRD = os.path.join(ROOT, "third_party", "fonts")

GF = "https://raw.githubusercontent.com/google/fonts/e7db6ca603fd4862010383ff17ffd6cc4ffac1c0/ofl"
SS3 = "https://github.com/adobe-fonts/source-sans/releases/download/3.052R/TTF-source-sans-3.052R.zip"
SS3_LICENSE = "https://raw.githubusercontent.com/adobe-fonts/source-sans/3.052R/LICENSE.md"
PLEX = "https://github.com/IBM/plex/releases/download/%40ibm/plex-sans%401.1.0/ibm-plex-sans.zip"

# Basic Latin to Latin Extended-B, spacing and combining marks, Latin Extended
# Additional, general punctuation, currency, letterlike symbols, arrows, a few
# math and geometric shapes, and the replacement character.
UNICODES = ("U+0000-024F,U+02B0-036F,U+1E00-1EFF,U+2000-206F,U+20A0-20CF,U+2100-214F,"
            "U+2190-21FF,U+2212,U+2215,U+2219,U+221E,U+2248,U+2260,U+2264-2265,U+25A0-25FF,U+FEFF,U+FFFD")

# id: (source file in google/fonts, extra axis pins, weights)
VARIABLE = {
    "roboto": ("roboto/Roboto%5Bwdth,wght%5D.ttf", {"wdth": 100}, (400, 500)),
    "inter": ("inter/Inter%5Bopsz,wght%5D.ttf", {"opsz": 14}, (400, 500)),
    "notosans": ("notosans/NotoSans%5Bwdth,wght%5D.ttf", {"wdth": 100}, (400, 500)),
    "notoserif": ("notoserif/NotoSerif%5Bwdth,wght%5D.ttf", {"wdth": 100}, (400, 500)),
    "notosansmono": ("notosansmono/NotoSansMono%5Bwdth,wght%5D.ttf", {"wdth": 100}, (400, 500)),
    "nunitosans": ("nunitosans/NunitoSans%5BYTLC,opsz,wdth,wght%5D.ttf", {"YTLC": 500, "opsz": 12, "wdth": 100}, (400, 500)),
    "jetbrainsmono": ("jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf", {}, (400, 500)),
    # Added 2026-11-10. None of these reserves its own name, so each is cut and subset like the ones above.
    "plusjakartasans": ("plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf", {}, (400, 500)),
    "manrope": ("manrope/Manrope%5Bwght%5D.ttf", {}, (400, 500)),
    "montserrat": ("montserrat/Montserrat%5Bwght%5D.ttf", {}, (400, 500)),
    "opensans": ("opensans/OpenSans%5Bwdth,wght%5D.ttf", {"wdth": 100}, (400, 500)),
    "worksans": ("worksans/WorkSans%5Bwght%5D.ttf", {}, (400, 500)),
    "dmsans": ("dmsans/DMSans%5Bopsz,wght%5D.ttf", {"opsz": 14}, (400, 500)),
    "rubik": ("rubik/Rubik%5Bwght%5D.ttf", {}, (400, 500)),
    "figtree": ("figtree/Figtree%5Bwght%5D.ttf", {}, (400, 500)),
    "lexend": ("lexend/Lexend%5Bwght%5D.ttf", {}, (400, 500)),
    "atkinsonnext": ("atkinsonhyperlegiblenext/AtkinsonHyperlegibleNext%5Bwght%5D.ttf", {}, (400, 500)),
    "nunito": ("nunito/Nunito%5Bwght%5D.ttf", {}, (400, 500)),
    "redhattext": ("redhattext/RedHatText%5Bwght%5D.ttf", {}, (400, 500)),
    "exo2": ("exo2/Exo2%5Bwght%5D.ttf", {}, (400, 500)),
    "literata": ("literata/Literata%5Bopsz,wght%5D.ttf", {"opsz": 12}, (400, 500)),
}

# Families published only as static files and without a Reserved Font Name: subset, not instanced.
STATIC = {
    "poppins": ("poppins", ("Poppins-Regular.ttf", "Poppins-Medium.ttf")),
}

def recorded_hashes():
    path = os.path.join(THIRD, "SOURCES.txt")
    if not os.path.exists(path):
        return {}
    with open(path) as f:
        rows = [line.rstrip("\n").split("\t") for line in f if "\t" in line]
    return {url: digest for _, url, digest in rows}


EXPECTED = recorded_hashes()


def fetch(url, cache):
    name = hashlib.sha256(url.encode()).hexdigest()[:16] + "-" + url.rsplit("/", 1)[-1].replace("%", "_")
    path = os.path.join(cache, name)
    if not os.path.exists(path):
        print("download", url)
        with urllib.request.urlopen(url, timeout=120) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(path + ".part", path)
    with open(path, "rb") as f:
        data = f.read()
    digest = hashlib.sha256(data).hexdigest()
    if url in EXPECTED and EXPECTED[url] != digest:
        sys.exit(f"{url}: SHA-256 {digest} differs from the recorded {EXPECTED[url]}")
    return data, digest


def static_cut(data, pins, weight, out):
    font = TTFont(io.BytesIO(data))
    axes = {a.axisTag for a in font["fvar"].axes}
    location = {k: v for k, v in pins.items() if k in axes}
    location["wght"] = weight
    inst = instancer.instantiateVariableFont(font, location, updateFontNames=False)
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.hinting = False
    opts.notdef_outline = True
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=subset.parse_unicodes(UNICODES))
    sub.subset(inst)
    inst.save(out)


def static_subset(data, out):
    font = TTFont(io.BytesIO(data))
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.hinting = False
    opts.notdef_outline = True
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=subset.parse_unicodes(UNICODES))
    sub.subset(font)
    font.save(out)


def write_licence(family, text):
    for folder in (os.path.join(THIRD, family), ASSETS):
        os.makedirs(folder, exist_ok=True)
    with open(os.path.join(THIRD, family, "OFL.txt"), "wb") as f:
        f.write(text)
    with open(os.path.join(ASSETS, family + ".txt"), "wb") as f:
        f.write(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.path.join(ROOT, "build", "font-src"))
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    os.makedirs(RES, exist_ok=True)
    sources = []

    for family, (src, pins, weights) in VARIABLE.items():
        data, digest = fetch(f"{GF}/{src}", args.cache)
        sources.append((family, f"{GF}/{src}", digest))
        for w, role in zip(weights, ("regular", "medium")):
            static_cut(data, pins, w, os.path.join(RES, f"{family}_{role}.ttf"))
        lic, _ = fetch(f"{GF}/{src.split('/')[0]}/OFL.txt", args.cache)
        write_licence(family, lic)

    for family, (folder, names) in STATIC.items():
        for role, name in zip(("regular", "medium"), names):
            data, digest = fetch(f"{GF}/{folder}/{name}", args.cache)
            sources.append((family, f"{GF}/{folder}/{name}", digest))
            static_subset(data, os.path.join(RES, f"{family}_{role}.ttf"))
        write_licence(family, fetch(f"{GF}/{folder}/OFL.txt", args.cache)[0])

    # Reserved Font Name families: official static files, unmodified.
    for role, name in (("regular", "Lato-Regular.ttf"), ("medium", "Lato-Medium.ttf")):
        data, digest = fetch(f"{GF}/lato/{name}", args.cache)
        sources.append(("lato", f"{GF}/lato/{name}", digest))
        with open(os.path.join(RES, f"lato_{role}.ttf"), "wb") as f:
            f.write(data)
    write_licence("lato", fetch(f"{GF}/lato/OFL.txt", args.cache)[0])

    data, digest = fetch(SS3, args.cache)
    sources.append(("sourcesans3", SS3, digest))
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for role, name in (("regular", "TTF/SourceSans3-Regular.ttf"), ("medium", "TTF/SourceSans3-Medium.ttf")):
            with open(os.path.join(RES, f"sourcesans3_{role}.ttf"), "wb") as f:
                f.write(z.read(name))
    write_licence("sourcesans3", fetch(SS3_LICENSE, args.cache)[0])

    data, digest = fetch(PLEX, args.cache)
    sources.append(("plexsans", PLEX, digest))
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        base = "ibm-plex-sans/fonts/complete/ttf/"
        for role, name in (("regular", "IBMPlexSans-Regular.ttf"), ("medium", "IBMPlexSans-Medium.ttf")):
            with open(os.path.join(RES, f"plexsans_{role}.ttf"), "wb") as f:
                f.write(z.read(base + name))
        write_licence("plexsans", z.read("ibm-plex-sans/LICENSE.txt"))

    with open(os.path.join(THIRD, "SOURCES.txt"), "w") as f:
        f.write("Font sources used by scripts/fetch-fonts.py (family, URL, SHA-256 of the download)\n\n")
        for family, url, digest in sources:
            f.write(f"{family}\t{url}\t{digest}\n")
    total = sum(os.path.getsize(os.path.join(RES, n)) for n in os.listdir(RES))
    print(f"{len(os.listdir(RES))} font files, {total / 1024:.0f} KB in {RES}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
