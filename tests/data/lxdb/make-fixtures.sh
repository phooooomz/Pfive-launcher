#!/usr/bin/env bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
# Rebuilds the jars lxdb_dex_test reads: one keeps classes.dex stored, as Android's framework.jar does, one deflated.
set -euo pipefail
cd "$(dirname "$0")"
d8=$(ls "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/d8 | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac --release 8 -nowarn -d "$work/classes" src/lx/fixture/IThing.java
"$d8" --min-api 26 --output "$work" "$work"/classes/lx/fixture/*.class
(cd "$work" && zip -q -0 stored.jar classes.dex && zip -q -9 deflated.jar classes.dex)
cp "$work/stored.jar" fixture-stored.jar
cp "$work/deflated.jar" fixture-deflated.jar
