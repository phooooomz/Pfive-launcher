#!/usr/bin/env bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
#
# Prepares the folder for Tedit's `-e only tmparity` check, which compares the token kind Tedit's TextMate tokenizer
# gives every character with what vscode-textmate (VS Code's own tokenizer) gives, using the same grammars, on the
# device's regex engine.
#
# Needs VS Code installed (its grammars and vscode-textmate come from there; neither is stored in this repository),
# node and python3.
#
# usage: scripts/tmparity/prepare.sh <out dir> <corpus.tsv>
#   corpus.tsv has one line per file: <TextMate scope><TAB><path>, for example
#   source.python	/usr/lib/python3.12/argparse.py
# then, with LX and its test APK installed:
#   adb push <out dir> /sdcard/Download/tmparity
#   adb shell appops set --uid com.lxlauncher MANAGE_EXTERNAL_STORAGE allow
#   adb shell am instrument -w -e only tmparity com.lxlauncher.test/.SelfTestInstrumentation
# and afterwards take the access back and remove the copies of the corpus files:
#   adb shell appops set --uid com.lxlauncher MANAGE_EXTERNAL_STORAGE default
#   adb shell rm -rf /sdcard/Download/tmparity
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:?out dir}
corpus=${2:?corpus.tsv}
app=${VSCODE_APP:-/usr/share/code/resources/app}
[ -f "$app/node_modules.asar" ] || { echo "VS Code not found at $app (set VSCODE_APP)" >&2; exit 1; }

rm -rf "$out"
mkdir -p "$out/files" "$out/ext"
mods=$(mktemp -d)
trap 'rm -rf "$mods"' EXIT
python3 -I "$here/asar.py" "$app/node_modules.asar" "$mods" vscode-textmate vscode-oniguruma
cp "$app/node_modules.asar.unpacked/vscode-oniguruma/release/onig.wasm" "$mods/vscode-oniguruma/release/"

for dir in "$app"/extensions/*/; do
  name=$(basename "$dir")
  [ -d "$dir/syntaxes" ] || continue
  mkdir -p "$out/ext/$name"
  cp -r "$dir/syntaxes" "$out/ext/$name/"
  [ -f "$dir/package.json" ] && cp "$dir/package.json" "$out/ext/$name/"
done

i=0
: > "$out/corpus.tsv"
while IFS=$'\t' read -r scope path; do
  [ -n "$scope" ] && [ -f "$path" ] || continue
  cp "$path" "$out/files/$i"
  printf '%s\t%s\t%s\n' "$scope" "$i" "$(basename "$path")" >> "$out/corpus.tsv"
  i=$((i + 1))
done < "$corpus"

node "$here/ref.js" "$mods" "$out/ext" "$out"
echo "ready: $i files in $out"
echo "next: adb push $out /sdcard/Download/tmparity (see the top of this script for the rest)"
