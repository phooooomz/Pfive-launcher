# XZ Embedded in LX

Source: https://github.com/tukaani-project/xz-embedded, commit ae63ae3a36ed01724674e8f3d750dc47bf125410
(30 December 2024). Licence: 0BSD (see COPYING), no attribution required.

Copied unchanged: `linux/include/linux/xz.h` to `include/`, and `userspace/xz_config.h` with the decoder sources from
`linux/lib/xz/` to `src/`. LX uses it to unpack the `.tar.xz` Linux root filesystem that Tedit sets up; the decoder is
built into `liblxnative.so` (see `app/src/main/cpp/CMakeLists.txt` and `xz_jni.cpp`).
