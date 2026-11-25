# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
cmake_minimum_required(VERSION 3.22.1)
project(lxnative LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Every object, the core's too, keeps its symbols to itself; only the JNIEXPORT functions are seen from Java.
# That lets --gc-sections drop core code no JNI function reaches.
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(LX_CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../core)
add_subdirectory(${LX_CORE_DIR} ${CMAKE_BINARY_DIR}/lxcore)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../lxdb ${CMAKE_BINARY_DIR}/lxdb)

# VHDP runs a Linux rootfs without root, for Tedit's terminal and tasks. Its command line tool (phdp) and its ELF
# loader are executables; Android packs only lib*.so files, so they get library names, as lxdb does. The loader has
# to sit in the app's native library folder, the one place Android lets an app exec from.
set(VHDP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(VHDP_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(VHDP_WERROR OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../vhdp ${CMAKE_BINARY_DIR}/vhdp)
set_target_properties(phdp PROPERTIES OUTPUT_NAME "libphdp.so" PREFIX "" SUFFIX ""
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_link_options(phdp PRIVATE -Wl,-z,max-page-size=16384)
set_target_properties(vhdp_loader PROPERTIES OUTPUT_NAME "libvhdp-loader.so" PREFIX "" SUFFIX ""
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

# XZ Embedded (0BSD) decodes the .tar.xz root filesystem Tedit sets up.
set(LX_XZ_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/xz-embedded)
add_library(lxxz STATIC
  ${LX_XZ_DIR}/src/xz_crc32.c
  ${LX_XZ_DIR}/src/xz_crc64.c
  ${LX_XZ_DIR}/src/xz_dec_lzma2.c
  ${LX_XZ_DIR}/src/xz_dec_stream.c
  ${LX_XZ_DIR}/src/xz_dec_bcj.c)
target_include_directories(lxxz PUBLIC ${LX_XZ_DIR}/include ${LX_XZ_DIR}/src)
# The archive's check is CRC64 by default; BCJ filters are enabled for archives packed with them.
target_compile_definitions(lxxz PUBLIC XZ_USE_CRC64 XZ_DEC_ANY_CHECK XZ_DEC_X86 XZ_DEC_ARM XZ_DEC_ARMTHUMB XZ_DEC_ARM64)
target_compile_options(lxxz PRIVATE -O2)

add_library(lxnative SHARED jni_bridge.cpp archive_jni.cpp xz_jni.cpp)
target_link_libraries(lxnative PRIVATE lxcore lxxz android jnigraphics log)
target_compile_options(lxnative PRIVATE -Wall -Wextra -fvisibility=hidden)
# 16 KB page alignment for devices that use 16 KB memory pages.
# Static libraries (libc++, the core) export nothing; identical functions are folded where that is safe.
target_link_options(lxnative PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL -Wl,--icf=safe)

# dracxterm's terminal engine (GPL-3.0, see third_party/dracxterm/README.lx.md): the pseudo terminal, the VT parser
# and the screen buffer behind Tedit's terminal, built from dracxterm 1.1.3's sources as they are, as libxterm.so,
# the name dracxterm's NativeTerminal loads.
set(LX_DX_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dracxterm/cpp)
add_library(xterm SHARED
  ${LX_DX_DIR}/native-lib.cpp
  ${LX_DX_DIR}/engine/pty/Pty.cpp
  ${LX_DX_DIR}/engine/parser/AnsiParser.cpp
  ${LX_DX_DIR}/engine/screen/ScreenBuffer.cpp
  ${LX_DX_DIR}/engine/charset/Unicode.cpp
  ${LX_DX_DIR}/engine/terminal/Terminal.cpp
  ${LX_DX_DIR}/engine/session/Session.cpp)
set_target_properties(xterm PROPERTIES CXX_STANDARD 17)
target_include_directories(xterm PRIVATE ${LX_DX_DIR} ${LX_DX_DIR}/engine ${LX_DX_DIR}/include)
target_link_libraries(xterm PRIVATE android log)
target_link_options(xterm PRIVATE -Wl,-z,max-page-size=16384)
