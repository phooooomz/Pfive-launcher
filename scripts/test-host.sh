#!/usr/bin/env bash
# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
# Builds the native core on the host and runs every C++ test, once plain and
# once under AddressSanitizer + UBSan. Prints the benchmark at the end.
set -euo pipefail
cd "$(dirname "$0")/.."
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Debug >/dev/null
cmake --build build/host
./build/host/lx_core_tests
cmake -S . -B build/asan -G Ninja -DCMAKE_BUILD_TYPE=Debug -DLX_SANITIZE=ON >/dev/null
cmake --build build/asan
./build/asan/lx_core_tests
cmake -S . -B build/rel -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build build/rel --target lx_core_bench
./build/rel/lx_core_bench
