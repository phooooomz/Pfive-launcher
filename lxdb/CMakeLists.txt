# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
# lxdb, LX's native daemon and command line tool. What makes no system call of its own builds on the host too, for the tests.
add_library(lxdbcore STATIC
  src/dex.cpp
  src/parcel.cpp
  src/rules.cpp)
target_include_directories(lxdbcore PUBLIC src)
target_compile_features(lxdbcore PUBLIC cxx_std_20)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(lxdbcore PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wno-sign-conversion)
endif()
if(ANDROID)
  target_link_libraries(lxdbcore PUBLIC z)
else()
  find_package(ZLIB REQUIRED)
  target_link_libraries(lxdbcore PUBLIC ZLIB::ZLIB)
  return()
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(LXDB_SYSCALL src/sys_arm64.S)
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(LXDB_SYSCALL src/sys_x86_64.S)
else()
  message(FATAL_ERROR "lxdb has no system call stub for ${ANDROID_ABI}")
endif()

add_executable(lxdb
  src/android.cpp
  src/binder.cpp
  src/control.cpp
  src/embed_service.cpp
  src/main.cpp
  src/process.cpp
  ${LXDB_SYSCALL})
target_link_libraries(lxdb PRIVATE lxdbcore log)
target_compile_options(lxdb PRIVATE -Wall -Wextra -fno-exceptions -fno-rtti -fvisibility=hidden)
# 16 KB page alignment for devices that use 16 KB memory pages.
target_link_options(lxdb PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
# Android packs only lib*.so files, and keeps them uncompressed in the APK, which is what lets the linker run this one there.
set_target_properties(lxdb PROPERTIES OUTPUT_NAME "liblxdb.so" PREFIX "" SUFFIX ""
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
