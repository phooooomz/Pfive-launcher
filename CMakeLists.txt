# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
# Host build of the native core and its tests. The Android build uses
# app/src/main/cpp/CMakeLists.txt, which includes core/CMakeLists.txt.
cmake_minimum_required(VERSION 3.22)
project(lx_launcher_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(LX_SANITIZE "Build with AddressSanitizer and UBSan" OFF)
if(LX_SANITIZE)
  add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
  add_link_options(-fsanitize=address,undefined)
endif()

add_subdirectory(core)
add_subdirectory(lxdb)

include(FetchContent)
# Pinned release archive. The hash was recorded on first download (the
# project publishes no checksum file); see docs/research/dependencies.md.
FetchContent_Declare(
  googletest
  URL https://github.com/google/googletest/releases/download/v1.15.2/googletest-1.15.2.tar.gz
  URL_HASH SHA256=7b42b4d6ed48810c5362c265a17faebe90dc2373c885e5216439d37927f02926
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()
add_executable(lx_core_tests
  tests/unit/json_test.cpp
  tests/unit/window_manager_test.cpp
  tests/unit/desktop_test.cpp
  tests/unit/settings_test.cpp
  tests/unit/persistence_test.cpp
  tests/unit/search_test.cpp
  tests/unit/render_test.cpp
  tests/unit/engine_test.cpp
  tests/unit/archive_path_test.cpp
  tests/unit/archive_test.cpp
  tests/unit/archive_safety_test.cpp
  tests/unit/archive_jni_test.cpp
  tests/unit/lxdb_parcel_test.cpp
  tests/unit/lxdb_dex_test.cpp
  tests/unit/lxdb_rules_test.cpp
  tests/security/fuzz_test.cpp
  tests/security/archive_fuzz_test.cpp
  tests/regression/regression_test.cpp)
target_link_libraries(lx_core_tests PRIVATE lxcore lxdbcore GTest::gtest_main)
# Archive fixtures and the interop script live in tests/data.
target_compile_definitions(lx_core_tests PRIVATE LX_TEST_DATA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests/data")
include(GoogleTest)
gtest_discover_tests(lx_core_tests)

# The archive JNI glue built for the host, so ArchiveJni can run it from Java
# under -Xcheck:jni. Left out without a JDK, and in the sanitizer build, whose
# runtime the JVM would have to preload.
if(NOT LX_SANITIZE)
  find_package(Java COMPONENTS Runtime QUIET)
  if(Java_JAVA_EXECUTABLE)
    get_filename_component(LX_JAVA "${Java_JAVA_EXECUTABLE}" REALPATH)
    get_filename_component(LX_JAVA_HOME "${LX_JAVA}" DIRECTORY)
    get_filename_component(LX_JAVA_HOME "${LX_JAVA_HOME}" DIRECTORY)
    find_path(LX_JNI_INCLUDE jni.h HINTS "${LX_JAVA_HOME}/include" NO_DEFAULT_PATH)
  endif()
  if(LX_JNI_INCLUDE)
    # lxcore goes into a shared object here.
    set_property(TARGET lxcore PROPERTY POSITION_INDEPENDENT_CODE ON)
    add_library(lx_archive_jni_host MODULE app/src/main/cpp/archive_jni.cpp)
    target_include_directories(lx_archive_jni_host PRIVATE "${LX_JNI_INCLUDE}" "${LX_JNI_INCLUDE}/linux")
    target_link_libraries(lx_archive_jni_host PRIVATE lxcore)
    target_compile_options(lx_archive_jni_host PRIVATE -Wall -Wextra -fvisibility=hidden)
    add_dependencies(lx_core_tests lx_archive_jni_host)
    target_compile_definitions(lx_core_tests PRIVATE LX_JNI_HOST_LIB="$<TARGET_FILE:lx_archive_jni_host>"
                                                     LX_JAVA="${LX_JAVA}")
  endif()
endif()

add_executable(lx_core_bench benchmarks/core_bench.cpp)
target_link_libraries(lx_core_bench PRIVATE lxcore)
